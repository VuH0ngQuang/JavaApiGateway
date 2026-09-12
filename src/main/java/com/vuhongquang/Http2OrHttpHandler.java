package com.vuhongquang;

import com.vuhongquang.forwarding.RequestForwarder;
import com.vuhongquang.gateway.BackendGatewayService;
import com.vuhongquang.gateway.GatewayHandler;
import com.vuhongquang.ratelimit.RateLimitHandler;
import com.vuhongquang.ratelimit.RateLimiter;

import com.vuhongquang.routing.Router;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

public class Http2OrHttpHandler extends ApplicationProtocolNegotiationHandler {
    private static final Logger log = LoggerFactory.getLogger(Http2OrHttpHandler.class);

    private final RequestForwarder forwarder;
    private final BackendGatewayService gatewayService;
    private final RateLimiter limiter;
    private final PrometheusMeterRegistry registry;
    private final GatewayConfig config;
    private final Router router;

    public Http2OrHttpHandler(RequestForwarder forwarder,
                              BackendGatewayService gatewayService,
                              RateLimiter limiter,
                              PrometheusMeterRegistry registry,
                              GatewayConfig config,
                              Router router) {
        super(ApplicationProtocolNames.HTTP_1_1);
        this.forwarder = forwarder;
        this.gatewayService = gatewayService;
        this.limiter = limiter;
        this.registry = registry;
        this.config = config;
        this.router = router;
    }

    @Override
    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
        ChannelPipeline pipeline = ctx.pipeline();

        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
            pipeline.addLast("h2-frame-codec", Http2FrameCodecBuilder.forServer().build())
                    .addLast("h2-multiplex", new Http2MultiplexHandler(new Http2StreamInitializer(forwarder, gatewayService, limiter,registry, config, router)));
        } else if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
            pipeline.addLast(
                    new HttpServerCodec(),
                    new RateLimitHandler(limiter, registry),
                    new HybridRequestAggregator(router, forwarder, config),
                    new GatewayHandler(gatewayService),
                    new BackendResponseHandler(forwarder, registry)
            );
        } else {
            log.warn("Unsupported ALPN protocol negotiated: {}; closing connection", protocol);
            ctx.close();
        }
    }
}
