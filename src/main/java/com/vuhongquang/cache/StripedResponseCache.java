package com.vuhongquang.cache;

import com.vuhongquang.cache.striped.LruStore;
import com.vuhongquang.cache.striped.StripedCache;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

public class StripedResponseCache implements ResponseCache {
    private static final Logger log = LoggerFactory.getLogger(StripedResponseCache.class);

    private final StripedCache<CachedResponse> cache;
    private final long ttlMS;
    private final long maxBytes;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public StripedResponseCache(long maxBytes, long ttlMs) {
        this.ttlMS = ttlMs;
        this.maxBytes = maxBytes;
        if (maxBytes != 0) {
            this.cache = new StripedCache<>(Runtime.getRuntime().availableProcessors(), maxBytes, LruStore::new, response -> response.body().length);
        } else {
            this.cache = null;
        }
    }

    public CachedResponse get(String uri) {
        if (cache == null) {
            log.warn("cache is disabled (maxBytes=0), get() is a no-op");
            return null;
        }
        Objects.requireNonNull(uri, "uri");
        CachedResponse response = cache.get(uri);
        if (response == null) {
            misses.increment();
            return null;
        }
        if (response.isExpired()) {
            misses.increment();
            cache.remove(uri);
            return null;
        }
        hits.increment();
        return response;
    }

    public CachedResponse put(String uri, HttpResponseStatus status, byte[] body, HttpHeaders headers) {
        if (cache == null) {
            log.warn("cache is disabled (maxBytes=0), put() is a no-op");
            return null;
        }
        Objects.requireNonNull(uri,"uri");
        Objects.requireNonNull(status,"Http status");
        Objects.requireNonNull(body,"Http body");
        Objects.requireNonNull(headers,"Http headers");

        CachedResponse response = new CachedResponse(status, body, headers, System.currentTimeMillis() + ttlMS);
        cache.put(uri, response);
        return response;
    }

    public void clear() {
        if (cache == null) {
            log.warn("cache is disabled (maxBytes=0), clear() is a no-op");
            return;
        }
        cache.clear();
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    public double hitRate() {
        long h = hits.sum();
        long total = h + misses.sum();
        return total == 0 ? 0.0 : (double) h / total;
    }

    public long sizeInBytes() {
        if (cache == null) {
            log.warn("cache is disabled (maxBytes=0), sizeInBytes() is a no-op");
            return 0;
        }
        return cache.sizeInBytes();
    }

    public int entryCount() {
        if (cache == null) {
            log.warn("cache is disabled (maxBytes=0), entryCount() is a no-op");
            return 0;
        }
        return cache.entryCount();
    }

    public void logStats() {
        if (cache == null) return;
        log.info("== Cache {} entries, {} bytes, {} hits, {} misses, {} hit rate",
                entryCount(), this.sizeInBytes(), hits(), misses(),
                String.format("%.1f%%", hitRate() * 100));
    }

    public long maxBytes() {
        return maxBytes;
    }
}
