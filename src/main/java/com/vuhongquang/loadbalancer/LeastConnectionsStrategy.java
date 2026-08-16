package com.vuhongquang.loadbalancer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class LeastConnectionsStrategy extends LoadBalancingStrategy{

    private final AtomicInteger index = new AtomicInteger(0);

    @Override
    protected Backend doSelect(List<Backend> backends) {
        int size = backends.size();
        int start = index.getAndIncrement() % size;
        Backend be = backends.get(start);
        for (int offset = 1; offset < size; offset++) {
            var temp = backends.get((start + offset) % size);
            if (temp.activeConnections() < be.activeConnections()) {
                be = temp;
            }
        }
        return be;
    }
}
