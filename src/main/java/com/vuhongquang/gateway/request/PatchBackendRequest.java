package com.vuhongquang.gateway.request;

import java.util.Objects;

public record PatchBackendRequest(
        String route,
        Long openDurationMs,
        Double failureRateThreshold,
        Integer minimumCalls,
        Integer windowSize
) {
    public PatchBackendRequest {
        Objects.requireNonNull(route);
    }
}
