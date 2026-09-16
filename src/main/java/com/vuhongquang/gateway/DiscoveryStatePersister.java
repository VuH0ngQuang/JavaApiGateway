package com.vuhongquang.gateway;

import com.vuhongquang.GatewayConfig;
import com.vuhongquang.gateway.request.AddDiscoveryRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

public class DiscoveryStatePersister {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryStatePersister.class);

    private final DiscoveryRegistry discoveryRegistry;
    private final GatewayStateStore<AddDiscoveryRequest> stateStore;
    private final GatewayConfig config;
    private final Counter persistFailureCounter;

    public DiscoveryStatePersister(
            DiscoveryRegistry discoveryRegistry,
            GatewayStateStore<AddDiscoveryRequest> stateStore,
            GatewayConfig config,
            PrometheusMeterRegistry registry
    ) {
        this.discoveryRegistry = discoveryRegistry;
        this.stateStore = stateStore;
        this.config = config;
        persistFailureCounter = registry.counter("gateway_discovery_persist_failures_total");
    }

    public void save() {
        var maxRetries = config.maxRetryPersister();
        Collection<AddDiscoveryRequest> copyDiscoveries;
        try {
            copyDiscoveries = discoveryRegistry.listConfigs();
        } catch (Exception e) {
            persistFailureCounter.increment();
            log.error("Failed to build gateway state snapshot for discoveries: {}", e.toString());
            return;
        }
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                stateStore.save(copyDiscoveries);
                return;
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    persistFailureCounter.increment();
                    log.error("Failed to persist gateway state for discoveries after {} attempts: {}", maxRetries, e.toString());
                } else {
                    log.warn("Persist for discoveries attempt {} failed, retrying: {}", attempt, e.toString());
                }
            }
        }
    }

    public void restore() {
        List<AddDiscoveryRequest> snapshot;
        try {
            snapshot = stateStore.load();
        } catch (IOException e) {
            log.error("Failed to load gateway state for discoveries, starting with no discoveries: {}", e.toString());
            return;
        }
        for (AddDiscoveryRequest discoveryReq : snapshot) {
            try {
                discoveryRegistry.startDiscovery(discoveryReq);
            } catch (Exception e) {
                log.error("Failed to restore discovery for route {}: {}", discoveryReq.route(), e.toString());
            }
        }
        log.info("Restored {} discovery(ies) from snapshot", snapshot.size());
    }
}
