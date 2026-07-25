package com.vuhongquang;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class BackendPool {
    private final List<Backend> backends;
    private final AtomicInteger index = new AtomicInteger(0);

    public BackendPool(List<Backend> backends) {
        this.backends = backends;
    }

    public Backend next() {
        if (backends.isEmpty()) {
            throw new IllegalStateException("Backend pool is empty");
        }
        int i = index.getAndIncrement();
        return backends.get(i % backends.size());
    }

    public Backend leastConnections() {
        if (backends.isEmpty()) {
            throw new IllegalStateException("Backend pool is empty");
        }
        Backend be = backends.get(0);
        int i = 1;
        while (i != backends.size()) {
            var temp = backends.get(i);
            if (temp.activeConnections() < be.activeConnections()) {
                be = temp;
            }
            i++;
        }
        be.incrementConnections();
        return be;
    }
}
