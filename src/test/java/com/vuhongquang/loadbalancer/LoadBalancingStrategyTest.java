package com.vuhongquang.loadbalancer;

import com.vuhongquang.resilience.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LoadBalancingStrategyTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private Backend newBackend(String host, int port) {
        return new Backend(
                new InetSocketAddress(host, port),
                new CircuitBreaker(5000, 0.5, 10, 20),
                registry
        );
    }

    @Test
    void select_excludesUnhealthyAndExcludedBackends() {
        Backend healthy = newBackend("localhost", 8081);
        Backend unhealthy = newBackend("localhost", 8082);
        unhealthy.setHealthy(false);
        Backend excluded = newBackend("localhost", 8083);
        RoundRobinStrategy strategy = new RoundRobinStrategy();

        Backend result = strategy.select(
                List.of(healthy, unhealthy, excluded),
                Set.of(excluded)
        );

        assertSame(healthy, result);
    }

    @Test
    void select_returnsNullWhenPoolEmpty() {
        RoundRobinStrategy strategy = new RoundRobinStrategy();
        Backend result = strategy.select(List.of(), Set.of());

        assertNull(result);
    }

    @Test
    void select_incrementsConnectionsOnSuccessfulPick() {
        Backend backend = newBackend("localhost", 8081);
        RoundRobinStrategy strategy = new RoundRobinStrategy();

        assertEquals(0, backend.activeConnections());
        strategy.select(List.of(backend), Set.of());
        assertEquals(1, backend.activeConnections());
    }
}
