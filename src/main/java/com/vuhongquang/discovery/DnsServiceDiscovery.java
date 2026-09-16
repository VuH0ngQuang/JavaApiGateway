package com.vuhongquang.discovery;

import com.vuhongquang.gateway.BackendRegistry;
import com.vuhongquang.gateway.request.AddBackendRequest;
import com.vuhongquang.gateway.request.AddDiscoveryRequest;
import com.vuhongquang.gateway.request.DeleteBackendRequest;
import com.vuhongquang.loadbalancer.Backend;
import com.vuhongquang.loadbalancer.BackendPool;
import com.vuhongquang.routing.Router;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class DnsServiceDiscovery {
    private static final Logger log = LoggerFactory.getLogger(DnsServiceDiscovery.class);

    private final AddDiscoveryRequest config;
    private final Router router;
    private final DnsNameResolver resolver;
    private final EventLoopGroup group;
    private final BackendRegistry beRegistry;

    private ScheduledFuture<?> scheduledTask;

    public DnsServiceDiscovery(
            AddDiscoveryRequest config,
            Router router,
            EventLoopGroup group,
            Class<? extends DatagramChannel> datagramChannelClass,
            BackendRegistry beRegistry
    ) {
        this.config = config;
        this.router = router;
        this.resolver = new DnsNameResolverBuilder(group.next())
                .datagramChannelType(datagramChannelClass)
                .build();
        this.group = group;
        this.beRegistry = beRegistry;
    }

    public void start() {
        group.scheduleAtFixedRate(this::poll, 0, config.pollIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void poll() {
        resolver.resolveAll(config.hostname()).addListener((Future<List<InetAddress>> future) -> {
            if (!future.isSuccess()) {
                log.warn("Failed to resolve {} for route {}: {}", config.hostname(), config.route(), future.cause().toString());
                return;
            }
            ArrayList<InetAddress> addressList = new ArrayList<>(future.getNow());
            BackendPool pool = router.getExact(config.route());
            if (pool == null) {
                for (InetAddress address : addressList) {
                    AddBackendRequest beReq = new AddBackendRequest(
                            config.route(),
                            address.getHostAddress(),
                            config.port(),
                            config.openDurationMs(),
                            config.failureRateThreshold(),
                            config.minimumCalls(),
                            config.windowSize(),
                            config.strategy(),
                            config.forceStream()
                    );
                    beRegistry.registerBackend(beReq);
                    log.info("Discovery added backend {}:{} to new route {}", address.getHostAddress(), config.port(), config.route());
                }
                return;
            }
            List<Backend> backends = pool.backends();
            for (Backend be : backends) {
                InetAddress address = be.address().getAddress();
                if (addressList.contains(address)) {
                    addressList.remove(address);
                } else {
                    DeleteBackendRequest beReq = new DeleteBackendRequest(config.route());
                    String id = address.getHostAddress() + ":" + config.port();
                    beRegistry.removeBackend(beReq, id);
                    log.info("Discovery removed backend {} from route {} (no longer in DNS)", id, config.route());
                }
            }
            for (InetAddress address : addressList) {
                AddBackendRequest beReq = new AddBackendRequest(
                        config.route(),
                        address.getHostAddress(),
                        config.port(),
                        config.openDurationMs(),
                        config.failureRateThreshold(),
                        config.minimumCalls(),
                        config.windowSize(),
                        config.strategy(),
                        config.forceStream()
                );
                beRegistry.registerBackend(beReq);
                log.info("Discovery added backend {}:{} to route {}", address.getHostAddress(), config.port(), config.route());
            }
        });
    }

    public AddDiscoveryRequest getConfig() {return config;}
}
