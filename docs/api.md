# Admin API

Self-management traffic lives under `/gateway/*`, a separate prefix from proxied
traffic (`/api/*`), intercepted by `GatewayHandler` before the routing/proxy path.

| Endpoint | Purpose |
|---|---|
| `GET /gateway/metrics` | Prometheus text-exposition dump — see [`observability.md`](observability.md) |
| `POST /gateway/backends` | Register a backend (new or existing route) |
| `PATCH /gateway/backends/{id}` | Update a backend's `CircuitBreaker` config |
| `DELETE /gateway/backends/{id}` | Remove a backend from pool, connection manager, and health checker |
| `POST /gateway/discovery` | Register DNS-based service discovery for a route |

## `GET /gateway/metrics`

```bash
curl localhost:1221/gateway/metrics
```

## `POST /gateway/backends`

New `route` → creates a `BackendPool` with the given `strategy`. Existing `route` →
appends to that pool (rejects `500` on duplicate address).

```json
{"route":"/api/movies","host":"localhost","port":8081,"openDurationMs":5000,"failureRateThreshold":0.5,"minimumCalls":10,"windowSize":20,"strategy":0,"forceStream":false,"hashKeyType":null}
```

| Field | Type | Meaning |
|---|---|---|
| `route` | string | URI prefix. Existing → append; new → create |
| `host`, `port` | string, int | Backend address; `host:port` is its `id` |
| `openDurationMs` | long | How long the breaker stays `OPEN` before probing |
| `failureRateThreshold` | double `(0,1]` | Failure ratio that trips the breaker |
| `minimumCalls` | int | Calls required in-window before threshold is evaluated |
| `windowSize` | int | Breaker history size |
| `strategy` | int | Only used on a brand-new route: `0` = Least Connections, `1` = Round Robin, `2` = Consistent Hashing |
| `forceStream` | boolean | Only used on a brand-new route: stream the request body straight to the backend instead of buffering (see [`architecture.md`](architecture.md#forwarding-package)) |
| `hashKeyType` | string, nullable | Only used on a brand-new route, only meaningful when `strategy` is `2`: `"CLIENT_IP"` or `"URI"` — which value the consistent-hash ring hashes on. Ignored (and persisted as `null`) for the other two strategies |

`strategy`/`forceStream`/`hashKeyType` are only honored when this call **creates** the route's pool — appending a backend to an existing route ignores whatever you send for them, the same way `openDurationMs` etc. only set the *new* backend's own breaker.

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

## `POST /gateway/discovery`

Registers DNS-based discovery for a route: on an interval, resolves `hostname`
and reconciles the route's backend pool against the result — adds newly-seen
IPs, removes ones no longer present, leaves existing ones (and their live
circuit-breaker/health state) untouched. One discovery registration per route;
a duplicate `route` is rejected.

```json
{"hostname":"backend.internal","route":"/api/movies","pollIntervalMs":3000,"port":8081,"openDurationMs":5000,"failureRateThreshold":0.5,"minimumCalls":10,"windowSize":20,"strategy":0,"forceStream":false}
```

| Field | Type | Meaning |
|---|---|---|
| `hostname` | string | DNS name to resolve on each poll |
| `route` | string | URI prefix to reconcile backends into |
| `pollIntervalMs` | long | How often to re-resolve `hostname` |
| `port` | int | Port every discovered backend is assumed to listen on |
| `openDurationMs`, `failureRateThreshold`, `minimumCalls`, `windowSize` | — | Circuit-breaker config applied to each newly-discovered backend |
| `strategy`, `forceStream` | — | Same meaning as [`POST /gateway/backends`](#post-gatewaybackends); only takes effect if discovery is the first thing to create the route's pool |

`200` registered · `400` malformed JSON / duplicate route · `500` other failure

```bash
curl -X POST localhost:1221/gateway/discovery -H 'Content-Type: application/json' \
  -d '{"hostname":"backend.internal","route":"/api/movies","pollIntervalMs":3000,"port":8081,"openDurationMs":5000,"failureRateThreshold":0.5,"minimumCalls":10,"windowSize":20,"strategy":0,"forceStream":false}'
```

Discovery config survives a restart (persisted the same way backends are) and
resumes polling/reconciling automatically on boot — no re-registration needed.
