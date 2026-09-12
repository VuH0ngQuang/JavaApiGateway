package com.vuhongquang;

import com.vuhongquang.forwarding.RequestForwarder;
import com.vuhongquang.gateway.BackendGatewayService;
import com.vuhongquang.gateway.GatewayHandler;
import com.vuhongquang.ratelimit.RateLimitHandler;
import com.vuhongquang.ratelimit.RateLimiter;
import com.vuhongquang.routing.Router;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Http2StreamInitializer extends ChannelInitializer<Http2StreamChannel> {
    private static final Logger log = LoggerFactory.getLogger(Http2StreamInitializer.class);

    private final RequestForwarder forwarder;
    private final BackendGatewayService gatewayService;
    private final RateLimiter limiter;
    private final PrometheusMeterRegistry registry;
    private final GatewayConfig config;
    private final Router router;

    public Http2StreamInitializer(RequestForwarder forwarder,
                                  BackendGatewayService gatewayService,
                                  RateLimiter limiter,
                                  PrometheusMeterRegistry registry,
                                  GatewayConfig config,
                                  Router router) {
        this.forwarder = forwarder;
        this.gatewayService = gatewayService;
        this.limiter = limiter;
        this.registry = registry;
        this.config = config;
        this.router = router;
    }

    @Override
    protected void initChannel(Http2StreamChannel ch) throws Exception {
        ch.pipeline().addLast(
                new Http2StreamFrameToHttpObjectCodec(true),
                new RateLimitHandler(limiter, registry),
                new HybridRequestAggregator(router, forwarder, config),
                new GatewayHandler(gatewayService),
                new BackendResponseHandler(forwarder, registry)
        );
    }
}
