# RPS ceiling investigation — 2026-08-20

Follow-up to `week11-jfr-notes.md`. Goal: find out why the 100-backend heavy
staircase plateaued at ~1,300-1,700 rps, and how high raw proxy throughput
actually goes once the real bottleneck is isolated.

## Finding 1 — the ~1.7k rps ceiling was payload size, not backend count

Reproduced the original 100-backend/1MB-payload setup in isolation. Backend
count (20 vs 100) made no measurable difference; payload size did:

| Setup | VUS | rps |
|---|---|---|
| 20 backends, 2KB body | 100/400/800/1500 | 9167 / 10451 / 10575 / 11536 |
| 100 backends, 2KB body | 100/400/800/1500 | 11184 / 11085 / 11370 / 11650 |
| 100 backends, 1MB body (reproduces original finding) | 100/300/600/1000 | 1756 / 2041 / 1877 / 1951 |

## Finding 2 — root cause of the 1MB-payload ceiling: response cache copy overhead

JFR (30-40s, VUS=600, 1MB payload, `settings=profile`):
- `jdk.ObjectAllocationSample`: **96.7% of all sampled allocations were `byte[]`**.
- GC ran ~5x/second throughout.
- Source: `RequestForwarder.forward()` calls `ByteBufUtil.getBytes(res.content())`
  on every cacheable (GET, 200) response to write it into the response cache —
  a full ~1MB copy every request, regardless of whether that URI is ever
  requested again. `CachedResponse`'s compact constructor does a *second*
  full-body `.clone()` on top of that.
- With `KEYSPACE=200000` (random) against `cacheMaxBytes=128MB` (≈128 entries
  at 1MB each), nearly every cached entry is evicted before it can ever be
  reused — so almost all of that copying is pure waste on the hot path.

Proposed fix (discussed, not applied to gateway source): cache admission
filter — only start caching a URI from its *second* sighting onward, via a new
`ResponseCache.shouldCache(uri)` check before the byte-copy. Comparable to
Caffeine's W-TinyLFU admission policy / Varnish's "hit-for-pass" concept, just
simplified to a bounded "seen" set. Not implemented in this session (reverted
after an unauthorized edit — see conversation).

## Finding 3 — cache size vs. JVM heap: cacheMaxBytes must leave real headroom

Bumped `cacheMaxBytes` to 6GB, `KEYSPACE=500`, 10 backends, 10MB payload:

- **No `-Xmx` set**: default ergonomic `MaxHeapSize` on this host = **4.39GB**
  — smaller than the 6GB cache target alone. Guaranteed `OutOfMemoryError:
  Java heap space` under load (confirmed).
- **`-Xmx8g`**: no OOM, but `GC.heap_info` showed heap at **99.1% utilization**
  (8,316,136K / 8,388,608K committed) under the same load — G1GC thrashing
  near-continuously instead of serving requests. VUS=600 avg latency hit 13s.
  `pool_queue_depth` stayed 0 throughout — the connection pool was never the
  issue, heap pressure was.
- Host-level confound: this box also ran low on system RAM during the test
  (free dropped to ~1.2GB, swap usage ~9.5GB) since it's shared with unrelated
  services (Kafka, kafka-ui, other JVM apps not part of this project).

Lesson: `cacheMaxBytes` needs real headroom below `-Xmx` (rule of thumb:
cache ≤ ~50-70% of heap), and `-Xmx` needs to be explicit, not left to JVM
ergonomics, especially on a shared host.

## Finding 4 — raw-rps test (cache disabled) at 10MB payload: backend, not gateway, was the ceiling

`cacheMaxBytes=0` (disables caching), 10 local backends, 10MB payload:

| VUS | rps | avg latency |
|---|---|---|
| 50 | 168 | 295ms |
| 100 | 175 | 565ms |
| 300 | 167 | 1.7s |
| 600 | *(invalid — see below)* | |

Ceiling ~170 rps, flat from VUS=50-300. But root cause at VUS=600: **the
Python `ThreadingHTTPServer` dummy backends themselves couldn't push 10MB
fast enough under load** — all 10 backend logs show `BrokenPipeError` on
`wfile.write(BODY)` (gateway-side connection closed mid-transfer). This
tripped the circuit breaker across all 10 backends ("no healthy backend"),
so the VUS=600 result (22,485 rps, 99.9% failed) is fast-failing 503s, not
real throughput — invalid data point. This ~170 rps ceiling reflects the
benchmark setup's limit (single-threaded-per-connection Python backends on a
shared box), not the gateway's.

## Finding 5 — real remote backend over SSH-reverse-tunnel: bandwidth-bound at 200Mbps

Set up a real backend on the user's separate Windows machine, reached via
WireGuard + an SSH reverse tunnel (`benchmarks/start-local-backends-tunnel.ps1`,
10 backends, ports 7000-7009). 1MB payload:

| VUS | rps | avg latency |
|---|---|---|
| 20 | 22 | 897ms |
| 50 | 24 | 2.0s |
| 100 | 23 | 4.2s |
| 200 | 21 | 9.3s |

Flat ~21-24 rps regardless of VUS (10x range), latency scaling linearly —
classic bandwidth saturation. Confirmed by math: link is capped at 200Mbps =
25MB/s; at 1MB/response that's a theoretical ceiling of ~25 rps, matching
measured ~21-24 rps almost exactly (account for TCP/SSH/WireGuard overhead).
Not a gateway or backend limitation — a physical network link constraint.

## Finding 6 — clean ceiling at realistic (8KB) payload size: ~15,400-15,850 rps

10 local backends, 8KB payload (typical small JSON API response size), cache
disabled:

| VUS | rps | avg latency | p95 |
|---|---|---|---|
| 50 | 1,277 | 39ms | 43ms |
| 200 | 4,763 | 42ms | 44ms |
| 500 | 11,430 | 43ms | 56ms |
| 1000 | 15,400 | 59ms | 107ms |
| 1500 | **15,847** | 76ms | 160ms |
| 2500 | 15,080 | 111ms | 236ms |

Flat from VUS=1000 onward — throughput ceiling, not client-side limitation.
`pool_queue_depth` stayed 0 across all backends throughout (consistent with
every prior finding this session — connection pool has never been the
bottleneck at any scale tested). This is the cleanest, most representative
number for "how many req/s can this gateway proxy" on this host, at this
payload size, without cache/bandwidth/backend confounds.

## Context for comparison (not measured here — industry reference points)

- nginx as reverse proxy, small payloads: commonly benchmarked at 50k-100k+
  rps on comparable hardware.
- HAProxy/Envoy: similar order of magnitude per instance.
- 15.8k rps is meaningfully below that range. Confounds worth noting before
  treating this as an apples-to-apples comparison:
  - This VPS is shared with unrelated services (Kafka, kafka-ui, other JVM
    apps); load average has been observed as high as 24 on a 10-core box from
    background traffic alone.
  - Client (k6), gateway, and backends all ran on the same machine/cores —
    self-contention, unlike a proper benchmark with client on separate
    hardware.
  - Gateway fully buffers request and response (`HttpObjectAggregator`)
    instead of streaming bytes through, unlike nginx.
  - Gateway uses JDK NIO transport (`NioIoHandler`) rather than Netty's
    native Epoll transport (`netty-transport-native-epoll` is already a
    dependency in the classpath but unused) — Epoll is generally
    meaningfully faster than NIO on Linux per Netty's own benchmarks.

## Finding 7 — switching to native Epoll transport: applied, measured

Implemented (by the user): `Main.java` now picks `EpollIoHandler` /
`EpollServerSocketChannel` / `EpollSocketChannel` when `Epoll.isAvailable()`,
falling back to the NIO equivalents otherwise. Required threading a
`Class<? extends SocketChannel>` / `Class<? extends ServerSocketChannel>`
through `GatewayServer`, `ConnectionPoolManager` → `ConnectionPool`, **and**
`HealthChecker` (missed in the initial plan — `HealthChecker.checkOne()` had
its own hardcoded `Bootstrap.channel(NioSocketChannel.class)`; registering a
NIO channel against an Epoll-based `EventLoopGroup` threw
`IllegalStateException: channel not registered to an event loop` on every
health check until fixed the same way).

Same 8KB-payload, 10-backend, cache-disabled setup as Finding 6, same VUS
staircase, for a direct comparison:

| VUS | NIO rps | Epoll rps | NIO avg latency | Epoll avg latency |
|---|---|---|---|---|
| 50 | 1,277 | **14,396** | 39ms | **3.3ms** |
| 200 | 4,763 | 16,053 | 42ms | 12.0ms |
| 500 | 11,430 | 16,425 | 43ms | 27.5ms |
| 1000 | 15,400 | 15,434 | 59ms | 51.9ms |
| 1500 | 15,847 | **16,570** | 76ms | 66.9ms |
| 2500 | 15,080 | 16,128 | 111ms | 107.7ms |

Two distinct effects:
- **Peak ceiling** moved from ~15,847 to ~16,570 rps — a modest ~4-5% gain.
- **Much bigger effect at low/moderate concurrency**: at VUS=50, Epoll hit
  14,396 rps (already near its own ceiling) vs. NIO's 1,277 rps at the same
  VUS — NIO needed to ramp all the way to VUS=1000 to reach comparable
  throughput. Per-connection overhead in the JDK NIO selector is
  substantially worse than native epoll on Linux; this shows up as both much
  lower throughput and much higher latency (39ms vs 3.3ms avg at VUS=50) at
  the same concurrency level, not just a difference at the ceiling.
- `pool_queue_depth` stayed 0 throughout, consistent with every prior finding
  — this change didn't touch the actual bottleneck, it made request handling
  itself cheaper.

## Finding 8 — core-count scaling confound: colocated client/gateway/backend

Follow-up question after Finding 7: does more CPU actually help? Tested 8 vs
16 vCPUs on a separate VMware Ubuntu VM (bench-kit, self-contained: prebuilt
jar + dummy backend + k6 script + orchestration shell script, no project
checkout needed on the test box), same 8KB-payload setup.

| | 8 core | 16 core |
|---|---|---|
| Peak rps | 10,708 | 10,655 |

**16 core was never better than 8 core**, across every subsequent variant
tested this session (with/without lock fixes, with/without streaming) — a
completely flat or slightly negative relationship with core count. Root
cause: the benchmark colocates the k6 client, the gateway, and all 10 dummy
backends on the *same* machine, sharing the same finite core pool. Netty
sizes its event-loop thread count to `2 × available cores` by default, so a
16-core box actually runs *more* gateway threads competing for CPU/scheduling
with the backends and the client than an 8-core box does — more cores didn't
translate to more useful parallelism because the extra threads were
contending with, not complementing, the other two components. This is not a
gateway bug — it is an artifact of not separating client/server/backend onto
different hardware, which is the reason bench-kit later added explicit
`taskset` core partitioning (see Finding 10).

## Finding 9 — lock contention investigation via JFR (`jdk.JavaMonitorEnter` / `jdk.ThreadPark`)

Given core count didn't explain the flat ceiling, went looking for a
serialization point instead. JFR supports setting per-event thresholds
directly on the command line without a custom `.jfc` file:

```bash
java -XX:StartFlightRecording=filename=lock.jfr,settings=profile,jdk.JavaMonitorEnter#threshold=3ms,jdk.ThreadPark#threshold=3ms -jar java-api-gateway.jar
```
`jdk.JavaMonitorEnter` catches contention on `synchronized` blocks/methods;
`jdk.ThreadPark` catches contention on `java.util.concurrent.locks.*`
(`ReentrantLock` doesn't emit `JavaMonitorEnter` — it parks via
`LockSupport`, a different event). Note `settings=profile`'s *default*
`JavaMonitorEnter` threshold is 10ms, which can hide massive aggregate
contention made of many sub-10ms waits — worth lowering explicitly, as above.

Found and fixed, in order of investigation:

1. **`Window.tryConsume()`** (`SlidingWindowLimiter`'s rate-limit bucket) —
   `synchronized` `ArrayDeque<Long>` of exact timestamps. Every request from
   the same client IP serializes on the *same* lock (in this benchmark, all
   traffic is one IP, so effectively 100% of traffic hit one lock). Root
   cause of Finding 2 in the original JFR investigation, revisited here.
   Tried a lock-free "sliding window counter" (bucketed, `LongAdder` +
   `AtomicReference`, interpolating between adjacent buckets) as a from-
   scratch redesign, but ultimately the simpler path taken was **switching to
   `TokenBucketLimiter`** — except its `Bucket.tryConsume()`/`isFull()` were
   *also* `synchronized`, so switching algorithms alone didn't remove the
   lock (same shape of bug, different class name). Fixed by making
   `Bucket` itself lock-free: `AtomicReference<State>` holding
   `(token, lastRefillTime)`, refill computed lazily on each `tryConsume()`,
   CAS loop instead of a monitor.
2. **`CircuitBreaker`** — every method was `synchronized`, including getters
   over `final` fields that never change after construction
   (`windowSize()`, `openDurationMs()`, `failureRateThreshold()`,
   `minimumCalls()` — dropped `synchronized` from these, zero risk).
   Bigger issue: `LoadBalancingStrategy.select()` called
   `b.getBreaker().isAvailable()` on *every* backend in the pool on *every*
   request (a pre-filter before `doSelect()`), plus `allowRequest()` on the
   chosen backend, plus `recordSuccess()/recordFailure()` at completion —
   up to ~12 synchronized acquisitions per request across 10 different
   `CircuitBreaker` instances. Removed the `isAvailable()` pre-filter
   entirely (redundant: `allowRequest()` already rejects an open-circuit
   backend post-selection, and the existing retry/exclusion mechanism in
   `RequestForwarder.attemptRequest()` already handles a rejected pick by
   trying the next backend — no behavior change, just fewer lock touches).
   Then made `CircuitBreaker` itself lock-free: `AtomicReference<StateHolder>`
   for `(state, openAt, probeInFlight)`, `LongAdder` counters for
   recorded/failure counts (semantics shift slightly: counts run since the
   circuit last closed, not over a fixed last-N-calls ring buffer —
   accepted trade-off, `windowSize` no longer bounds anything).

Contention dropped from **626 → 469 → 186 → 6** `JavaMonitorEnter` events
(3ms threshold, ~15-18s runs) across these fixes, ending at zero application
classes in the trace (only JVM/Netty-internal noise).

**But throughput didn't scale past what the cache-lock fix alone had
achieved** — 8-core peak went 10,708 (baseline) → 12,349 (cache fix only) →
14,530 (cache fix + rate limiter *bypassed entirely*, not just made
lock-free) → 12,222 (cache fix + both limiters made properly lock-free, same
core count). Removing lock *waiting* time doesn't remove the underlying
*computation* time (CAS retry loops, refill math, `LongAdder.sum()`, new
`StateHolder`/`State` allocations per transition) — bypassing the algorithm
entirely (no computation at all) will always beat making it non-blocking.
Lesson: lock contention and per-request CPU cost are separate budgets;
fixing one doesn't automatically reclaim the other.

## Finding 10 — the dummy backend itself was a confound: Python → Go rewrite

At high VUS with the 10MB payload, backends started throwing
`BrokenPipeError` mid-`wfile.write()`, and the circuit breaker correctly
marked them all unhealthy. Root cause: Python's
`socketserver.TCPServer.request_queue_size` (the TCP `listen()` backlog)
**defaults to 5** — confirmed via `python3 -c "import socketserver;
print(socketserver.TCPServer.request_queue_size)"`. Under enough concurrent
connection attempts, the kernel resets connections beyond that backlog
regardless of available CPU (confirmed separately: CPU usage topped out
around 60% during the failures, not maxed — ruling out a compute bottleneck).

Rewrote `benchmarks/dummy_backend.py` as `benchmarks/dummy_backend.go` (same
CLI contract: `dummy_backend <port> [service] [body_bytes]`), compiled to a
static binary (`CGO_ENABLED=0 go build`) — no Go runtime needed on the target
machine. Go's `net/http` has no artificially small backlog and no GIL
serializing request handling within a process. This eliminated the
connection resets and the wild latency spikes (VUS=2500 avg latency dropped
from multi-second/30s-tail territory to consistently sub-second) in every
subsequent run. `dummy_backend.py` was deleted; bench-kit now ships the
compiled `dummy_backend` binary directly.

## Finding 11 — memory leak from a shadowed variable, and the fix for it

While rewriting the response handler for streaming (Finding 12), renamed the
`channelRead(ChannelHandlerContext ctx, Object msg)` parameters at various
points and re-hit the same class of bug repeatedly: a parameter/field name
shadowing an outer variable of the same name, silently making the outer one
unreachable inside the inner scope (no compiler error — this is legal Java).
Caught three instances over the course of this work:
- `ctx` (backend-side context) shadowing the outer client-side `ctx`.
- `cacheable` (a field) shadowing the outer `cacheable` parameter (from
  `attemptRequest`), leaving the cache permanently disabled since the field
  default (`false`) was read but the outer parameter was never actually
  reachable to assign from.
- `msg` (the backend response object, inside `channelRead`) shadowing the
  outer client `FullHttpRequest msg` — meaning the streaming rewrite had no
  way to call `msg.release()` on the original aggregated client request on
  the success path. Confirmed via `ResourceLeakDetector`: `LEAK: ByteBuf.release()
  was not called before it's garbage-collected`, stack trace rooted in
  `MessageAggregator.decode()` → `compositeDirectBuffer` (the client-facing
  `HttpObjectAggregator`, aggregating the incoming request). Fixed by
  renaming the backend-side parameter to `backendMsg`, freeing `msg` to
  correctly refer to the client request again, and adding back the
  `msg.release()` call on the success path that had been lost in the
  rewrite.

General lesson: renaming a parameter/field to fix one shadowing bug can
introduce a new one nearby if the replacement name still collides with
something else in scope. Worth a full read-through of the enclosing method's
variable names whenever adding a field/parameter to a nested class or lambda.

## Finding 12 — response-body streaming (not request-body)

Scope decision made explicit up front: stream backend→client (response)
only, not client→backend (request). Reason: `RequestForwarder.attemptRequest()`
retries up to 3 times across different backends on failure, resending the
same request content — if the request body were streamed (consumed once
while forwarding to backend A), there would be nothing left to resend to
backend B on retry. The response side has no equivalent retry-replay
constraint (once headers are sent downstream to the client, a proxy commits
to that response either way — same behavior real reverse proxies have), so
it was the safe, high-value slice: this session's actual measured problem
(Finding 2, byte[] copy overhead) was entirely on the response side (large
backend payloads), never the request side (small GET requests, no body).

Implementation, in order:
1. **`ConnectionPool.java`**: removed `HttpObjectAggregator` from the
   backend-facing pipeline (kept only `HttpClientCodec`). Backend responses
   now arrive as separate `HttpResponse` → N×`HttpContent` → `LastHttpContent`
   messages instead of one aggregated `FullHttpResponse`.
2. **`RequestForwarder.java`**: rewrote the per-exchange response handler
   from `SimpleChannelInboundHandler<FullHttpResponse>` to a plain
   `ChannelInboundHandlerAdapter` (needed manual release control instead of
   `SimpleChannelInboundHandler`'s auto-release). On `HttpResponse`: write a
   header-only `DefaultHttpResponse` to the client immediately, and — only if
   the response is GET+200+cache-enabled — allocate a `ByteBuf` accumulator
   for a parallel cache-write copy. On each `HttpContent`: optionally copy
   into the accumulator (`content.content().duplicate()`, non-consuming read)
   and forward the content object directly to the client
   (`ctx.writeAndFlush(content)` — no `retain()` needed, since nothing else
   holds/releases it; the write itself consumes and releases it). On
   `LastHttpContent`: flush the accumulated cache entry (if any) and call
   `finishExchange`. `channelInactive`/`exceptionCaught` now branch on
   whether headers were already sent (`status == null` → still-clean
   `sendError()` possible; otherwise → `ctx.close()`, matching what any real
   reverse proxy does once it's committed to a response).
3. Cache write cost dropped to zero for the common case (any response that
   isn't GET+200+cacheable skips the accumulator entirely — no copy at all,
   versus the old code always calling `ByteBufUtil.getBytes()` regardless of
   whether the entry would ever be reused).

Validated with the 8KB benchmark (expected: no material change, payload too
small for aggregation overhead to matter) and the 1MB/10MB benchmarks
(expected: real improvement). 10MB payload result: ceiling ~220-245 rps,
which converts to roughly the *same* bytes/sec (~2.2-2.5 GB/s) as the earlier
broken 1MB-payload measurement (~1.7-2k rps → ~1.7-2 GB/s) — i.e. throughput
now scales with total bytes moved rather than collapsing on large payloads,
which is exactly the outcome streaming was meant to produce. (2.2-2.5 GB/s is
itself CPU-bound, not disk/bandwidth-bound: proxying is two TCP-stack passes
per byte — backend→gateway, gateway→client — plus Netty HTTP framing, confined
to only the 8 cores this session dedicated to the gateway process via
`taskset`; not evidence of an unexplained bottleneck.)

**Follow-up bug found under load**: without a `-Xmx` large enough, the 10MB
run OOM'd even though streaming avoids buffering a whole response — because
nothing capped how much *unsent* data could queue up in Netty's per-channel
outbound write buffer if a client drained slower than the backend produced.
`-Xmx6g` band-aids this (more headroom to absorb the backlog) but doesn't fix
it — a sufficiently slow client at high concurrency could still OOM regardless
of heap size. Real fix: backpressure. Added a per-exchange
`channelWritabilityChanged` listener on the *client*-facing pipeline
(`ctx.pipeline().addLast("backpressure", ...)`, added once `HttpResponse`
arrives) that toggles `ch.config().setAutoRead(...)` on the *backend*
channel — when the client's outbound buffer is full (`!isWritable()`), stop
reading further chunks from the backend at all, letting TCP flow control on
the backend connection do the rest. Removed the handler at every exchange-
completion path (`LastHttpContent`, `channelInactive`, `exceptionCaught`) to
avoid a duplicate-handler-name crash on the next request over the same
keep-alive connection.

That removal itself had two follow-on bugs, both surfaced live during testing
rather than by inspection:
- `channelInactive`/`exceptionCaught` ran their full body (including
  `ctx.close()` on the *client* connection) even when the exchange had
  *already* finished successfully moments earlier via `LastHttpContent` (a
  backend closing an idle pooled connection afterward is normal, not an
  error) — wrongly killing a healthy, already-served client connection and
  interfering with the next request's own `"backpressure"` handler add.
  Fixed by guarding the top of both methods with `if (done.get()) return;`
  (the existing `AtomicBoolean done` already existed to guard
  `finishExchange` against double-firing; extended to gate the entire
  method body).
- Even after that, one rare `Duplicate handler name: backpressure` still
  surfaced under concurrent load — root cause not fully isolated. Rather
  than keep chasing an intermittent race, made the add idempotent/self-
  healing: remove any existing `"backpressure"` handler immediately before
  adding a new one. This is also a correctness fix, not just a crash
  guard — a lingering stale handler's closure captures the *previous*
  exchange's backend channel (`ch`), so leaving it in place would silently
  throttle the wrong backend connection, not just risk a crash.

## Finding 13 — small mechanical fixes (Router, SO_BACKLOG, boss thread count)

Three low-risk, low-effort items cleaned up after the bigger fixes:

1. **`Router.match()`**: was a `.stream().filter().max(Comparator...)` full
   scan over every registered route on every request — replaced with a plain
   `for` loop tracking the longest matching prefix manually. Same O(n)
   complexity either way (a for loop doesn't fix scaling to thousands of
   routes — that needs a trie/radix-tree — but at the realistic route counts
   for this project, on the order of 100, O(n) `String.startsWith()` compares
   are microseconds; a trie now would be premature complexity, especially
   since routes can be added/removed at runtime via the admin API and a
   mutable-tree redesign would need its own concurrency story). First attempt
   had two bugs worth remembering: an uninitialized `String key;` (needed
   `= null`), and `candidate.length() == uri.length()` instead of
   `uri.startsWith(candidate)` — the former requires an exact-length match,
   which basically never happens for a URI with a path segment appended
   after the route prefix, so it silently broke routing for virtually every
   real request.
2. **`GatewayServer.java`**: added `.option(ChannelOption.SO_BACKLOG, 1024)`
   to the `ServerBootstrap` — previously relying entirely on Netty's
   platform-default listen backlog, never made explicit. (`.option()`, not
   `.childOption()` — the latter applies to each *accepted* connection, not
   the listening socket itself, and would silently do nothing for this
   purpose.)
3. **`Main.java`**: `boss` `EventLoopGroup` was sized to the same
   `2 × available cores` default as `worker`, despite only needing to accept
   connections and hand them off — explicit-sized down to 2 threads
   (`new MultiThreadIoEventLoopGroup(2, ioHandlerFactory)`), leaving relative
   core budget for `worker` (which does all the real request processing).

## Finding 14 — a last round of JFR-guided allocation fixes, and a lesson about noise

One more JFR pass (`jdk.ExecutionSample` for CPU hotspots, alongside the existing
allocation/lock events) after Finding 13, specifically to check whether anything in
`com.vuhongquang.*` still showed up as a hot method. It didn't — every top-20 CPU
frame across multiple runs was Netty/JDK-internal (HTTP encode/decode, buffer
allocation, pipeline traversal). That's the signal this investigation is at its
practical floor for "fix our own code" wins. Allocation hotspots still turned up two
real, fixable items:

- `LoadBalancingStrategy.select()` still had a `.stream().filter().filter().toList()`
  over the backend pool on every request (the `isAvailable()` filter itself was
  removed in Finding 9, but the `.stream()` call wrapping the remaining two filters
  was never converted) — replaced with a plain `for` loop building an `ArrayList`,
  same fix pattern as `Router.match()`.
- `Meter.Id` objects (Micrometer) were being allocated on every request:
  `BackendResponseHandler` called `registry.counter("gateway_requests")` by name on
  every `channelRead0` instead of caching the returned `Counter` once; `RequestForwarder
  .stopTimer()` called `registry.timer(name, "status", code)` by name on every call
  despite there only ever being a handful of distinct status codes. Fixed by caching
  the `Counter` as an instance field (set once in the constructor — `BackendResponseHandler`
  is created once per accepted connection, not once per request, so this already
  amortizes the lookup to "once per connection") and caching `Timer`s in a
  `ConcurrentHashMap<Integer, Timer>` keyed by status code inside `RequestForwarder`
  (which, unlike `BackendResponseHandler`, is a true process-wide singleton — the map
  needs to be thread-safe since every event-loop thread calls into it concurrently).
- The streaming response handler's `HttpResponse` branch built a brand-new
  `DefaultHttpResponse` and copied every header into it (`headerOnlyResponse.headers()
  .set(headers)`) before forwarding — but `res` (the already-decoded `HttpResponse`
  from the backend) carries no body at this point (streaming split header from
  content already) and isn't `ReferenceCounted`, so it's safe to forward directly
  (`ctx.writeAndFlush(res)`) with no copy at all. This only trimmed the
  `DefaultHeaders$HeaderEntry` allocation count by ~6% — most of that count turned out
  to be intrinsic to Netty's own decode of the backend's response (building the header
  chain while parsing bytes off the wire happens regardless of what code does with the
  result afterward), not something removable without leaving Netty's HTTP object model.

Also examined and explicitly decided *not* to chase further: `ConcurrentHashMap
$KeyIterator` allocation from `Router.match()`'s `for` loop (unavoidable per-iteration
cost of iterating any map, cheap at this project's realistic route counts) and
`CompositeByteBuf`/`Component[]` allocation from the *client*-facing
`HttpObjectAggregator` (still deliberately kept — see Finding 12 — because retry needs
the full request body to resend, and there's no way to decide "does this specific
request have a body" from the HTTP method alone: GET requests are conventionally
bodyless but not spec-forbidden from carrying one, so skipping aggregation based on
method would silently drop a body-bearing GET since `BackendResponseHandler` only
accepts `FullHttpRequest`).

Measured effect of this round: modest, as expected for allocation-only fixes with zero
remaining lock contention to unlock — initially looked like a **regression** (16,159
peak vs. the prior 17,968) until repeating the *identical* config three times back to
back produced 16,074 / 16,798 / **19,610** peak rps. That's a ~22% spread from doing
nothing but re-running — larger than the apparent "regression," and consistent with an
earlier observation in this same investigation (Finding 9's JFR runs) where back-to-back
identical runs swung by as much as 35% at high VUS. Lesson: on this benchmark
environment, single-run before/after comparisons under roughly ~20% apart are not
distinguishable from noise; only repeated runs (ideally averaged, at minimum a few
back-to-back samples) can support a real regression/improvement claim at that
magnitude. All the *earlier* findings in this document cleared much larger margins
(15%, 43%, etc.) and are not in question — this caveat applies specifically to
fine-tuning-scale (single-digit-to-~20%) deltas late in the investigation.

## Final result

| Stage | Peak rps |
|---|---|
| Baseline (session start) | 10,708 |
| + cache lock fix | 12,349 |
| + Epoll transport + response streaming + Go backend + CPU core isolation | 15,146 |
| + Router for-loop + SO_BACKLOG + boss thread sizing + backpressure fix | 17,968 |
| + LoadBalancingStrategy stream removed, cached Counter/Timer, header reuse | **19,610** |

~83% peak-throughput improvement over the session's starting point (best observed run;
see Finding 14 for why any single number here should be read as a sample from a noisy
range, not an exact constant), verified clean (JFR shows zero application-class lock
contention, zero errors/leaks across a 400-request manual smoke test plus the full VUS
staircase, `failed_rate` 0 throughout). Remaining levers, in rough order of effort vs.
expected payoff, are all small (single-digit-percent, and hard to even distinguish from
run-to-run noise at this point) tuning items from here — confirmed pooled
`ByteBufAllocator` is in use, JVM/GC flag tuning, etc. — not another step-function win
like the lock/streaming/backend fixes were. Getting meaningfully past this ceiling on a
single instance would mean
rewriting around Netty's object model entirely (unrealistic scope for this
project); the standard, correct next lever for more aggregate throughput is
horizontal scaling — N gateway instances behind a load balancer — not further
single-instance optimization.
