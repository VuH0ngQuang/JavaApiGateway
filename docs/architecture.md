# Architecture

```
Client
   |
Netty pipeline: HttpServerCodec -> HttpObjectAggregator -> GatewayHandler -> BackendResponseHandler
   |            (request side only -- responses stream back, see RequestForwarder below)
   |
   +-- /gateway/*  --> GatewayHandler --> BackendGatewayService (admin API, see api.md)
   |                                       - never touches routing/proxy state directly
   |
   +-- everything else --> BackendResponseHandler
                              |
                              +--> ResponseCache --- hit? write cached body, done (no backend involved)
                              |
                              v miss
                            RequestForwarder
                              |
                            Router ---- longest-prefix match on URI (/api/movies, /api/todos, ...)
                              |
                            BackendPool --- LoadBalancingStrategy (Round Robin / Least Connections)
                              |                     ^
                              |                     | filters to healthy backends
                              v                     | (circuit-availability now checked once,
                            ConnectionPoolManager    |  post-selection -- see resilience below)
                              |    --> ConnectionPool (one per Backend, no HttpObjectAggregator --
                              |         borrow an idle keep-alive channel,      response streams
                              |         or open one, or queue until timeout)    through in chunks
                              +---> Backend 1  <-----+---- CircuitBreaker (per backend)
                              +---> Backend 2  <-----+---- HealthChecker (TCP probe every 5s)
                              +---> ...
```

`GatewayServer` picks Netty's native Epoll transport on Linux (`Epoll.isAvailable()`,
falling back to NIO elsewhere) — the `boss`/`worker` `EventLoopGroup`s, the server and
client `SocketChannel` classes, and every `Bootstrap`/`ServerBootstrap` in the codebase
(`GatewayServer`, `ConnectionPool`, `HealthChecker`) all take the resolved
`IoHandlerFactory`/channel classes from `Main` rather than hardcoding NIO — this was a
Week 11 change; see [`performance.md`](performance.md#week-11-performance-optimization)
for the throughput difference it made, especially at low-to-moderate concurrency.

Every `Backend`, `BackendPool`, `Router` entry, and `ConnectionPool` in a running gateway
was created at runtime through the [admin API](api.md) — `Main` boots with empty
collections and zero pre-declared routes. `GatewayConfig` holds the handful of startup
constants (port, connection-pool limits, cache size, rate-limit rate); `GatewayServer`
owns the Netty `ServerBootstrap`/`EventLoopGroup` lifecycle (`start()`/`shutdown()`), so
`Main.main()` is just dependency wiring.

## Root package (`com.vuhongquang`)

- **`Main`** — builds the shared singletons (`ResponseCache`, `PrometheusMeterRegistry`,
  `RateLimiter`, `Router`, `ConnectionPoolManager`, `HealthChecker`, `RequestForwarder`,
  `BackendGatewayService`) with empty backend/route state, then hands them to
  `GatewayServer`.
- **`GatewayConfig`** — a record of the startup constants that used to be hardcoded
  inline: `maxConnections`, `acquireTimeoutMs`, `serverPort`, cache size/entry limits,
  rate-limit capacity/window/interval. `GatewayConfig.defaults()` is the only place
  those numbers live.
- **`GatewayServer`** — owns `ServerBootstrap`, the boss/worker `EventLoopGroup`s, and
  the pipeline (`HttpServerCodec` → `HttpObjectAggregator` → `GatewayHandler` →
  `BackendResponseHandler`). `start()` binds and blocks on the close future; `shutdown()`
  gracefully stops both event loop groups.
- **`BackendResponseHandler`** — the client-facing entry point. Per request: starts a
  `Timer.Sample`, increments `gateway_requests_total`, runs the rate-limit check, then
  delegates the actual proxy work to `RequestForwarder`. Deliberately thin — every other
  responsibility it used to hold (caching, retry, backend selection) has been extracted
  out to keep this class to "Netty glue plus the rate-limit gate."

## `forwarding` package

- **`RequestForwarder`** — everything `BackendResponseHandler` used to do beyond the
  rate-limit gate: cache lookup, backend selection, retry, and the actual proxy exchange.
  Extracted specifically because that logic and the Netty-handler shell around it have
  different reasons to change.
  - **Retry** — up to 3 attempts, tracked via a `LinkedHashSet<Backend> triedBackend`
    passed through the recursive `attemptRequest` calls. Each attempt excludes every
    backend already tried this exchange, via a new `Router`/`BackendPool` overload of
    `select(...)` that takes an exclusion set. When every backend in the pool has been
    excluded and attempts remain, the *oldest* exclusion is evicted (not the whole set)
    so the next attempt retries the backend most likely to have recovered, rather than
    the one that just failed a moment ago.
  - **Timer bookkeeping** — see [`observability.md`](observability.md) for why the
    `Timer.Sample` has to be stopped exactly once across 8 different exit paths.
  - **`msg` reference-counting** — `msg.retain()` once in `forward()`, released exactly
    once per exchange (`sendError` on every terminal error path, explicitly in the
    success branch). Getting this wrong across retries produced both a leak (extra
    `retain()` per write attempt that was never balanced) and a crash
    (`IllegalReferenceCountException` from retrying against an already-zero refcount) at
    different points during development — the current design retains once for the whole
    multi-attempt exchange, not once per attempt.
  - **Response streaming (Week 11)** — the per-exchange backend response handler is a
    plain `ChannelInboundHandlerAdapter` (not `SimpleChannelInboundHandler`, which would
    auto-release messages before the streaming code gets a chance to forward them). It
    reacts to three message types individually as they arrive from the (now
    un-aggregated) backend connection: an `HttpResponse` triggers a header-only reply to
    the client immediately, each `HttpContent` is forwarded as it arrives (and copied
    into a cache-accumulator buffer only if this specific response is GET+200+cacheable
    — otherwise zero extra copies), and `LastHttpContent` finalizes the cache write and
    the exchange. Request bodies are still fully aggregated (`HttpObjectAggregator`
    stays on the client-facing pipeline) — deliberately not streamed, because retrying a
    failed attempt against a different backend resends the same request content, which
    a consumed/streamed body can't support.
  - **Backpressure** — a `channelWritabilityChanged` listener is added to the
    *client*-facing pipeline once headers arrive, toggling `setAutoRead` on the
    *backend* channel: if the client can't drain fast enough, stop reading more from the
    backend rather than letting Netty's outbound write buffer grow unbounded (which can
    OOM a fast backend serving a slow client). Removed on every exchange-completion path
    to avoid a duplicate-handler-name crash on the connection's next request; the add
    itself removes any stale handler first as a second line of defense, since a
    left-over handler's closure would otherwise reference the *previous* exchange's
    backend channel.
  - **Committed-response semantics** — `channelInactive`/`exceptionCaught` on the
    backend connection now branch on whether headers were already sent to the client
    (`status == null` → a clean `sendError()` is still possible; otherwise → `ctx.close()`,
    since a proxy that already streamed part of a response can't rewrite it, matching
    real reverse-proxy behavior). Both are also guarded by `if (done.get()) return;` at
    the top — a backend connection closing normally *after* the exchange already
    finished successfully (e.g. an idle pooled connection timing out later) must not be
    treated as a new failure.

## `gateway` package

- **`GatewayHandler`** — intercepts `/gateway/*` before it can reach the proxy path.
  Does only path dispatch: `/gateway/metrics` (exact) and anything starting with
  `/gateway/backends` (delegates the method dispatch to `BackendGatewayService.handler`).
  No `Router`/`ConnectionPoolManager`/`HealthChecker` dependency — those live in
  `BackendGatewayService`, which this class only holds a reference to.
- **`BackendGatewayService`** — the admin API's actual logic. See [`api.md`](api.md) for
  the endpoint contracts.
- **`gateway.request` package** — `AddBackendRequest`, `PatchBackendRequest`,
  `DeleteBackendRequest`: Jackson-deserialized records for each endpoint's body.
  `AddBackendRequest` validates in its compact constructor (matches
  `CircuitBreaker`'s own constructor constraints, so a bad request fails before a
  `CircuitBreaker` is even attempted). `PatchBackendRequest`'s optional fields are boxed
  (`Long`, `Double`, `Integer`, not primitives) specifically so `null` can mean "field
  omitted, don't touch this," which primitives can't express.

## `loadbalancer` package

- **`BackendPool`** — holds a `CopyOnWriteArrayList<Backend>` (mutable — backends are
  added/removed at runtime via the admin API, while every in-flight request is
  concurrently reading the list to select from it) and a `LoadBalancingStrategy`.
  `select(excluded)` filters to healthy backends, excludes anything already tried this
  exchange, and delegates the pick to the strategy — circuit-breaker eligibility is
  checked once, after selection (see `LoadBalancingStrategy`), not as a pool-wide
  pre-filter.
- **`LoadBalancingStrategy`** (abstract, Template Method) — `select()` handles the shared
  guard logic (empty pool, no eligible backend) and the connection-count bookkeeping
  (`incrementConnections()` happens here, exactly once, only after a pick has cleared
  `allowRequest()` — not inside `doSelect()`, which stays a pure comparison with no side
  effects). Concrete strategies only implement `doSelect()`. Until Week 11, `select()`
  also pre-filtered every backend in the pool through `getBreaker().isAvailable()`
  before ever calling `doSelect()` — removed as a JFR-measured lock-contention source
  (every request touched every backend's `CircuitBreaker`, not just the one it ended up
  using); the post-selection `allowRequest()` check plus the existing retry/exclusion
  mechanism already reject and route around an open-circuit pick without it.
  - **`RoundRobinStrategy`** — cycles via an `AtomicInteger` index.
  - **`LeastConnectionsStrategy`** — starts its scan from a rotating index (same
    `AtomicInteger` pattern as round robin) rather than always `backends.get(0)`, so ties
    (which are common — most backends sit at 0 active connections most of the time)
    don't always resolve toward the same backend and starve the rest of the pool.
- **`Backend`** — wraps an address, a mutable `CircuitBreaker` (swappable via
  `setBreaker`, for `PATCH`), an `AtomicInteger` connection counter, and a `volatile
  healthy` flag. Self-registers `gateway_backend_active_connections` and
  `gateway_backend_healthy` gauges in its constructor. `equals`/`hashCode` on address, so
  it works as a map key (`ConnectionPoolManager`) and a set member (retry's exclusion
  set).

## `routing` package

- **`Router`** — a `ConcurrentHashMap<String, BackendPool>` (concurrent because routes
  can be registered at runtime while every request reads the map). `match(uri)` does
  longest-prefix matching for proxied traffic with a plain `for` loop over the
  registered keys (changed from a `.stream().filter().max(...)` in Week 11 — same O(n)
  complexity either way, just without the per-request Stream/lambda/boxed-`Integer`
  allocation; a trie would change the complexity class but isn't worth the added
  mutable-tree concurrency story at this project's realistic route counts, roughly a
  hundred). `getExact(uri)` requires an exact string match, used by the admin API where
  a client-supplied `route` has to name a real, already-known prefix rather than
  fuzzily match one. `register(uri, pool)` adds a new route — called by
  `BackendGatewayService` when `POST /gateway/backends` targets an unknown route.

## `pool` package

- **`ConnectionPool`** — one per backend. `acquire()` reuses a live idle channel,
  discards dead ones, opens a new connection while under `maxConnections`, or queues the
  caller and times it out after `acquireTimeoutMs`. `release()` hands the channel
  straight to a waiting caller if there is one, otherwise parks it idle. All state is
  confined to a single `EventLoop` chosen in the constructor — both methods run inside
  `executor.execute(...)`, which removes the need for locks (the same approach Netty's
  own `FixedChannelPool` takes).
- **`ConnectionPoolManager`** — a `ConcurrentHashMap<Backend, ConnectionPool>` (same
  concurrent-mutation reasoning as `Router`). `poolFor(backend)` looks one up;
  `addBackend`/`deleteBackend` create or drop the entry for the admin API.

## `cache` package

- **`ResponseCache`** (interface) — the seam is the whole cache, not a pluggable
  eviction-policy hook, because an O(1) eviction policy is inseparable from the data
  structure backing it (LRU needs access-ordering; LFU needs frequency buckets). A
  shared "pick a victim" method over one map could only be implemented by scanning.
- **`LruResponseCache`** — an access-ordered `LinkedHashMap` with `removeEldestEntry`
  doing O(1) eviction. Bounded by **bytes**, not entry count. Expiry is lazy (checked on
  `get()`), since LRU pressure evicts untouched stale entries anyway.
- **`CachedResponse`** — an immutable record holding a defensive `byte[]` copy of the
  body, since a `FullHttpResponse`'s buffer is freed once written to the client.

## `ratelimit` package

- **`RateLimiter`** (interface) — `Future<Boolean> tryAcquire(String key)`, so a future
  backend (Redis) can implement the same contract asynchronously without the call site
  changing.
  - **`tokenbucket.TokenBucketLimiter`** / **`Bucket`** — per-key token bucket; a sweep
    task evicts buckets that are back to **full** (not empty — evicting an idle-but-not-
    full bucket would reset its banked allowance for no reason). `Bucket` is lock-free
    (Week 11): an `AtomicReference<State>` holding `(token, lastRefillTime)`, refilled
    lazily on each `tryConsume()` via a CAS loop, not a monitor. It started out
    `synchronized` — since every request from the same client IP shares one `Bucket`,
    that lock alone accounted for a meaningful share of a JFR-measured throughput
    ceiling (see [`performance.md`](performance.md#week-11-performance-optimization)).
  - **`slidingwindow.SlidingWindowLimiter`** / **`Window`** — per-key timestamp deque;
    the sweep evicts windows that are **idle** (no timestamps left after expiring old
    ones). `Main` currently wires `TokenBucketLimiter` as the active `RateLimiter`;
    `Window` is still `synchronized` internally and was the original Week 11 hot-lock
    finding, superseded by switching the active limiter rather than rewriting `Window`
    itself lock-free (a lock-free "sliding window counter" design — bucketed counts
    interpolated across adjacent fixed windows — was drafted but not carried through).
  - Both limiters key by client IP, keyed off a `ConcurrentHashMap`, with their own
    periodic sweep scheduled on the shared `EventLoopGroup` — no dedicated cleanup
    thread.

## `resilience` package

- **`CircuitBreaker`** — rate-based (not consecutive-failure-count). States: `CLOSED` →
  `OPEN` (threshold breached) → `HALF_OPEN` (one probe allowed after `openDurationMs`) →
  back to `CLOSED` on a successful probe, or `OPEN` again on a failed one. `allowRequest()`
  is the only method that mutates state; `isAvailable()` is a read-only check. Config
  fields are `final` — reconfiguring a breaker means constructing a new instance and
  swapping it in via `Backend.setBreaker`, not mutating the running one (see
  [`api.md`](api.md#patch-gatewaybackendsid) for the state-loss tradeoff that implies).
  Lock-free since Week 11: state lives in an `AtomicReference<StateHolder>`
  (`state`/`openAt`/`probeInFlight` as one immutable record, swapped via
  compare-and-set), and failure/call counts are `LongAdder`s reset on a successful
  `HALF_OPEN` probe — replacing a `synchronized` fixed-size ring buffer (`windowSize`
  boolean array, only evaluated once `minimumCalls` outcomes had been recorded). The
  semantics shifted slightly: counts now run since the circuit last closed rather than
  over a strict last-N-calls window, and `windowSize` no longer bounds anything (kept in
  the constructor signature for API compatibility). This was the single largest lock
  contention source found by JFR — every request touched a `CircuitBreaker` method
  multiple times (`LoadBalancingStrategy.select()` originally called `isAvailable()` on
  every backend in the pool, not just the one selected — that pre-filter was removed
  too, since `allowRequest()` on the chosen backend plus the existing retry/exclusion
  path already covers a rejected pick). See
  [`performance.md`](performance.md#week-11-performance-optimization).

## `health` package

- **`HealthChecker`** — every 5 seconds, attempts a bare TCP connection to each backend
  (no HTTP path/auth, to stay agnostic of each backend's own health-endpoint quirks).
  Backs onto a `CopyOnWriteArrayList<Backend>` for the same reason `BackendPool` and
  `Router` do — backends are added/removed at runtime while the sweep concurrently reads
  the list. Logs only on state *transitions*, not every check, to avoid log spam.
