package com.vuhongquang;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class BackendPool {
    private final List<InetSocketAddress> backends;
    private final AtomicInteger index = new AtomicInteger(0);

    public BackendPool(List<InetSocketAddress> backends) {
        this.backends = backends;
    }

    public InetSocketAddress next () {
        /
    }
}
