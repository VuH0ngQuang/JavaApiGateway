package com.vuhongquang.ratelimit.tokenbucket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketLimiterTest {

    @Test
    void tryAcquire_isolatesBucketsPerKey() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1, 1, 60_000, null);
        assertTrue(limiter.tryAcquire("a").getNow());
        assertFalse(limiter.tryAcquire("a").getNow());
        assertTrue(limiter.tryAcquire("b").getNow());
    }

    @Test
    void tryAcquire_allowsBurstUpToMaxTokenThenRejectsForSameKey() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(3, 1, 60_000, null);
        assertTrue(limiter.tryAcquire("a").getNow());
        assertTrue(limiter.tryAcquire("a").getNow());
        assertTrue(limiter.tryAcquire("a").getNow());
        assertFalse(limiter.tryAcquire("a").getNow());
    }
}
