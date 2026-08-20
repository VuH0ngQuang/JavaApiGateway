# Admin API

All gateway self-management traffic lives under `/gateway/*` — a separate prefix from
proxied application traffic (`/api/*`), intercepted by `GatewayHandler` before it ever
reaches the routing/proxy path (`BackendResponseHandler` → `RequestForwarder`). A request
to `/gateway/*` can never be accidentally forwarded to a backend, and a backend route can
never collide with an admin endpoint.

`GatewayHandler` only does dispatch — path match, then either serve `/gateway/metrics`
directly or delegate everything under `/gateway/backends*` to `BackendGatewayService`,
which owns the actual request parsing, validation, and mutation logic.

## `GET /gateway/metrics`

Returns the full Prometheus text-exposition dump of the shared `PrometheusMeterRegistry`
(same registry every `Backend`, `BackendResponseHandler`, and `RequestForwarder` write
into). See [`observability.md`](observability.md) for what's actually in it.

```bash
curl localhost:1221/gateway/metrics
```

## `POST /gateway/backends`

Registers a new backend. If `route` already exists, the backend is appended to that
pool's existing `LoadBalancingStrategy` and rejected with `500` if the address is a
duplicate. If `route` does not exist yet, a brand-new `BackendPool` is created with the
strategy named in the request and registered into `Router` immediately — no restart, no
pre-declared routes. `Main` starts with zero backends and zero routes; every route in a
running gateway was created by a call to this endpoint.

```json
{
  "route": "/api/movies",
  "host": "localhost",
  "port": 8081,
  "openDurationMs": 5000,
  "failureRateThreshold": 0.5,
  "minimumCalls": 10,
  "windowSize": 20,
  "strategy": 0
}
```

| Field | Type | Meaning |
|---|---|---|
| `route` | string | URI prefix this backend serves. Existing prefix → append; new prefix → create. |
| `host`, `port` | string, int | Backend address. `host:port` is also this backend's `id` for `PATCH`/`DELETE`. |
| `openDurationMs` | long | How long the `CircuitBreaker` stays `OPEN` before probing again. |
| `failureRateThreshold` | double, `(0,1]` | Failure ratio inside the window that trips the breaker. |
| `minimumCalls` | int, `1..windowSize` | Calls required in-window before the threshold is evaluated at all. |
| `windowSize` | int | Ring-buffer size the breaker's pass/fail history is tracked over. |
| `strategy` | int | **Only consulted when `route` doesn't exist yet.** `0` = Least Connections, `1` = Round Robin. |

Responses: `200` created/appended · `400` malformed JSON, or unknown `strategy` on a
brand-new route · `500` duplicate address, or any other failure (logged with the route).

```bash
curl -X POST localhost:1221/gateway/backends \
  -H 'Content-Type: application/json' \
  -d '{"route":"/api/movies","host":"localhost","port":8081,"openDurationMs":5000,"failureRateThreshold":0.5,"minimumCalls":10,"windowSize":20,"strategy":0}'
```

## `PATCH /gateway/backends/{id}`

Partially updates an existing backend's `CircuitBreaker` config. `{id}` is `host:port`
(e.g. `localhost:8081`). Every field except `route` is optional — omit a field to keep
its current value; the handler reads the *existing* breaker's config via its getters and
merges only what the request actually supplies.

```json
{ "route": "/api/movies", "failureRateThreshold": 0.8 }
```

Because `CircuitBreaker`'s own fields are `final`, an update can't mutate the running
instance — it always builds a **new** `CircuitBreaker` and swaps it in via
`Backend.setBreaker(...)`. The consequence: whatever `CLOSED`/`OPEN`/`HALF_OPEN` state
and failure-window history the old breaker was tracking is discarded. A backend mid-`OPEN`
that gets patched comes back `CLOSED` with an empty window. Accepted tradeoff, not a bug —
there is no way to reconfigure thresholds *and* preserve in-flight tracking state without
a different breaker design.

`healthy` is deliberately **not** patchable here: `HealthChecker` polls every 5s and would
silently overwrite a manual override on its next cycle, making a "drain this backend"
PATCH look like it worked for a few seconds and then quietly stop.

Responses: `200` (including when zero fields were provided — a no-op patch is still a
successful request) · `400` malformed JSON · `404` route or `id` not found · `500` other
failure.

```bash
curl -X PATCH localhost:1221/gateway/backends/localhost:8081 \
  -H 'Content-Type: application/json' \
  -d '{"route":"/api/movies","failureRateThreshold":0.8}'
```

## `DELETE /gateway/backends/{id}`

Removes a backend from all three places that independently track it — they share no
underlying collection, so each needs its own removal call: `BackendPool` (no longer
selectable), `ConnectionPoolManager` (its dedicated `ConnectionPool` entry dropped),
`HealthChecker` (stops polling it). `route` is still required in the body — `{id}` alone
isn't enough to find *which* pool holds it, since nothing indexes backends by address
across the whole gateway.

```json
{ "route": "/api/movies" }
```

Responses: `200` · `400` malformed JSON · `404` route or `id` not found · `500` other
failure.

```bash
curl -X DELETE localhost:1221/gateway/backends/localhost:8081 \
  -H 'Content-Type: application/json' \
  -d '{"route":"/api/movies"}'
```

**Known limitation:** the `gateway_backend_active_connections` / `gateway_backend_healthy`
gauges for a deleted backend don't disappear from `/gateway/metrics` immediately.
Micrometer's `registry.gauge(...)` holds a *weak* reference to the `Backend` object it
reads from; once nothing else holds a strong reference (all three removals above have to
have happened), the object becomes GC-eligible, and only then does the gauge start
reporting `NaN` in place of a real value. The series itself is never actually deregistered
— it just goes stale. Harmless for scraping/alerting (most PromQL functions treat `NaN`
as no-data), but worth knowing before staring confused at a metrics dump full of `NaN`
rows for backends that were deleted an hour ago.
