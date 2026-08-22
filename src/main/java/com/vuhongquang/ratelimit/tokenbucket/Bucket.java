package com.vuhongquang.ratelimit.tokenbucket;

import java.util.concurrent.atomic.AtomicReference;

public class Bucket {
    private final int maxToken;
    private final long refillRate;
    private final long interval;
    private final AtomicReference<State> state;

    public Bucket(int maxToken, long refillRate, long interval) {
        this.maxToken = maxToken;
        this.refillRate = refillRate;
        this.interval = interval;
        this.state = new AtomicReference<>(new State(maxToken, System.currentTimeMillis()));
    }

    public boolean tryConsume() {
        while (true) {
            State s = state.get();
            State refilled = refill(s);
            if (refilled.token() <= 0) {
                if (!state.compareAndSet(s, refilled)) continue; // cập nhật lại refill time dù reject
                return false;
            }
            State consumed = new State(refilled.token() - 1, refilled.lastRefillTime());
            if (state.compareAndSet(s, consumed)) {
                return true;
            }
            // thua race -- thread khác vừa đổi state, đọc lại và thử tiếp
        }
    }

    public boolean isFull() {
        State s = refill(state.get());
        return s.token() == maxToken;
    }

    private State refill(State s) {
        long now = System.currentTimeMillis();
        long elapsed = now - s.lastRefillTime();
        long tokensToAdd = elapsed * refillRate / interval;
        if (tokensToAdd <= 0) return s;
        int newToken = (int) Math.min(maxToken, s.token() + tokensToAdd);
        long newRefillTime = s.lastRefillTime() + tokensToAdd * interval / refillRate;
        return new State(newToken, newRefillTime);
    }
}
