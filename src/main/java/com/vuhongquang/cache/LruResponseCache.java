package com.vuhongquang.cache;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

public class LruResponseCache implements ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(LruResponseCache.class);

    private final LinkedHashMap<String, CachedResponse> entries;
    private final ReentrantLock lock = new ReentrantLock();
    private final long maxBytes;
    private final AtomicLong bytes = new AtomicLong(0);
    private final long ttlMs;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public LruResponseCache(long maxBytes, long ttlMs) {
        this.maxBytes = maxBytes;
        this.ttlMs = ttlMs;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedResponse> eldest) {
                if (bytes.get() <= maxBytes) {
                    return false;
                }
                bytes.addAndGet(-eldest.getValue().body().length);
                return true;
            }
        };
    }

    @Override
    public CachedResponse get(String uri){
        lock.lock();
        try {
            CachedResponse response = entries.get(uri);
            if (response == null) {
                misses.increment();
                return null;
            }
            if (response.isExpired()) {
                entries.remove(uri, response);
                misses.increment();
                bytes.addAndGet(-response.body().length);
                return null;
            }
            hits.increment();
            return response;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(String uri, HttpResponseStatus status, byte[] body, HttpHeaders headers) {
        var response = new CachedResponse(status, body, headers, System.currentTimeMillis() + ttlMs);
        lock.lock();
        try {
            bytes.addAndGet(response.body().length);
            var previous = entries.put(uri, response);
            if (previous != null) {
                bytes.addAndGet(-previous.body().length);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            entries.clear();
            bytes.set(0);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long hits() {
        return hits.sum();
    }

    @Override
    public long misses() {
        return misses.sum();
    }

    @Override
    public double hitRate() {
        long h = hits.sum();
        long total = h + misses.sum();
        return total == 0 ? 0.0 : (double) h / total;
    }

    @Override
    public long sizeInBytes() {
        return bytes.get();
    }

    @Override
    public int entryCount() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void logStats() {
        log.info("== Cache {} entries, {} bytes, {} hits, {} misses, {} hit rate",
                entryCount(), bytes.get(), hits.sum(), misses.sum(),
                String.format("%.1f%%", hitRate() * 100));
    }
}
