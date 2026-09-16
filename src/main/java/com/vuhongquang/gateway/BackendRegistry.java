package com.vuhongquang.gateway;

import com.vuhongquang.gateway.request.AddBackendRequest;
import com.vuhongquang.gateway.request.DeleteBackendRequest;
import com.vuhongquang.gateway.request.PatchBackendRequest;
import com.vuhongquang.health.HealthChecker;
import com.vuhongquang.loadbalancer.Backend;
import com.vuhongquang.loadbalancer.BackendPool;
import com.vuhongquang.loadbalancer.LoadBalancingStrategy;
import com.vuhongquang.loadbalancer.StrategyType;
import com.vuhongquang.pool.ConnectionPoolManager;
import com.vuhongquang.resilience.CircuitBreaker;
import com.vuhongquang.routing.Router;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class BackendRegistry {
    private static final Logger log = LoggerFactory.getLogger(BackendRegistry.class);

    private final Router router;
    private final ConnectionPoolManager poolManager;
    private final HealthChecker healthChecker;
    private final PrometheusMeterRegistry registry;

    public BackendRegistry(Router router, ConnectionPoolManager poolManager, HealthChecker healthChecker, PrometheusMeterRegistry registry) {
        this.router = router;
        this.poolManager = poolManager;
        this.healthChecker = healthChecker;
        this.registry = registry;
    }

    public void registerBackend(AddBackendRequest beReq) {
        if (beReq == null) {
            throw new IllegalArgumentException("registerBackend called with a null request");
        }
        String route = beReq.route();
        BackendPool backendPool = router.getExact(route);
        if (backendPool == null) {
            Optional<StrategyType> strategy = StrategyType.fromId(beReq.strategy());
            if (strategy.isEmpty()) {
                throw new IllegalArgumentException("unknown strategy id: " + beReq.strategy());
            }
            backendPool = createNewPool(route, strategy.get().create(), beReq.forceStream());
        }
        Backend be = new Backend(
                new InetSocketAddress(beReq.host(), beReq.port()),
                new CircuitBreaker(beReq.openDurationMs(),
                        beReq.failureRateThreshold(),
                        beReq.minimumCalls(),
                        beReq.windowSize()
                ),
                registry
        );
        addToPool(backendPool, be);
    }

    public void removeBackend (DeleteBackendRequest beReq, String id) {
        if (beReq == null) throw new IllegalArgumentException("removeBackend called with a null request");
        var route = beReq.route();
        BackendPool backendPool = router.getExact(route);
        if (backendPool == null) throw new NoSuchElementException("route not found: " + route);
        Optional<Backend> beOpt = backendPool.findByAddress(id);
        if (beOpt.isEmpty()) throw new NoSuchElementException("backend not found: " + id + " on route " + route);

        Backend be = beOpt.get();
        backendPool.removeBackend(be);
        poolManager.deleteBackend(be);
        healthChecker.deleteBackend(be);
        log.info("Backend {} removed from route {}", id, route);
    }

    public void patchBackend(String id, PatchBackendRequest patchReq) {
        String route = patchReq.route();
        BackendPool bePool = router.getExact(route);
        if (bePool == null) throw new NoSuchElementException("route not found: "+route);
        Optional<Backend> beOpt = bePool.findByAddress(id);
        if (beOpt.isEmpty()) throw new NoSuchElementException("backend not found: " + id + " on route " + route);
        Backend be = beOpt.get();
        CircuitBreaker oldBreaker = be.getBreaker();
        if (patchReq.minimumCalls() != null ||
                patchReq.windowSize() != null ||
                patchReq.openDurationMs() != null ||
                patchReq.failureRateThreshold() != null
        ) {
            int minimumCalls;
            int windowSize;
            long openDurationMs;
            double failureRateThreshold;
            if (patchReq.minimumCalls() != null) {
                minimumCalls = patchReq.minimumCalls();
            } else {
                minimumCalls = oldBreaker.minimumCalls();
            }
            if (patchReq.windowSize() != null) {
                windowSize = patchReq.windowSize();
            } else {
                windowSize = oldBreaker.windowSize();
            }
            if (patchReq.openDurationMs() != null) {
                openDurationMs = patchReq.openDurationMs();
            } else {
                openDurationMs = oldBreaker.openDurationMs();
            }
            if (patchReq.failureRateThreshold() != null) {
                failureRateThreshold = patchReq.failureRateThreshold();
            } else {
                failureRateThreshold = oldBreaker.failureRateThreshold();
            }
            CircuitBreaker newBreaker = new CircuitBreaker(openDurationMs, failureRateThreshold, minimumCalls, windowSize);
            be.setBreaker(newBreaker);
            log.info("Backend {} on route {} reconfigured (breaker in-flight state reset)", id, route);
        }
    }

    private BackendPool createNewPool(String route, LoadBalancingStrategy loadBalancingStrategy, boolean forceStream) {
        BackendPool pool = new BackendPool(new CopyOnWriteArrayList<>(), loadBalancingStrategy, forceStream);
        router.register(route, pool);
        return pool;
    }

    private void addToPool(BackendPool pool, Backend be) {
        poolManager.addBackend(be);
        healthChecker.addBackend(be);
        pool.addBackend(be);
    }
}
