package com.vuhongquang.pool;

import com.vuhongquang.loadbalancer.Backend;
import io.netty.channel.EventLoopGroup;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConnectionPoolManager {

    private final Map<Backend, ConnectionPool> pools = new HashMap<>();

    public ConnectionPoolManager(List<Backend> backends, EventLoopGroup group, int maxConnections, long acquireTimeoutMs) {

        for (Backend be : backends) {
            pools.computeIfAbsent(be, key -> new ConnectionPool(key, group, maxConnections, acquireTimeoutMs));
        }
    }

    public ConnectionPool poolFor (Backend backend) {
        return pools.get(backend);
    }
}
