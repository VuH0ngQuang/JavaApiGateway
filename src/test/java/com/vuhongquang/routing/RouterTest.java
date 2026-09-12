package com.vuhongquang.routing;

import com.vuhongquang.loadbalancer.BackendPool;
import com.vuhongquang.loadbalancer.RoundRobinStrategy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RouterTest {

    private BackendPool newPool() {
        return new BackendPool(new CopyOnWriteArrayList<>(), new RoundRobinStrategy(), true);
    }

    @Test
    void match_returnsLongestPrefixMatch() {
        Router router = new Router(new ConcurrentHashMap<>());
        BackendPool apiPool = newPool();
        BackendPool moviesPool = newPool();
        router.register("/api", apiPool);
        router.register("/api/movies", moviesPool);
        BackendPool result = router.match("/api/movies/123");
        assertSame(moviesPool, result);
    }

    @Test
    void match_returnsNullWhenNoRouteMatches() {
        Router router = new Router(new ConcurrentHashMap<>());
        BackendPool apiPool = newPool();
        router.register("/api", apiPool);
        BackendPool result = router.match("/other");
        assertNull(result);
    }

    @Test
    void register_throwsOnDuplicateRoute() {
        Router router = new Router(new ConcurrentHashMap<>());
        BackendPool moviesPool = newPool();
        router.register("/api/movies", moviesPool);
        assertThrows(IllegalArgumentException.class, () -> {
            router.register("/api/movies", moviesPool);
        });
    }
}
