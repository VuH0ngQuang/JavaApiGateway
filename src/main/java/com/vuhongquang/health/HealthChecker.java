package com.vuhongquang.health;

import com.vuhongquang.loadbalancer.Backend;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class HealthChecker {
    private static final Logger log = LoggerFactory.getLogger(HealthChecker.class);

    private final List<Backend> backends;
    private final EventLoopGroup group;

    public HealthChecker(List<Backend> backends, EventLoopGroup group) {
        this.backends = backends;
        this.group = group;
    }

    public void start() {
        group.scheduleAtFixedRate(this::checkAll, 0 ,5, TimeUnit.SECONDS);
    }

    private void checkAll() {
        backends.forEach(this::checkOne);
    }

    private void checkOne(Backend be) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInboundHandlerAdapter());

        bootstrap.connect(be.address()).addListener((ChannelFuture future) -> {
           if (future.isSuccess()) {
               if (!be.isHealthy()) {
                   log.info("Backend {} recovered - marked healthy", be.address());
               }
               be.setHealthy(true);
               future.channel().close();
           } else {
               if (be.isHealthy()) {
                   log.warn("Backend {} is not responding - Healthy is false now", be.address());
               }
               be.setHealthy(false);
               future.channel().close();
           }
        });
    }
}
