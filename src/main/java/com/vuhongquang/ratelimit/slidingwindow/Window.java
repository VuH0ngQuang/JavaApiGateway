package com.vuhongquang.ratelimit.slidingwindow;

import java.util.ArrayDeque;

public class Window {
    private final ArrayDeque<Long> timestamps;
    private final int limit;
    private final long windowSizeMs;

    public Window(int limit, long windowSizeMs) {
        this.timestamps = new ArrayDeque<>();
        this.limit = limit;
        this.windowSizeMs = windowSizeMs;
    }

    public synchronized boolean tryConsume() {
        long now = System.currentTimeMillis();
        removeExpired(now);
        //refuse
        if (timestamps.size() >= limit) {
            return false;
        }
        // accept
        timestamps.addLast(now);
        return true;
    }

    public synchronized boolean isIdle() {
        removeExpired(System.currentTimeMillis());
        return timestamps.isEmpty();
    }

    private void removeExpired(long now) {
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= now - windowSizeMs) {
            timestamps.pollFirst();
        }
    }
}
