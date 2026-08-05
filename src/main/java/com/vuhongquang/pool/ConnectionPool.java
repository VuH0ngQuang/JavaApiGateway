package com.vuhongquang.pool;

import com.vuhongquang.loadbalancer.Backend;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionPool {
    private final Backend backend;
    private final EventLoopGroup group;
    private final EventLoop executor;
    private final int maxConnections;
    private final long acquireTimeoutMs;

    private Deque<Channel> idleChannels = new ArrayDeque<>();
    private Deque<Promise<Channel>> waiters = new ArrayDeque<>();
    private AtomicInteger totalConnections = new AtomicInteger(0);

    public ConnectionPool(Backend backend, EventLoopGroup group, int maxConnections, long acquireTimeoutMs) {
        this.backend = backend;
        this.group = group;
        this.maxConnections = maxConnections;
        this.acquireTimeoutMs = acquireTimeoutMs;
        executor = group.next();
    }

    public Future<Channel> acquire() {
        Promise<Channel> promise = executor.newPromise();

        executor.execute(() -> {
            Channel channel = idleChannels.pollFirst();
            while (channel != null) {
                if (channel.isActive()) {
                    promise.setSuccess(channel);
                    return ;
                }
                totalConnections.decrementAndGet();
                channel = idleChannels.pollFirst();
            }

            if (totalConnections.get() < maxConnections) {
                totalConnections.incrementAndGet();
                connectNew(promise);
            } else {
                waiters.addLast(promise);
                executor.schedule(() ->{
                    if (promise.tryFailure(new TimeoutException("Timed out waiting for a connection"))) {
                        waiters.remove(promise);
                    }
                },acquireTimeoutMs, TimeUnit.MILLISECONDS);
            }
        });

        return promise;
    }

    public void release(Channel channel) {
        executor.execute(() -> {
            if (!channel.isActive()) {
                totalConnections.decrementAndGet();
                return;
            }

            Promise<Channel> waiting = waiters.pollFirst();

            if (waiting != null) {
                waiting.setSuccess(channel);
                return;
            }

            idleChannels.addLast(channel);
        });
    }

    private void connectNew(Promise<Channel> promise) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new HttpClientCodec(),
                                new HttpObjectAggregator(64 * 1024 * 1024)
                        );
                    }
                });

        bootstrap.connect(backend.address()).addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                promise.setSuccess(future.channel());
            } else {
                totalConnections.decrementAndGet();
                promise.setFailure(future.cause());
            }
        });
    }
}
