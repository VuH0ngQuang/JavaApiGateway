package com.vuhongquang.loadbalancer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public abstract class LoadBalancingStrategy {

    public static final Logger log = LoggerFactory.getLogger(LoadBalancingStrategy.class);

    public final Backend select(List<Backend> backends, Set<Backend> excluded, String clientIp, String uri) {
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
            Backend chosen =  doSelect(healthyBackends, clientIp, uri);
            if (chosen != null && !chosen.getBreaker().allowRequest()) {
                return null;
            }
            if (chosen != null) chosen.incrementConnections();
            return chosen;
        }
    }

    protected abstract Backend doSelect(List<Backend> backends, String clientIp, String uri);

    void onBackendAdded(Backend be) {}

    void onBackendRemoved(Backend be) {}

    private boolean isEmpty(List<Backend> list) {return list.isEmpty();}
}
