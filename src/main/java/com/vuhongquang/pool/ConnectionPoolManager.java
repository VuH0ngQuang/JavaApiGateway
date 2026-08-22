package com.vuhongquang.pool;

import com.vuhongquang.loadbalancer.Backend;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionPoolManager {

    private final ConcurrentHashMap<Backend, ConnectionPool> pools = new ConcurrentHashMap<>();
    private final EventLoopGroup group;
    private final int maxConnections;
    private final long acquireTimeoutMs;
    private final MeterRegistry registry;
    private final Class<? extends SocketChannel> channelClass;

    public ConnectionPoolManager(List<Backend> backends,
                                 EventLoopGroup group,
                                 int maxConnections,
                                 long acquireTimeoutMs,
                                 MeterRegistry registry,
                                 Class<? extends SocketChannel> channelClass) {
        this.group = group;
        this.maxConnections = maxConnections;
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.registry = registry;
        this.channelClass = channelClass;

        for (Backend be : backends) {
            pools.computeIfAbsent(be, key -> new ConnectionPool(key, group, maxConnections, acquireTimeoutMs, registry, channelClass));
        }
    }

    public ConnectionPool poolFor (Backend backend) {
        return pools.get(backend);
    }

    public void addBackend(Backend backend) {
        pools.computeIfAbsent(backend, key -> new ConnectionPool(key, group, maxConnections, acquireTimeoutMs, registry, channelClass));
    }

    public void deleteBackend(Backend backend) {
        pools.remove(backend);
    }
}
