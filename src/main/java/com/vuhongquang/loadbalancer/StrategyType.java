package com.vuhongquang.loadbalancer;

import java.util.Arrays;
import java.util.Optional;

import java.util.function.Supplier;

public enum StrategyType {
    LEAST_CONNECTIONS(0, LeastConnectionsStrategy::new),
    ROUND_ROBIN(1, RoundRobinStrategy::new);

    private final int id;
    private final Supplier<LoadBalancingStrategy> factory;

    StrategyType(int id, Supplier<LoadBalancingStrategy> factory) {
        this.id = id;
        this.factory = factory;
    }

    public int getId() {
        return id;
    }

    public LoadBalancingStrategy create() {
        return factory.get();
    }

    public static Optional<StrategyType> fromId(int id) {
        return Arrays.stream(values())
                .filter(s -> s.id == id)
                .findFirst();
    }

    public static Optional<StrategyType> fromStrategy(LoadBalancingStrategy strategy) {
        return Arrays.stream(values())
                .filter(s -> s.create().getClass().equals(strategy.getClass()))
                .findFirst();
    }
}