package com.vuhongquang.ratelimit;

import io.netty.util.concurrent.Future;

public interface RateLimiter {
    Future<Boolean> tryAcquire(String key);
}
