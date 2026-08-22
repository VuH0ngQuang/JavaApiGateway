package com.vuhongquang.pool;

import com.vuhongquang.loadbalancer.Backend;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
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
    private final Class<? extends SocketChannel> channelClass;

    private final AtomicInteger queueDepth = new AtomicInteger(0);

    private final Deque<Channel> idleChannels = new ArrayDeque<>();
    private final Deque<Promise<Channel>> waiters = new ArrayDeque<>();
    private final AtomicInteger totalConnections = new AtomicInteger(0);

    public ConnectionPool(Backend backend,
                          EventLoopGroup group,
                          int maxConnections,
                          long acquireTimeoutMs,
                          MeterRegistry registry,
                          Class<? extends SocketChannel> channelClass) {
        this.backend = backend;
        this.group = group;
        this.maxConnections = maxConnections;
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.channelClass = channelClass;

        executor = group.next();

        registry.gauge(
                "gateway_pool_total_connections",
                Tags.of("address", backend.address().toString()),
                this,
                p -> p.totalConnections.get()
        );
        registry.gauge(
                "gateway_pool_queue_depth",
                Tags.of("address", backend.address().toString()),
                this,
                p -> p.queueDepth.get()
        );
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
                queueDepth.incrementAndGet();
                executor.schedule(() ->{
                    if (promise.tryFailure(new TimeoutException("Timed out waiting for a connection"))) {
                        queueDepth.decrementAndGet();
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
                queueDepth.decrementAndGet();
                waiting.setSuccess(channel);
                return;
            }

            idleChannels.addLast(channel);
        });
    }

    private void connectNew(Promise<Channel> promise) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(channelClass)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new HttpClientCodec()
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
