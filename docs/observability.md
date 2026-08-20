# Observability

Metrics are collected with [Micrometer](https://micrometer.io) into one shared
`PrometheusMeterRegistry`, built once in `Main` and passed into every component that
needs to record something. `GET /gateway/metrics` scrapes it — see
[`api.md`](api.md#get-gatewaymetrics).

## `gateway_requests_total` (counter)

Incremented once per request, in `BackendResponseHandler.channelRead0`, **before** the
rate-limit check runs. That ordering matters: a request that gets rejected with `429`
still counts here, so this number is "requests that arrived," not "requests that got
past the gate" — the two are easy to conflate and would silently disagree with
`gateway_request_duration_seconds`'s total count if the increment moved after the
rate-limit gate.

## `gateway_request_duration_seconds` (timer, tagged by `status`)

Wall-clock time from request arrival to response written, for every exchange without
exception — success, cache hit, `429`, `404`, `503`, `502`/`504`, and every
`channelInactive`/`exceptionCaught`/write-failure path. One `Timer.Sample`
(`Timer.start(registry)`) is created per request and threaded through `forward` →
`attemptRequest` → every retry, stopped exactly once via a shared `stopTimer(time,
status)` helper.

Two things had to be gotten right here, both because they failed quietly the first time:

- **The tag value is the numeric status code (`status.code()`), not `HttpResponseStatus`
  itself.** `HttpResponseStatus.toString()` returns the full reason phrase (`"502 Bad
  Gateway"`), and Prometheus's `=~` label matching is a *full-string* match by default —
  `status=~"5.."` silently matches nothing against a value like that, since the value is
  longer than the pattern. A dashboard built against that tag would just show empty data,
  no error.
- **Every exit path stops the timer exactly once.** Some paths call both `sendError` and
  `finishExchange` for the same exchange (`channelInactive`, `exceptionCaught`,
  write-failure) — the two used to each independently call `.stop()`, double-counting
  every backend-side failure into this timer's `_count`/`_sum` without affecting
  `gateway_requests_total`, so the two metrics would quietly disagree only under backend
  failures. `finishExchange`'s own `AtomicBoolean done` guard now doubles as the
  single-stop guard: `sendError` never stops the timer on paths where `finishExchange`
  will also run.

## `gateway_backend_active_connections{address}` / `gateway_backend_healthy{address}` (gauges)

Both self-register inside `Backend`'s constructor — no manual wiring anywhere else, so a
backend added via `POST /gateway/backends` at 3am shows up in `/gateway/metrics` on its
next scrape with no code path to forget. Each gauge reads live off state `Backend` already
maintains for other reasons: `activeConnections` (an `AtomicInteger` the load balancer
increments/decrements) and `healthy` (a `volatile boolean` `HealthChecker` flips).
`gateway_backend_healthy` reports `1.0`/`0.0`, not a boolean — Prometheus gauges are
numeric only.

See [`api.md`](api.md#delete-gatewaybackendsid) for the one caveat: these gauges use
Micrometer's weak-reference form, so a deleted backend's series goes stale (`NaN`)
instead of disappearing.

## Error rate

Not a separate metric — it's `gateway_request_duration_seconds`'s `status` tag, queried
in Grafana/PromQL rather than pre-aggregated in code:

```promql
sum(rate(gateway_request_duration_seconds_count{status=~"5.."}[5m]))
  / sum(rate(gateway_request_duration_seconds_count[5m]))
```

`429` is deliberately excluded from that pattern on purpose: it's the gateway correctly
rejecting a client, not a sign the gateway or a backend is broken. The RED-method
convention (Rate, Errors, Duration) most SRE tooling follows treats client-side 4xx and
server-side 5xx as different signals for exactly this reason — folding `429` into "error
rate" would make a healthy, correctly-throttling gateway look like it's failing under
load, which is the opposite of what actually happened.
