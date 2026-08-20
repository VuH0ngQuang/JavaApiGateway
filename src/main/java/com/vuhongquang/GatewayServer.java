package com.vuhongquang;

import com.vuhongquang.forwarding.RequestForwarder;
import com.vuhongquang.gateway.BackendGatewayService;
import com.vuhongquang.gateway.GatewayHandler;
import com.vuhongquang.ratelimit.RateLimiter;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class GatewayServer {
    private final EventLoopGroup boss;
    private final EventLoopGroup worker;
    private final int port;
    private final RequestForwarder forwarder;
    private final BackendGatewayService gatewayService;
    private final RateLimiter limiter;
    private final PrometheusMeterRegistry registry;

    public GatewayServer(EventLoopGroup boss, EventLoopGroup worker, int port, RequestForwarder forwarder, BackendGatewayService gatewayService, RateLimiter limiter, PrometheusMeterRegistry registry) {
        this.boss = boss;
        this.worker = worker;
        this.port = port;
        this.forwarder = forwarder;
        this.gatewayService = gatewayService;
        this.limiter = limiter;
        this.registry = registry;
    }

    public void start() throws InterruptedException {
        ChannelFuture server = new ServerBootstrap()
                .group(boss,worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(64 * 1024 * 1024),
                                new GatewayHandler(gatewayService),
                                new BackendResponseHandler(forwarder, limiter, registry)
                        );
                    }
                })
                .bind(port)
                .sync();
        server.channel().closeFuture().sync();
    }

    public void shutdown() {
        boss.shutdownGracefully();
        worker.shutdownGracefully();
    }
}
