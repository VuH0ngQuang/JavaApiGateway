package com.vuhongquang;

import com.vuhongquang.health.HealthChecker;
import com.vuhongquang.loadbalancer.Backend;
import com.vuhongquang.loadbalancer.BackendPool;
import com.vuhongquang.loadbalancer.LeastConnectionsStrategy;
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
import java.util.List;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        EventLoopGroup boss = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        EventLoopGroup worker = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        List<Backend> backends = List.of(
                new Backend(new InetSocketAddress("localhost", 1500)),
                new Backend(new InetSocketAddress("localhost", 1501))
        );

        final BackendPool pool = new BackendPool(backends, new LeastConnectionsStrategy());
        final HealthChecker healthChecker = new HealthChecker(backends, worker);

        healthChecker.start();

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
                                    new BackendResponseHandler(pool)
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