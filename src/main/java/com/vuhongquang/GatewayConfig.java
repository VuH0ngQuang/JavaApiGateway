package com.vuhongquang;

public record GatewayConfig(
        int maxConnections,
        long acquireTimeoutMs,
        int serverPort,
        long cacheMaxBytes,
        int cacheMaxEntries,
        int rateLimitCapacity,
        long rateLimitWindowMs,
        long rateLimitIntervalMs,
        String stateDir,
        int stateRetentionCount,
        String tlsCertPath,
        String tlsKeyPath,
        int maxContentLength,
        int maxRetryPersister,
        String backendStatePrefix,
        String discoveryStatePrefix,
        String cachePolicy,
        int virtualNode
) {
    public static GatewayConfig defaults() {
        return new GatewayConfig(
                2048,
                30000,
                1221,
                0L, //cache disabled for raw-proxy-path rps test
                5000,
                100_000_000,
                60_000,
                60_000,
                "snapshot",
                20,
                "",
                "",
                64 * 1024 * 1024,
                3,
                "gateway-backends",
                "gateway-discovery",
                "LRU",
                150
        );
    }
}
