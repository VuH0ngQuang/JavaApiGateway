package com.vuhongquang;

import com.vuhongquang.cache.LruResponseCache;
import com.vuhongquang.cache.ResponseCache;
import com.vuhongquang.gateway.BackendGatewayService;
import com.vuhongquang.forwarding.RequestForwarder;
import com.vuhongquang.gateway.GatewayStateStore;
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
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws InterruptedException, IOException {

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
        final GatewayStateStore stateStore = new GatewayStateStore(config.stateDir(),config.stateRetentionCount());
        final BackendGatewayService gatewayService = new BackendGatewayService(router, manager, healthChecker, registry, stateStore);
        final TokenBucketLimiter limiter = new TokenBucketLimiter(
                config.rateLimitCapacity(),
                config.rateLimitWindowMs(),
                config.rateLimitIntervalMs(),
                worker
        );

        //check ssl
        SslContext sslContext = null;
        if (config.tlsCertPath() != null && config.tlsKeyPath() != null && !config.tlsCertPath().isBlank() && !config.tlsKeyPath().isBlank()) {
            sslContext = SslContextBuilder.forServer(
                    new File(config.tlsCertPath()),
                    new File(config.tlsKeyPath())
            ).applicationProtocolConfig(
                    new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2,
                            ApplicationProtocolNames.HTTP_1_1
            )).build();
        }

        GatewayServer server = new GatewayServer(
                boss,
                worker,
                config.serverPort(),
                forwarder,
                gatewayService,
                limiter,
                registry,
                serverChannelClass,
                sslContext,
                config,
                router
        );
        worker.scheduleAtFixedRate(cache::logStats, 10, 10, TimeUnit.SECONDS);
        limiter.start();
        healthChecker.start();
        gatewayService.restoreBackend();

        try {
            server.start();
        } finally {
            server.shutdown();
        }
    }
}
