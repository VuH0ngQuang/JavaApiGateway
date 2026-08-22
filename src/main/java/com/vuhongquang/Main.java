package com.vuhongquang;

import com.vuhongquang.cache.LruResponseCache;
import com.vuhongquang.cache.ResponseCache;
import com.vuhongquang.gateway.BackendGatewayService;
import com.vuhongquang.forwarding.RequestForwarder;
import com.vuhongquang.health.HealthChecker;
import com.vuhongquang.pool.ConnectionPoolManager;
import com.vuhongquang.ratelimit.tokenbucket.TokenBucketLimiter;
import com.vuhongquang.routing.Router;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws InterruptedException {

        //check Epoll is available
        boolean useEpoll = Epoll.isAvailable();
        IoHandlerFactory ioHandlerFactory = useEpoll ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();
        Class<? extends ServerSocketChannel> serverChannelClass = useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
        Class<? extends SocketChannel> clientChannelClass = useEpoll ? EpollSocketChannel.class : NioSocketChannel.class;

        final GatewayConfig config = GatewayConfig.defaults();
        final EventLoopGroup boss = new MultiThreadIoEventLoopGroup(2, ioHandlerFactory);
        final EventLoopGroup worker = new MultiThreadIoEventLoopGroup(ioHandlerFactory);
        final ResponseCache cache = new LruResponseCache(config.cacheMaxBytes(), config.cacheMaxEntries());
        final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        final HealthChecker healthChecker = new HealthChecker(new CopyOnWriteArrayList<>(), worker, clientChannelClass);
        final Router router = new Router(new ConcurrentHashMap<>());
        final ConnectionPoolManager manager = new ConnectionPoolManager(List.of(), worker, config.maxConnections(), config.acquireTimeoutMs(), registry, clientChannelClass);
        final RequestForwarder forwarder = new RequestForwarder(router, manager, cache, registry);
        final BackendGatewayService gatewayService = new BackendGatewayService(router, manager, healthChecker, registry);
        final TokenBucketLimiter limiter = new TokenBucketLimiter(
                config.rateLimitCapacity(),
                config.rateLimitWindowMs(),
                config.rateLimitIntervalMs(),
                worker
        );

        GatewayServer server = new GatewayServer(boss, worker, config.serverPort(), forwarder, gatewayService, limiter, registry, serverChannelClass);
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
