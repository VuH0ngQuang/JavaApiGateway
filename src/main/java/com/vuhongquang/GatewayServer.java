package com.vuhongquang;

import com.vuhongquang.forwarding.RequestForwarder;
import com.vuhongquang.gateway.BackendGatewayService;
import com.vuhongquang.gateway.GatewayHandler;
import com.vuhongquang.ratelimit.RateLimitHandler;
import com.vuhongquang.ratelimit.RateLimiter;
import com.vuhongquang.routing.Router;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GatewayServer {
    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private final EventLoopGroup boss;
    private final EventLoopGroup worker;
    private final int port;
    private final RequestForwarder forwarder;
    private final BackendGatewayService gatewayService;
    private final RateLimiter limiter;
    private final PrometheusMeterRegistry registry;
    private final Class<? extends ServerSocketChannel> channelClass;
    private final SslContext sslContext;
    private final GatewayConfig config;
    private final Router router;

    public GatewayServer(EventLoopGroup boss,
                         EventLoopGroup worker,
                         int port,
                         RequestForwarder forwarder,
                         BackendGatewayService gatewayService,
                         RateLimiter limiter,
                         PrometheusMeterRegistry registry,
                         Class<? extends ServerSocketChannel> channelClass,
                         SslContext sslContext,
                         GatewayConfig config,
                         Router router
    ) {
        this.boss = boss;
        this.worker = worker;
        this.port = port;
        this.forwarder = forwarder;
        this.gatewayService = gatewayService;
        this.limiter = limiter;
        this.registry = registry;
        this.channelClass = channelClass;
        this.sslContext = sslContext;
        this.config = config;
        this.router = router;
    }

    public void start() throws InterruptedException {
        ChannelFuture server = new ServerBootstrap()
                .group(boss,worker)
                .channel(channelClass)
                .option(ChannelOption.SO_BACKLOG, 2048)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (sslContext != null) {
                            ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                            ch.pipeline().addLast(new Http2OrHttpHandler(forwarder, gatewayService, limiter, registry, config, router));
                        } else {
                            ch.pipeline().addLast(
                                    new HttpServerCodec(),
                                    new RateLimitHandler(limiter, registry),
                                    new HybridRequestAggregator(router, forwarder, config),
                                    new GatewayHandler(gatewayService),
                                    new BackendResponseHandler(forwarder, registry)
                            );
                        }
                    }
                })
                .bind(port)
                .sync();
        log.info("Gateway started on port {} (TLS: {}, transport: {})",
                port, sslContext != null ? "on" : "off", channelClass.getSimpleName());
        server.channel().closeFuture().sync();
    }

    public void shutdown() {
        boss.shutdownGracefully();
        worker.shutdownGracefully();
    }
}
