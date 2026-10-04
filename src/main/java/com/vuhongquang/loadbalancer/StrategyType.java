package com.vuhongquang.loadbalancer;

import com.vuhongquang.GatewayConfig;

import java.util.Arrays;
import java.util.Optional;

import java.util.function.BiFunction;

public enum StrategyType {
    LEAST_CONNECTIONS(0, ((keyType, config) -> new LeastConnectionsStrategy())),
    ROUND_ROBIN(1, ((keyType, config) -> new RoundRobinStrategy())),
    CONSISTENT_HASH(2,ConsistentHashStrategy::new);

    private final int id;
    private final BiFunction<ConsistentHashStrategy.KeyType, GatewayConfig, LoadBalancingStrategy> factory;


    StrategyType(int id, BiFunction<ConsistentHashStrategy.KeyType, GatewayConfig, LoadBalancingStrategy> factory) {
        this.id = id;
        this.factory = factory;
    }

    public int getId() {
        return id;
    }

    public LoadBalancingStrategy create(ConsistentHashStrategy.KeyType keyType, GatewayConfig config) {
        return factory.apply(keyType, config);
    }

    public static Optional<StrategyType> fromId(int id) {
        return Arrays.stream(values())
                .filter(s -> s.id == id)
                .findFirst();
    }

    public static Optional<StrategyType> fromStrategy(LoadBalancingStrategy strategy, GatewayConfig config) {
        return Arrays.stream(values())
                .filter(s -> s.create(ConsistentHashStrategy.KeyType.CLIENT_IP, config).getClass().equals(strategy.getClass()))
                .findFirst();
    }
}