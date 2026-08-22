# Week 11 — JFR profiling notes (for later docs)

Raw JFR recordings were not kept (large, machine-specific); numbers and findings below
are what to write up.

## Setup

- `java -XX:StartFlightRecording=filename=...,settings=profile -cp ... com.vuhongquang.Main`
- 2 dummy backends (`benchmarks/dummy_backend.py`, 64 KB bodies), Least Connections.
- k6, 50 VUs, against `/api/movies`.
- Rate limit temporarily raised (`GatewayConfig.rateLimitCapacity` → `1_000_000`) for the
  duration of profiling only, then reverted to `120` — a real k6 run at 50 VUs/no-sleep
  trips the per-IP limit almost instantly otherwise (single client IP), which was the
  first false result (see "Dead ends" below).

## Finding 1 — no `logback.xml`, per-request `INFO` logging was the #1 CPU sample

`com.vuhongquang.BackendResponseHandler.channelRead0` was the single hottest sampled
method in `jdk.ExecutionSample` — hotter than any Netty internal method. The project had
never had a committed `logback.xml`; Logback's bare default config was active for every
benchmark this whole project, including the numbers in `docs/performance.md` (those were
achieved by some ad hoc "run at WARN" setting during that session, never made permanent).

Fix: added `src/main/resources/logback.xml` — root at `INFO`, with
`com.vuhongquang.BackendResponseHandler` and `com.vuhongquang.forwarding.RequestForwarder`
(the two loggers that fire once per request) pinned to `WARN`. Everything else
(`HealthChecker`, `LruResponseCache`, `BackendGatewayService`, startup) stays at `INFO` —
those aren't on the per-request hot path.

**Measured, k6 no-sleep variant (VUS=50, DURATION=20s, same machine, back-to-back):**

| | Before (no logback.xml) | After (logback.xml) | Change |
|---|---|---|---|
| Throughput | 18,775.94 rps | **20,807.78 rps** | **+10.8%** |
| Avg latency | 2.50 ms | **2.14 ms** | -14.4% |
| p95 | 7.47 ms | 7.57 ms | ~unchanged (noise) |
| Max latency | 1,125.70 ms | **58.87 ms** | **19× lower** |
| Failed | 0% | 0% | — |

Raw k6 JSON: `benchmarks/results/week11-logging-before.json` /
`benchmarks/results/week11-logging-after.json`.

Max latency dropping 19× is the more interesting number than the throughput gain —
likely occasional console-flush stalls under per-request `INFO` logging producing rare
but large latency spikes, not a steady-state cost.

## Finding 2 — `ResourceLeakDetector$TraceRecord` dominated exception-throw volume, but disabling it isn't worth it

`jdk.JavaExceptionThrow`: 926 of 1242 total were `io.netty.util.ResourceLeakDetector$TraceRecord`
— Netty's own leak-detection machinery constructs a `Throwable` (stack-trace capture) on
sampled buffer touches. Level was Netty's default (`simple`), never explicitly configured
for this project.

**Measured, before/after `-Dio.netty.leakDetection.level=disabled` (k6 no-sleep, VUS=50,
DURATION=20s, `logback.xml` in place for both):**

| | `simple` (default) | `disabled` | Change |
|---|---|---|---|
| Throughput | 23,058.89 rps | 22,912.62 rps | **-0.6% (noise)** |
| Avg latency | 1.95 ms | 1.97 ms | ~unchanged |
| p95 | 6.62 ms | 6.52 ms | ~unchanged |

**No measurable win.** `simple` already samples ~1% of buffers, so its steady-state cost
was already negligible — unlike Finding 1, this wasn't actually a hot path, just a
frequently-*sampled* one. Conclusion: leave leak detection at Netty's default. The
Prometheus/JFR flame graph reading — high raw exception *count* — didn't translate into
a real throughput cost here; worth remembering that `jdk.JavaExceptionThrow` volume alone
isn't sufficient evidence of a hot path without a before/after to confirm it.

## Finding 3 — `gateway-load-test.js` hits one URI; benchmark itself needs a variant

The checked-in `benchmarks/gateway-load-test.js` always requests the same `TARGET_URL`.
Under Least Connections + the response cache, that means ~99% of requests never reach
`RequestForwarder`/a backend at all — they're served straight from cache. Fine for
measuring cache-hit-path cost, wrong tool for measuring proxy/load-balancer/connection-pool
cost under Week 11 optimization work.

`benchmarks/bench-keyspace.js` (written for the Week 6 cache pressure test, keyspace of
distinct URIs) is the right base to adapt for future proxy-path-focused profiling instead
of `gateway-load-test.js`.

## Dead ends (don't repeat)

- First JFR attempt was run while the gateway had been started from an IntelliJ debug
  session — `com.intellij.rt.debugger.agent.CaptureStorage` polluted both the exception
  count and (presumably) CPU/allocation samples. Always launch the profiled JVM from a
  plain terminal `java` command, never IDE debug mode, for real numbers.
- First clean-launch k6 run showed `failed_rate: 0.99` — not a gateway bug, the
  `SlidingWindowLimiter` (120/min/IP) was rejecting nearly everything because k6's 50 VUs
  all share one client IP and blew through the limit instantly. Raise
  `GatewayConfig.rateLimitCapacity` temporarily for profiling runs, always revert after.
- `benchmarks/gateway-load-test.js` has a client-side `sleep(0.1)` per iteration — caps
  throughput at ~500 rps regardless of server speed (documented in `docs/performance.md`'s
  own Methodology section already, from Week 5 — re-learned the hard way here). Use a
  sleep-stripped copy for anything meant to be server-CPU-bound.
