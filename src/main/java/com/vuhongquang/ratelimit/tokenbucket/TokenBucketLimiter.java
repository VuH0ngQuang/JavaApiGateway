package com.vuhongquang.ratelimit.tokenbucket;

import com.vuhongquang.ratelimit.RateLimiter;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TokenBucketLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();


    private final int maxToken;
    private final long refillRate;
    private final long interval;
    private final EventLoopGroup group;

    private boolean started = false;

    public TokenBucketLimiter(int maxToken, long refillRate, long interval, EventLoopGroup group) {
        this.maxToken = maxToken;
        this.refillRate = refillRate;
        this.interval = interval;
        this.group = group;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        group.scheduleAtFixedRate(this::checkAll,0,interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public Future<Boolean> tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(maxToken, refillRate, interval));
        return ImmediateEventExecutor.INSTANCE.newSucceededFuture(bucket.tryConsume());
    }


    private void checkAll() {
        try {
            buckets.entrySet().removeIf(e -> e.getValue().isFull());
        } catch (Throwable t) {
            log.error("x- Rate limiter sweep failed", t);
        }
    }
}
