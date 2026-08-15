package com.vuhongquang.ratelimit.slidingwindow;

import com.vuhongquang.ratelimit.RateLimiter;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class SlidingWindowLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowLimiter.class);

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private final int limit;
    private final long windowSizeMs;
    private final long interval;
    private final EventLoopGroup group;


    private boolean started = false;

    public SlidingWindowLimiter(int limit, long windowSizeMs, long interval, EventLoopGroup group) {
        this.limit = limit;
        this.windowSizeMs = windowSizeMs;
        this.interval = interval;
        this.group = group;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        group.scheduleAtFixedRate(this::checkAll,0, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public Future<Boolean> tryAcquire(String key) {
        Window window = windows.computeIfAbsent(key, k -> new Window(limit, windowSizeMs));
        return ImmediateEventExecutor.INSTANCE.newSucceededFuture(window.tryConsume());
    }

    private void checkAll() {
        try {
            windows.entrySet().removeIf(e -> e.getValue().isIdle());
        } catch (Throwable t) {
            log.error("x- Rate limiter sweep failed", t);
        }
    }
}
