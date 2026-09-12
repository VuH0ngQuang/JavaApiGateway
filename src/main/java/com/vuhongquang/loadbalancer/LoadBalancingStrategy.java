package com.vuhongquang.loadbalancer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public abstract class LoadBalancingStrategy {

    public static final Logger log = LoggerFactory.getLogger(LoadBalancingStrategy.class);

    // Callers (RequestForwarder) log selection failures with full request context
    // (method/uri) -- logging here too would either duplicate that (pool empty /
    // no healthy backend) or be misleading (this class can't tell "no healthy
    // backend" apart from "circuit breaker rejected the pick" once collapsed to a
    // single null return, so it doesn't try).
    public final Backend select(List<Backend> backends, Set<Backend> excluded) {
        if (isEmpty(backends)) {
            return null;
        } else {

            ArrayList<Backend> healthyBackends = new ArrayList<>();

            for (Backend be : backends) {
                if (!excluded.contains(be) && be.isHealthy()) {
                    healthyBackends.add(be);
                }
            }

            if (isEmpty(healthyBackends)) {
                return null;
            }
            Backend chosen =  doSelect(healthyBackends);
            if (chosen != null && !chosen.getBreaker().allowRequest()) {
                return null;
            }
            if (chosen != null) chosen.incrementConnections();
            return chosen;
        }
    }

    protected abstract Backend doSelect(List<Backend> backends);

    private boolean isEmpty(List<Backend> list) {return list.isEmpty();}
}
