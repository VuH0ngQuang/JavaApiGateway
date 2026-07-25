# Java API Gateway

A production-inspired API Gateway built from scratch on **Java + Netty**, following a
12-week roadmap (see [`target.md`](target.md)) to learn networking, non-blocking I/O,
distributed systems, and observability by building rather than reading about them.

## Status

Currently through **Week 3** of the roadmap.

| Week | Topic | Status |
|------|-------|--------|
| 1 | Netty Fundamentals (EventLoop, Channel, Pipeline, ByteBuf) | ✅ |
| 2 | Reverse Proxy (forward method/headers/body to a backend) | ✅ |
| 3 | Load Balancer (Round Robin, Least Connections, benchmarking) | ✅ |
| 4 | Health Checks | ⬜ |
| 5 | Connection Pooling | ⬜ |
| 6+ | Auth, Rate Limiting, Resilience, Observability, ... | ⬜ |

## Architecture

```
Client
   |
Netty HTTP Server (BackendResponseHandler)
   |
BackendPool (Round Robin / Least Connections)
   |
   +---> Backend 1
   +---> Backend 2
   +---> ...
```

- **`Main`** — bootstraps the Netty server (`ServerBootstrap`, boss/worker `EventLoopGroup`s)
  and binds to port `1221`.
- **`BackendResponseHandler`** — receives each incoming `FullHttpRequest`, selects a backend
  via `BackendPool`, opens an outbound Netty client connection (`Bootstrap`), forwards the
  request (method, URI, headers, body — with `X-Forwarded-For` added), and relays the
  backend's response back to the original client. Falls back to `502 Bad Gateway` if the
  backend is unreachable.
- **`BackendPool`** — holds the set of available `Backend`s and selects one per request:
  - `next()` — Round Robin, cycles through backends in order.
  - `leastConnections()` — picks the backend with the fewest currently in-flight requests.
- **`Backend`** — wraps a backend's address with an atomic active-connection counter, used
  by Least Connections.

## Running

Requires Java 21+ and Maven.

```bash
mvn compile
mvn exec:java -Dexec.mainClass=com.vuhongquang.Main
```

The gateway listens on `localhost:1221`. Backend targets are currently configured directly
in `Main.java` (dynamic configuration is a later roadmap item — Week 10).

```bash
curl localhost:1221/api/movies
```

## Benchmarking

A [k6](https://k6.io) load test lives in `benchmarks/gateway-load-test.js`, and
`benchmarks/dummy_backend.py` is a minimal fixed-response Python server useful for
generating a clean baseline against a uniform backend pool (bypassing real backend
variance).

```bash
k6 run benchmarks/gateway-load-test.js
k6 run -e TARGET_URL=http://localhost:1221/api/movies -e VUS=20 -e DURATION=30s benchmarks/gateway-load-test.js
```

Each run saves a full-detail JSON summary to `benchmarks/results/<timestamp>.json` for
comparing performance across changes (e.g. before/after adding caching or connection
pooling).

## Tech Stack

- Java 21
- Netty 4.2 (`netty-all`)
- Maven
- Logback (SLF4J)
- k6 (benchmarking)

Planned for later weeks: Redis, Prometheus, Grafana, Docker, GitHub Actions.
