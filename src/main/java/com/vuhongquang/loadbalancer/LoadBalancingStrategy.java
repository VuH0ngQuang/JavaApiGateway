package com.vuhongquang.loadbalancer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public abstract class LoadBalancingStrategy {

    public static final Logger log = LoggerFactory.getLogger(LoadBalancingStrategy.class);

    public final Backend select(List<Backend> backends) {
        if (isEmpty(backends)) {
            log.error("Backend pool is empty: ");
            return null;
        } else {
            var healthyBackends = backends.stream().filter(Backend::isHealthy).toList();
            if (isEmpty(healthyBackends)) {
                log.error("There is no healthy Backend");
                return null;
            }
            return doSelect(healthyBackends);
        }
    }

    protected abstract Backend doSelect(List<Backend> backends);

    private boolean isEmpty(List<Backend> list) {return list.isEmpty();}
}
