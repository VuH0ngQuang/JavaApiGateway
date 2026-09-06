package com.vuhongquang;

import com.vuhongquang.forwarding.RequestForwarder;
import com.vuhongquang.gateway.BackendGatewayService;
import com.vuhongquang.gateway.GatewayHandler;
import com.vuhongquang.ratelimit.RateLimiter;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpObjectAggregator;
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

    public Http2StreamInitializer(RequestForwarder forwarder,
                                  BackendGatewayService gatewayService,
                                  RateLimiter limiter,
                                  PrometheusMeterRegistry registry,
                                  GatewayConfig config) {
        this.forwarder = forwarder;
        this.gatewayService = gatewayService;
        this.limiter = limiter;
        this.registry = registry;
        this.config = config;
    }

    @Override
    protected void initChannel(Http2StreamChannel ch) throws Exception {
        ch.pipeline().addLast(
                new Http2StreamFrameToHttpObjectCodec(true),
                new HttpObjectAggregator(config.maxContentLength()),
                new GatewayHandler(gatewayService),
                new BackendResponseHandler(forwarder, limiter, registry)
        );
    }
}
