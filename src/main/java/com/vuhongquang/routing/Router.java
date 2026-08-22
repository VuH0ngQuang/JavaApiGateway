package com.vuhongquang.routing;

import com.vuhongquang.loadbalancer.Backend;
import com.vuhongquang.loadbalancer.BackendPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class Router {
    private static final Logger log = LoggerFactory.getLogger(Router.class);

    private final ConcurrentHashMap<String, BackendPool> routes;

    public Router(ConcurrentHashMap<String, BackendPool> routes) {
        this.routes = routes;
    }

    public BackendPool match(String uri) {
        if (routes.isEmpty()) {
            log.error("Routes is empty");
            return null;
        }
        String key = null;
        for (String candidate : routes.keySet()) {
            if (uri.startsWith(candidate) && (key == null || candidate.length() > key.length())) {
                key = candidate;
            }
        }
        if (key == null) {
            log.warn("No route configured for {}", uri);
            return null;
        }
        return routes.get(key);
    }

    public BackendPool getExact(String uri) {
        if (routes.isEmpty()) {
            log.error("Routes is empty");
            return null;
        }
        BackendPool pool = routes.get(uri);
        if (pool == null) {
            log.warn("No route configured for {}", uri);
            return null;
        }
        return pool;
    }

    public void register(String uri, BackendPool pool) {
        if (routes.containsKey(uri)) {
            throw new IllegalArgumentException("Uri already exist on the list "+uri);
        } else {
            routes.put(uri, pool);
        }
    }
}
