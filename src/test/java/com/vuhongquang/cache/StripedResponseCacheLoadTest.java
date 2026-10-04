package com.vuhongquang.cache;

import com.vuhongquang.cache.striped.LruStore;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real concurrent load test against StripedResponseCache -- many real threads hammering
 * get()/put() simultaneously, not a single-threaded unit test. Verifies no exceptions/crashes
 * and that the byte-budget accounting stays sane under contention, since that is exactly the
 * kind of bug (race condition, lost update) a green single-threaded test suite cannot catch.
 */
class StripedResponseCacheLoadTest {

    @Test
    void concurrentGetPutStressTest() throws InterruptedException {
        long maxBytes = 10L * 1024 * 1024; // 10MB budget
        long ttlMs = 60_000;
        StripedResponseCache cache = new StripedResponseCache(maxBytes, ttlMs, LruStore::new);

        int threadCount = 32;
        int opsPerThread = 50_000;
        int keySpace = 2_000;

        HttpHeaders headers = new DefaultHttpHeaders();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger totalGets = new AtomicInteger(0);

        long startNanos = System.nanoTime();

        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                Random rnd = new Random();
                try {
                    startGate.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        String uri = "/api/item/" + rnd.nextInt(keySpace);
                        if (rnd.nextBoolean()) {
                            byte[] body = new byte[64 + rnd.nextInt(4096)];
                            cache.put(uri, HttpResponseStatus.OK, body, headers);
                        } else {
                            cache.get(uri);
                            totalGets.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    failed.set(true);
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = doneLatch.await(60, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        pool.shutdown();

        assertTrue(finished, "load test did not finish within 60s -- possible deadlock");
        assertFalse(failed.get(), "at least one thread threw during concurrent get/put -- see stderr above");

        long finalBytes = cache.sizeInBytes();
        int finalEntries = cache.entryCount();
        long finalHits = cache.hits();
        long finalMisses = cache.misses();

        System.out.printf(
                "StripedResponseCache load test: %d threads x %d ops in %dms (%.0f ops/sec)%n" +
                "  final: %d entries, %d/%d bytes, %d hits, %d misses (gets performed: %d)%n",
                threadCount, opsPerThread, elapsedMs,
                (threadCount * opsPerThread) / Math.max(1.0, elapsedMs / 1000.0),
                finalEntries, finalBytes, maxBytes, finalHits, finalMisses, totalGets.get()
        );

        assertTrue(finalBytes >= 0, "byte accounting went negative: " + finalBytes);
        assertTrue(finalBytes <= maxBytes, "byte accounting exceeded budget: " + finalBytes + " > " + maxBytes);
        assertTrue(finalEntries >= 0, "entry count went negative: " + finalEntries);
        assertTrue(finalHits + finalMisses == totalGets.get(),
                "hits(" + finalHits + ") + misses(" + finalMisses + ") != gets performed(" + totalGets.get() + ")");
    }
}
