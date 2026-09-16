package com.vuhongquang.gateway;

import com.vuhongquang.GatewayConfig;
import com.vuhongquang.gateway.request.AddBackendRequest;
import com.vuhongquang.loadbalancer.Backend;
import com.vuhongquang.loadbalancer.BackendPool;
import com.vuhongquang.loadbalancer.LoadBalancingStrategy;
import com.vuhongquang.loadbalancer.StrategyType;
import com.vuhongquang.resilience.CircuitBreaker;
import com.vuhongquang.routing.Router;
import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class BackendStatePersister {
    private static final Logger log = LoggerFactory.getLogger(BackendStatePersister.class);

    private final Router router;
    private final GatewayStateStore<AddBackendRequest> stateStore;
    private final BackendRegistry beRegistry;
    private final GatewayConfig config;
    private final Counter persistFailureCounter;

    public BackendStatePersister(Router router,
                                 GatewayStateStore<AddBackendRequest> stateStore,
                                 BackendRegistry beRegistry,
                                 GatewayConfig config,
                                 PrometheusMeterRegistry registry) {
        this.router = router;
        this.stateStore = stateStore;
        this.beRegistry = beRegistry;
        this.config = config;
        persistFailureCounter = registry.counter("gateway_backend_persist_failures_total");
    }

    //save the current snapshot of the gateway
    public void save() {
        var maxRetries = config.maxRetryPersister();
        ArrayList<AddBackendRequest> copyBackends;
        try {
            copyBackends = buildSnapshot();
        } catch (Exception e) {
            persistFailureCounter.increment();
            log.error("Failed to build gateway state snapshot for backends: {}", e.toString());
            return;
        }
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                stateStore.save(copyBackends);
                return;
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    persistFailureCounter.increment();
                    log.error("Failed to persist gateway state for backends after {} attempts: {}", maxRetries, e.toString());
                } else {
                    log.warn("Persist for backends attempt {} failed, retrying: {}", attempt, e.toString());
                }
            }
        }
    }

    public void restore() {
        List<AddBackendRequest> snapshot;
        try {
            snapshot = stateStore.load();
        } catch (IOException e) {
            log.error("Failed to load gateway state for backends, starting with no backends: {}", e.toString());
            return;
        }
        for (AddBackendRequest beReq : snapshot) {
            try {
                beRegistry.registerBackend(beReq);
            } catch (Exception e) {
                log.error("Failed to restore backend for route {}: {}", beReq.route(), e.toString());
            }
        }
        log.info("Restored {} backend(s) from snapshot", snapshot.size());
    }

    private ArrayList<AddBackendRequest> buildSnapshot() {
        ArrayList<AddBackendRequest> copyBackends = new ArrayList<>();
        for (Map.Entry<String, BackendPool> poolEntry : router.routes().entrySet()) {
            String route = poolEntry.getKey();
            BackendPool pool = poolEntry.getValue();
            List<Backend> backends = pool.backends();
            for (Backend be : backends) {
                CircuitBreaker breaker = be.getBreaker();
                LoadBalancingStrategy strategy = pool.strategy();
                Optional<StrategyType> strategyId = StrategyType.fromStrategy(strategy);
                if (strategyId.isEmpty()) {
                    throw new IllegalStateException("Route " + route + " uses a LoadBalancingStrategy not registered in StrategyType: " + strategy.getClass());
                }
                AddBackendRequest request = new AddBackendRequest(route,
                        be.address().getHostName(),
                        be.address().getPort(),
                        breaker.openDurationMs(),
                        breaker.failureRateThreshold(),
                        breaker.minimumCalls(),
                        breaker.windowSize(),
                        strategyId.get().getId(),
                        pool.isForceStream()
                );
                copyBackends.add(request);
            }
        }
        return copyBackends;
    }
}
