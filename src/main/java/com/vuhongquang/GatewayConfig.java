package com.vuhongquang;

public record GatewayConfig(
        int maxConnections,
        long acquireTimeoutMs,
        int serverPort,
        long cacheMaxBytes,
        int cacheMaxEntries,
        int rateLimitCapacity,
        long rateLimitWindowMs,
        long rateLimitIntervalMs
) {
    public static GatewayConfig defaults() {
        return new GatewayConfig(
                2048,
                30000,
                1221,
                128 * 1024L * 1024L,
                5000,
                120,
                60_000,
                60_000
        );
    }
}
