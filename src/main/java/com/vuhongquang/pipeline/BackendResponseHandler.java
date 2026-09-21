package com.vuhongquang.pipeline;

import com.vuhongquang.forwarding.RequestForwarder;

import com.vuhongquang.ratelimit.RateLimitHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;

import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class BackendResponseHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(BackendResponseHandler.class);
    private static final int RETRY_AFTER_SECONDS = 60;

    private final RequestForwarder forwarder;
    private final Counter requestCounter;

    public BackendResponseHandler(
            RequestForwarder forwarder,
            PrometheusMeterRegistry registry) {
        this.forwarder = forwarder;
        this.requestCounter = registry.counter("gateway_requests");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        String clientIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        log.info("-> {} {} from {}", msg.method(), msg.uri(), clientIp);

        requestCounter.increment();
        Timer.Sample time = ctx.channel().attr(RateLimitHandler.getTimerKey()).get();
        forwarder.forward(ctx, msg, clientIp, time);
    }
}
