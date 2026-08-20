package com.vuhongquang.loadbalancer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class BackendPool {

    public static final Logger log = LoggerFactory.getLogger(BackendPool.class);

    private final CopyOnWriteArrayList<Backend> backends;
    private final LoadBalancingStrategy strategy;

    public BackendPool(CopyOnWriteArrayList<Backend> backends, LoadBalancingStrategy strategy) {
        this.backends = backends;
        this.strategy = strategy;
    }

    public Backend select(Set<Backend> excluded) {
        return strategy.select(backends, excluded);
    }

    public void addBackend(Backend be) {
        if (backends.contains(be)) {
            throw new IllegalArgumentException("Backend already exists in list: " + be.address());
        } else {
            backends.add(be);
        }
    }

    public Backend select() {
        return strategy.select(backends, new HashSet<>());
    }

    public int size() {
        return backends.size();
    }

    public Optional<Backend> findByAddress(String hostPort) {
        return backends
                .stream()
                .filter(b -> (b.address().getHostString()+":"+b.address().getPort()).equals(hostPort))
                .findFirst();
    }

    public void removeBackend (Backend backend) {
        backends.remove(backend);
    }
}
