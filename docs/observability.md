# Observability

Metrics collected via [Micrometer](https://micrometer.io) into one shared
`PrometheusMeterRegistry`. `GET /gateway/metrics` scrapes it — see
[`api.md`](api.md#get-gatewaymetrics).

| Metric | Type | Notes |
|---|---|---|
| `gateway_requests_total` | counter | Incremented **before** the rate-limit check — counts arrivals, not admitted requests |
| `gateway_request_duration_seconds` | timer, tagged `status` | One `Timer.Sample` per request, stopped exactly once across all retry/error paths |
| `gateway_backend_active_connections{address}` | gauge | Self-registered in `Backend`'s constructor |
| `gateway_backend_healthy{address}` | gauge | `1.0`/`0.0` — reports live off `Backend.healthy` |

**Two gotchas fixed here:**

- Status tag is the numeric code (`status.code()`), not `HttpResponseStatus.toString()`
  ("502 Bad Gateway") — PromQL `=~"5.."` is a full-string match and would silently match nothing
- Some exit paths call both `sendError` and `finishExchange` — `finishExchange`'s
  `AtomicBoolean done` doubles as a single-stop guard so the timer isn't double-counted

**Deleted-backend gauges**: Micrometer's weak-reference gauges go stale (`NaN`)
instead of disappearing — see [`api.md`](api.md#delete-gatewaybackendsid).

## Error rate (PromQL, not a separate metric)

```promql
sum(rate(gateway_request_duration_seconds_count{status=~"5.."}[5m]))
  / sum(rate(gateway_request_duration_seconds_count[5m]))
```

`429` excluded deliberately — it's the gateway correctly rejecting a client
(RED-method: 4xx and 5xx are different signals), not a failure.
