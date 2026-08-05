package com.vuhongquang.cache;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;


public interface ResponseCache {
    CachedResponse get(String uri);
    void put(String uri, HttpResponseStatus status, byte[] body, HttpHeaders headers);
    void clear();
    long hits();
    long misses();
    double hitRate();
    long sizeInBytes();
    int entryCount();
    void logStats();
}
