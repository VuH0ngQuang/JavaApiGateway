package com.vuhongquang.ratelimit.tokenbucket;

public class Bucket {
    private final int maxToken;
    private final int refillRate;
    private final long interval;
    private int token;
    private long lastRefillTime;

    public Bucket(int maxToken, int refillRate, long interval) {
        this.maxToken = maxToken;
        this.refillRate = refillRate;
        this.interval = interval;
        this.token = maxToken;
        this.lastRefillTime = System.currentTimeMillis();
    }

    public synchronized boolean tryConsume() {
        refillTokens();
        if (token > 0) {
            token--;
            return true;
        }
        return false;
    }

    public synchronized boolean isFull() {
        refillTokens();
        return token == maxToken;
    }

    private void refillTokens() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime;
        long tokensToAdd = elapsed * refillRate / interval;
        if (tokensToAdd > 0) {
            token = (int) Math.min(maxToken, token + tokensToAdd);
            lastRefillTime += tokensToAdd * interval / refillRate;   // keep the remainder
        }
    }
}
