package com.vuhongquang;

import com.vuhongquang.cache.LruResponseCache;
import com.vuhongquang.cache.ResponseCache;
import com.vuhongquang.gateway.BackendGatewayService;
import com.vuhongquang.forwarding.RequestForwarder;
import com.vuhongquang.health.HealthChecker;
import com.vuhongquang.loadbalancer.Backend;
import com.vuhongquang.loadbalancer.BackendPool;
import com.vuhongquang.pool.ConnectionPoolManager;
import com.vuhongquang.ratelimit.slidingwindow.SlidingWindowLimiter;
import com.vuhongquang.routing.Router;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws InterruptedException {

        final GatewayConfig config = GatewayConfig.defaults();
        final EventLoopGroup boss = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        final EventLoopGroup worker = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        final ResponseCache cache = new LruResponseCache(config.cacheMaxBytes(), config.cacheMaxEntries());
        final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        final HealthChecker healthChecker = new HealthChecker(new CopyOnWriteArrayList<>(), worker);
        final Router router = new Router(new ConcurrentHashMap<String, BackendPool>());
        final ConnectionPoolManager manager = new ConnectionPoolManager(List.<Backend>of(), worker, config.maxConnections(), config.acquireTimeoutMs());
        final RequestForwarder forwarder = new RequestForwarder(router, manager, cache, registry);
        final BackendGatewayService gatewayService = new BackendGatewayService(router, manager, healthChecker, registry);
        final SlidingWindowLimiter limiter = new SlidingWindowLimiter(
                config.rateLimitCapacity(),
                config.rateLimitWindowMs(),
                config.rateLimitIntervalMs(),
                worker
        );

        GatewayServer server = new GatewayServer(boss, worker, config.serverPort(), forwarder, gatewayService, limiter, registry);
        worker.scheduleAtFixedRate(cache::logStats, 10, 10, TimeUnit.SECONDS);
        limiter.start();
        healthChecker.start();

        try {
            server.start();
        } finally {
            server.shutdown();
        }
    }
}
