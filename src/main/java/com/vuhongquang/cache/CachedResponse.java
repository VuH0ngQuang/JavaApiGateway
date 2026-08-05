package com.vuhongquang.cache;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;

public record CachedResponse(HttpResponseStatus status, byte[] body, HttpHeaders headers, long expiresAt) {
    public CachedResponse {
        body = body.clone();
        headers = new DefaultHttpHeaders().set(headers);
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
