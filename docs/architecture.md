# Architecture

```
Client
   |
Netty pipeline: HttpServerCodec -> HttpObjectAggregator -> GatewayHandler -> BackendResponseHandler
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
                              |                     | filters to healthy + circuit-available
                              v
                            ConnectionPoolManager --> ConnectionPool (one per Backend)
                              |                        borrow an idle keep-alive channel,
                              |                        or open one, or queue until timeout
                              +---> Backend 1  <-----+---- CircuitBreaker (per backend)
                              +---> Backend 2  <-----+---- HealthChecker (TCP probe every 5s)
                              +---> ...
```

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
  `select(excluded)` filters to healthy + circuit-available backends, excludes anything
  already tried this exchange, and delegates the pick to the strategy.
- **`LoadBalancingStrategy`** (abstract, Template Method) — `select()` handles the shared
  guard logic (empty pool, no eligible backend, circuit breaker's `allowRequest()`) and
  the connection-count bookkeeping (`incrementConnections()` happens here, exactly once,
  only after a pick has cleared `allowRequest()` — not inside `doSelect()`, which stays a
  pure comparison with no side effects). Concrete strategies only implement `doSelect()`.
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
  longest-prefix matching for proxied traffic; `getExact(uri)` requires an exact string
  match, used by the admin API where a client-supplied `route` has to name a real,
  already-known prefix rather than fuzzily match one. `register(uri, pool)` adds a new
  route — called by `BackendGatewayService` when `POST /gateway/backends` targets an
  unknown route.

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
    full bucket would reset its banked allowance for no reason).
  - **`slidingwindow.SlidingWindowLimiter`** / **`Window`** — per-key timestamp deque;
    the sweep evicts windows that are **idle** (no timestamps left after expiring old
    ones).
  - Both limiters key by client IP, keyed off a `ConcurrentHashMap`, with their own
    periodic sweep scheduled on the shared `EventLoopGroup` — no dedicated cleanup
    thread.

## `resilience` package

- **`CircuitBreaker`** — rate-based (not consecutive-failure-count), tracked over a
  fixed-size ring buffer (`windowSize`), only evaluated once `minimumCalls` outcomes
  have been recorded. States: `CLOSED` → `OPEN` (threshold breached) → `HALF_OPEN` (one
  probe allowed after `openDurationMs`) → back to `CLOSED` on a successful probe, or
  `OPEN` again on a failed one. `allowRequest()` is the only method that mutates state;
  `isAvailable()` is a read-only check used by `LoadBalancingStrategy`'s eligibility
  filter, which runs *before* a backend is chosen (mutating `allowRequest()` runs after,
  exactly once, on the backend actually picked). Config fields are `final` — reconfiguring
  a breaker means constructing a new instance and swapping it in via `Backend.setBreaker`,
  not mutating the running one (see [`api.md`](api.md#patch-gatewaybackendsid) for the
  state-loss tradeoff that implies).

## `health` package

- **`HealthChecker`** — every 5 seconds, attempts a bare TCP connection to each backend
  (no HTTP path/auth, to stay agnostic of each backend's own health-endpoint quirks).
  Backs onto a `CopyOnWriteArrayList<Backend>` for the same reason `BackendPool` and
  `Router` do — backends are added/removed at runtime while the sweep concurrently reads
  the list. Logs only on state *transitions*, not every check, to avoid log spam.
