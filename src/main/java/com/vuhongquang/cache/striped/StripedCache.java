package com.vuhongquang.cache.striped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class StripedCache<V> {
    public interface SizeOf<V> {int sizeOf(V value);}

    private static final Logger log = LoggerFactory.getLogger(StripedCache.class);
    private final int stripeCount;
    private final EvictionStore<V>[] stripes;
    private final ReentrantLock[] locks;
    private final AtomicLong bytes = new AtomicLong(0);
    private final long maxBytes;
    private final SizeOf<V> sizeOf;

    public StripedCache(int stripeCount, long maxBytes, Supplier<EvictionStore<V>> storeFactory, SizeOf<V> sizeOf) {
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount must be > 0, got: " + stripeCount);
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be > 0, got: " + maxBytes);
        }
        this.stripeCount = stripeCount;
        this.maxBytes = maxBytes;
        this.sizeOf = Objects.requireNonNull(sizeOf, "sizeOf");
        Objects.requireNonNull(storeFactory, "storeFactory");

        // safe: array is private and only holds EvictionStore<V>
        this.stripes = (EvictionStore<V>[]) new EvictionStore[stripeCount];
        this.locks = new ReentrantLock[stripeCount];

        for (int i =0; i < stripeCount; i++) {
            this.stripes[i] = Objects.requireNonNull(storeFactory.get(), "storeFactory returned null");
            this.locks[i] = new ReentrantLock();
        }
    }

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private int idx(String key) {
        return Math.floorMod(spread(key.hashCode()), stripes.length);
    }

    public V get(String key) {
        Objects.requireNonNull(key, "key");
        int stripe = idx(key);
        ReentrantLock lock = locks[stripe];
        lock.lock();
        try {
            return stripes[stripe].get(key);
        } catch (Exception e) {
            log.error("get failed: stripe={}, key={}", stripe, key, e);
            return null;
        } finally {
            lock.unlock();
        }
    }

    public V put(String key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long newSize = sizeOf.sizeOf(value);
        int stripe = idx(key);
        ReentrantLock lock = locks[stripe];
        lock.lock();
        try {
            EvictionStore<V> store = stripes[stripe];
            V old = store.put(key, value);
            // calculate size diff of Cache if it existed
            long oldSize = (old == null) ? 0 : sizeOf.sizeOf(old);
            bytes.addAndGet(newSize - oldSize);

            while (bytes.get() > maxBytes) {
                V evicted = store.evictOne();
                if (evicted == null) {
                    break; //nothing left to evict
                }
                bytes.addAndGet(-sizeOf.sizeOf(evicted));
            }
            return old;
        } catch (Exception e) {
            log.error("put failed: stripe={}, key={}", stripe, key, e);
            return null;
        } finally {
            lock.unlock();
        }
    }

    public V remove(String key) {
        Objects.requireNonNull(key, "key");
        int stripe = idx(key);
        ReentrantLock lock = locks[stripe];
        lock.lock();
        try {
            V value = stripes[stripe].remove(key);
            if (value != null) {
                bytes.addAndGet(-sizeOf.sizeOf(value));
            }
            return value;
        } catch (Exception e) {
            log.error("remove failed: stripe={}, key={}", stripe, key, e);
            return null;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        for (int i =0; i < stripeCount; i++) {
            ReentrantLock lock = locks[i];
            lock.lock();
            try {
                stripes[i].clear();
            } finally {
                lock.unlock();
            }
        }
        bytes.set(0);
    }

    public int entryCount() {
        int size = 0;
        for (int i =0; i < stripeCount; i++) {
            ReentrantLock lock = locks[i];
            lock.lock();
            try {
                size += stripes[i].size();
            } finally {
                lock.unlock();
            }
        }
        return size;
    }

    public long sizeInBytes() {
        return bytes.get();
    }
}
