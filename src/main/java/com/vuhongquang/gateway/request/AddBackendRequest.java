package com.vuhongquang.gateway.request;

import java.util.Objects;

public record AddBackendRequest(
        String route,
        String host,
        int port,
        long openDurationMs,
        double failureRateThreshold,
        int minimumCalls,
        int windowSize,
        int strategy // 0: least_connections    1: round_robin
) {
    public AddBackendRequest {
        Objects.requireNonNull(route);
        Objects.requireNonNull(host);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive, got " + windowSize);
        }
        if (minimumCalls <= 0 || minimumCalls > windowSize) {
            throw new IllegalArgumentException(
                    "minimumCalls must be in 1.." + windowSize + ", got " + minimumCalls);
        }
        if (failureRateThreshold <= 0 || failureRateThreshold > 1) {
            throw new IllegalArgumentException(
                    "failureRateThreshold must be in (0,1], got " + failureRateThreshold);
        }
    }
}