package com.vuhongquang.loadbalancer;

import com.vuhongquang.resilience.CircuitBreaker;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class Backend {
    private final InetSocketAddress address;
    private final CircuitBreaker breaker;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private volatile boolean healthy = true;

    public Backend(InetSocketAddress address, CircuitBreaker breaker) {
        this.address = address;
        this.breaker = breaker;
    }

    public InetSocketAddress address() {return address;}
    public int activeConnections() {return activeConnections.get();}
    public void incrementConnections() {activeConnections.incrementAndGet();}
    public void decrementConnections() {activeConnections.decrementAndGet();}
    public boolean isHealthy() {return healthy;}
    public void setHealthy(boolean healthy) {this.healthy = healthy;}
    public CircuitBreaker getBreaker() {return breaker;}

    @Override
    public int hashCode() {
        return Objects.hashCode(address);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Backend)) return false;
        return Objects.equals(address, ((Backend) obj).address);
    }
}
