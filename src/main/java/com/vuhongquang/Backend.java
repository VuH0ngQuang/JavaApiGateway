package com.vuhongquang;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

public class Backend {
    private final InetSocketAddress address;
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    public Backend(InetSocketAddress address) {
        this.address = address;
    }

    public InetSocketAddress address() {return address;}
    public int activeConnections() {return activeConnections.get();}
    public void incrementConnections() {activeConnections.incrementAndGet();}
    public void decrementConnections() {activeConnections.decrementAndGet();}
}
