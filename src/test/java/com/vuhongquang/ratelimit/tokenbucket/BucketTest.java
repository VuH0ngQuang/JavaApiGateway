package com.vuhongquang.ratelimit.tokenbucket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BucketTest {

    @Test
    void tryConsume_allowsBurstUpToMaxThenRejects() {
        Bucket bucket = new Bucket(3, 1, 60_000);
        assertTrue(bucket.tryConsume());
        assertTrue(bucket.tryConsume());
        assertTrue(bucket.tryConsume());
        assertFalse(bucket.tryConsume());
    }

    @Test
    void isFull_returnsTrueOnFreshBucket() {
        Bucket bucket = new Bucket(3, 1 , 60_000);
        assertTrue(bucket.isFull());
    }

    @Test
    void isFull_returnsFalseAfterConsuming() {
        Bucket bucket = new Bucket(3, 1 , 60_000);
        bucket.tryConsume();
        assertFalse(bucket.isFull());
    }
}
