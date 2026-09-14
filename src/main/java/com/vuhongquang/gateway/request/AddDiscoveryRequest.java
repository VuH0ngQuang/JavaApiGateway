package com.vuhongquang.gateway.request;

import java.util.Objects;

public record AddDiscoveryRequest(
        String hostname,
        String route,
        long pollIntervalMs,
        int port,
        long openDurationMs,
        double failureRateThreshold,
        int minimumCalls,
        int windowSize,
        int strategy, // 0: least_connections    1: round_robin
        boolean forceStream
) {
    public AddDiscoveryRequest {
        Objects.requireNonNull(route);
        Objects.requireNonNull(hostname);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive, got " + pollIntervalMs);
        }
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive, got " + windowSize);
        }
        if (minimumCalls <= 0 || minimumCalls > windowSize) {
            throw new IllegalArgumentException("minimumCalls must be in 1.." + windowSize + ", got " + minimumCalls);
        }
        if (failureRateThreshold <= 0 || failureRateThreshold > 1) {
            throw new IllegalArgumentException("failureRateThreshold must be in (0,1], got " + failureRateThreshold);
        }
    }
}
