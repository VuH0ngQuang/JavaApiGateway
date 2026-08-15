package com.vuhongquang.loadbalancer;

import java.util.List;

public class LeastConnectionsStrategy extends LoadBalancingStrategy{
    @Override
    protected Backend doSelect(List<Backend> backends) {
        Backend be = backends.getFirst();
        int i = 1;
        while (i != backends.size()) {
            var temp = backends.get(i);
            if (temp.activeConnections() < be.activeConnections()) {
                be = temp;
            }
            i++;
        }
        return be;
    }
}
