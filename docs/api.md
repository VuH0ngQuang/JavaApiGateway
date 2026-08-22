# Admin API

Self-management traffic lives under `/gateway/*`, a separate prefix from proxied
traffic (`/api/*`), intercepted by `GatewayHandler` before the routing/proxy path.

| Endpoint | Purpose |
|---|---|
| `GET /gateway/metrics` | Prometheus text-exposition dump — see [`observability.md`](observability.md) |
| `POST /gateway/backends` | Register a backend (new or existing route) |
| `PATCH /gateway/backends/{id}` | Update a backend's `CircuitBreaker` config |
| `DELETE /gateway/backends/{id}` | Remove a backend from pool, connection manager, and health checker |

## `GET /gateway/metrics`

```bash
curl localhost:1221/gateway/metrics
```

## `POST /gateway/backends`

New `route` → creates a `BackendPool` with the given `strategy`. Existing `route` →
appends to that pool (rejects `500` on duplicate address).

```json
{"route":"/api/movies","host":"localhost","port":8081,"openDurationMs":5000,"failureRateThreshold":0.5,"minimumCalls":10,"windowSize":20,"strategy":0}
```

| Field | Type | Meaning |
|---|---|---|
| `route` | string | URI prefix. Existing → append; new → create |
| `host`, `port` | string, int | Backend address; `host:port` is its `id` |
| `openDurationMs` | long | How long the breaker stays `OPEN` before probing |
| `failureRateThreshold` | double `(0,1]` | Failure ratio that trips the breaker |
| `minimumCalls` | int | Calls required in-window before threshold is evaluated |
| `windowSize` | int | Breaker history size |
| `strategy` | int | Only used on a brand-new route: `0` = Least Connections, `1` = Round Robin |

`200` created/appended · `400` malformed JSON / unknown strategy · `500` duplicate address / other failure

```bash
curl -X POST localhost:1221/gateway/backends -H 'Content-Type: application/json' \
  -d '{"route":"/api/movies","host":"localhost","port":8081,"openDurationMs":5000,"failureRateThreshold":0.5,"minimumCalls":10,"windowSize":20,"strategy":0}'
```

## `PATCH /gateway/backends/{id}`

Partial update of a backend's breaker config. `{id}` = `host:port`. Every field but
`route` is optional. Because `CircuitBreaker` fields are `final`, this **always**
builds a new breaker and swaps it in — in-flight state (`OPEN`/`HALF_OPEN`, failure
window) is discarded, not preserved. `healthy` is **not** patchable — `HealthChecker`
would silently overwrite it on its next 5s poll.

```json
{ "route": "/api/movies", "failureRateThreshold": 0.8 }
```

`200` (including no-op) · `400` malformed JSON · `404` route/id not found · `500` other failure

```bash
curl -X PATCH localhost:1221/gateway/backends/localhost:8081 -H 'Content-Type: application/json' \
  -d '{"route":"/api/movies","failureRateThreshold":0.8}'
```

## `DELETE /gateway/backends/{id}`

Removes the backend from all three independent stores: `BackendPool`,
`ConnectionPoolManager`, `HealthChecker`. `route` still required — nothing indexes
backends by address gateway-wide.

```json
{ "route": "/api/movies" }
```

`200` · `400` malformed JSON · `404` route/id not found · `500` other failure

```bash
curl -X DELETE localhost:1221/gateway/backends/localhost:8081 -H 'Content-Type: application/json' \
  -d '{"route":"/api/movies"}'
```

**Known limitation**: deleted-backend gauges go stale (`NaN`) instead of
disappearing immediately — Micrometer holds a weak reference, only turns `NaN` once
the `Backend` object is GC-eligible. Harmless for scraping/alerting.
