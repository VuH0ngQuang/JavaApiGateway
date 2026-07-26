package com.vuhongquang.loadbalancer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class BackendPool {

    public static final Logger log = LoggerFactory.getLogger(BackendPool.class);

    private final List<Backend> backends;
    private final LoadBalancingStrategy strategy;

    public BackendPool(List<Backend> backends, LoadBalancingStrategy strategy) {
        this.backends = backends;
        this.strategy = strategy;
    }

    public Backend select() {
        return strategy.select(backends);
    }
}
