package com.vuhongquang.gateway;

import com.vuhongquang.discovery.DnsServiceDiscovery;
import com.vuhongquang.gateway.request.AddDiscoveryRequest;
import com.vuhongquang.routing.Router;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DiscoveryRegistry {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryRegistry.class);

    private final Router router;
    private final EventLoopGroup group;
    private final Class<? extends DatagramChannel> datagramChannelClass;
    private final BackendRegistry beRegistry;

    private final Map<String, DnsServiceDiscovery> discoveries = new ConcurrentHashMap<>();

    public DiscoveryRegistry(Router router, EventLoopGroup group, Class<? extends DatagramChannel> datagramChannelClass, BackendRegistry beRegistry) {
        this.router = router;
        this.group = group;
        this.datagramChannelClass = datagramChannelClass;
        this.beRegistry = beRegistry;
    }

    public void startDiscovery(AddDiscoveryRequest discoveryReq) {
        if (discoveryReq == null) throw new IllegalArgumentException("startDiscovery called with a null request");
        String route = discoveryReq.route();
        if (discoveries.containsKey(route)) throw new IllegalArgumentException("Failed to add new DNS discovery for route "+route+", route already exists");
        DnsServiceDiscovery discovery = new DnsServiceDiscovery(discoveryReq, router, group, datagramChannelClass, beRegistry);
        discovery.start();
        discoveries.put(route, discovery);
        log.info("Started DNS discovery for route {} -> {}", route, discoveryReq.hostname());
    }

    public Collection<AddDiscoveryRequest> listConfigs() {
        return discoveries
                .values()
                .stream()
                .map(DnsServiceDiscovery::getConfig)
                .collect(Collectors.toList());
    }
}
