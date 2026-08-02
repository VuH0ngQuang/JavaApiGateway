package com.vuhongquang;

import com.vuhongquang.health.HealthChecker;
import com.vuhongquang.loadbalancer.Backend;
import com.vuhongquang.loadbalancer.BackendPool;
import com.vuhongquang.loadbalancer.LeastConnectionsStrategy;
import com.vuhongquang.routing.Router;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        EventLoopGroup boss = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        EventLoopGroup worker = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        List<Backend> movieBackends = List.of(
                new Backend(new InetSocketAddress("localhost", 8081)),
                new Backend(new InetSocketAddress("localhost", 8082))
        );

        List<Backend> todoBackends = List.of(
                new Backend(new InetSocketAddress("localhost", 9081)),
                new Backend(new InetSocketAddress("localhost", 9082)),
                new Backend(new InetSocketAddress("localhost", 9083))
        );

        final BackendPool moviePool = new BackendPool(movieBackends, new LeastConnectionsStrategy());
        final BackendPool todoPool = new BackendPool(todoBackends, new LeastConnectionsStrategy());

        ArrayList<Backend> healthList = new ArrayList<>(todoBackends);
        healthList.addAll(movieBackends);
        final HealthChecker healthChecker = new HealthChecker(healthList, worker);

        healthChecker.start();

        HashMap<String, BackendPool> routes = new HashMap<>();
        routes.put("/api/movies", moviePool);
        routes.put("/api/todos", todoPool);

        Router router = new Router(routes);

        try {
            ChannelFuture server = new ServerBootstrap()
                    .group(boss,worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new HttpServerCodec(),
                                    new HttpObjectAggregator(64*1024),
                                    new BackendResponseHandler(router)
                            );
                        }
                    })
                    .bind(1221)
                    .sync();
            server.channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}