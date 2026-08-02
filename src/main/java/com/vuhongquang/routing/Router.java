package com.vuhongquang.routing;

import com.vuhongquang.loadbalancer.BackendPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;

public class Router {
    private static final Logger log = LoggerFactory.getLogger(Router.class);

    private final HashMap<String, BackendPool> routes;

    public Router(HashMap<String, BackendPool> routes) {
        this.routes = routes;
    }

    public BackendPool match(String uri) {
        if (routes.isEmpty()) {
            log.error("Routes is empty");
            return null;
        }
        String key = routes.keySet().stream()
                .filter(uri::startsWith)
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
        if (key == null) {
            log.warn("No route configured for {}", uri);
            return null;
        }
        return routes.get(key);
    }
}
