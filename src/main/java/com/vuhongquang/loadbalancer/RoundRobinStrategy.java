package com.vuhongquang.loadbalancer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class RoundRobinStrategy extends LoadBalancingStrategy{

    private final AtomicInteger index = new AtomicInteger(0);

    @Override
    protected Backend doSelect(List<Backend> backends) {
        int i = index.getAndIncrement();
        return backends.get(i % backends.size());
    }
}
