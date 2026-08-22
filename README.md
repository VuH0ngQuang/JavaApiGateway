# Java API Gateway

A production-inspired API Gateway built from scratch on **Java + Netty**, following a
12-week roadmap (see [`target.md`](target.md)) to learn networking, non-blocking I/O,
distributed systems, and observability by building rather than reading about them.

## Summary

- **Reverse proxy** over Netty, with **path-based routing**, **Round Robin / Least
  Connections** load balancing, and **active TCP health checks** every 5s.
- **Connection pooling** (keep-alive reuse, waiter queue, acquire timeout) measured at
  **17.8× throughput** over opening a connection per request.
- **LRU response cache**, byte-budgeted, measured at **4.8×** the hit rate under skewed
  (Zipf) access versus uniform, on the same cache.
- **Rate limiting** behind one interface with two interchangeable implementations —
  token bucket (bursty) and sliding window (hard ceiling) — same configured rate,
  deliberately different behaviour under a burst.
- **Retry (up to 3 attempts, excluding already-tried backends) + a rate-based circuit
  breaker** per backend, closing the gap active-only health checking leaves open.
- **Observability** via Micrometer/Prometheus: request count, latency-by-status (also
  the error-rate signal), per-backend active-connection and health gauges.
- **Dynamic configuration** — backends and routes are created, patched, and deleted at
  runtime through an admin API; `Main` boots with zero hardcoded backends.
- **Performance-tuned request path** — native Epoll transport (falls back to NIO off
  Linux), response-body streaming (no full-response buffering, with backpressure so a
  slow client can't grow the gateway's memory unbounded), and a lock-free rate limiter
  and circuit breaker (no `synchronized`/`ReentrantLock` on the hot path). **+83%
  peak throughput** over the pre-Week-11 baseline, JFR-verified to zero lock
  contention — see [`docs/performance.md`](docs/performance.md#week-11-83-peak-throughput).

Details are split out rather than kept in one file — see the table below.

## Docs

| Doc | Covers |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | Every package, what it owns, and why it's shaped the way it is |
| [`docs/api.md`](docs/api.md) | The `/gateway/*` admin API — add/patch/delete backends, metrics |
| [`docs/observability.md`](docs/observability.md) | What each metric means and the bugs that shaped them |
| [`docs/performance.md`](docs/performance.md) | Full benchmark numbers, charts, and methodology |
| [`target.md`](target.md) | The 12-week roadmap this project follows |

## Status

Through **Week 11** of the roadmap.

| Week | Topic | Status |
|------|-------|--------|
| 1 | Netty Fundamentals (EventLoop, Channel, Pipeline, ByteBuf) | ✅ |
| 2 | Reverse Proxy (forward method/headers/body to a backend) | ✅ |
| 3 | Load Balancer (Round Robin, Least Connections, benchmarking) | ✅ |
| 4 | Health Checks (TCP-level probing, auto add/remove) | ✅ |
| 5 | Connection Pooling (keep-alive reuse, waiter queue, acquire timeout) | ✅ |
| 6 | Response Caching (LRU, byte-budgeted, TTL) | ✅ |
| 7 | Rate Limiting (token bucket + sliding window, per IP) | ✅ |
| 8 | Resilience (retry with backend exclusion, rate-based circuit breaker) | ✅ |
| 9 | Observability (Prometheus/Micrometer metrics) | ✅ |
| 10 | Dynamic Configuration (admin API — add/patch/delete backends & routes) | ✅ |
| 11 | Performance Optimization (Epoll, streaming, lock-free rate limit/circuit breaker) | ✅ |
| 12 | Production Ready (Docker, CI/CD, docs) | ⬜ |

## Running

Requires Java 21+ and Maven.

```bash
mvn compile
mvn exec:java -Dexec.mainClass=com.vuhongquang.Main
```

The gateway listens on `localhost:1221` with **no backends and no routes configured** —
everything is created through the admin API at runtime (Week 10):

```bash
curl -X POST localhost:1221/gateway/backends \
  -H 'Content-Type: application/json' \
  -d '{"route":"/api/movies","host":"localhost","port":8081,"openDurationMs":5000,"failureRateThreshold":0.5,"minimumCalls":10,"windowSize":20,"strategy":0}'

curl localhost:1221/api/movies
```

Full endpoint reference: [`docs/api.md`](docs/api.md).

## Tech Stack

- Java 21
- Netty 4.2 (`netty-all`), native Epoll transport on Linux
- Maven
- Logback (SLF4J)
- Micrometer + Prometheus client (metrics)
- Jackson (admin API JSON)
- k6 (benchmarking); dummy backend for benchmarks is a small Go binary
  (`benchmarks/dummy_backend.go`) — no runtime dependency on the target machine

Planned for later weeks: Docker, GitHub Actions.
