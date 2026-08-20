package com.vuhongquang;

import com.vuhongquang.forwarding.RequestForwarder;
import com.vuhongquang.ratelimit.RateLimiter;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.Future;

import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class BackendResponseHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(BackendResponseHandler.class);
    private static final int RETRY_AFTER_SECONDS = 60;

    private final RequestForwarder forwarder;
    private final RateLimiter limiter;
    private final PrometheusMeterRegistry registry;

    public BackendResponseHandler(
            RequestForwarder forwarder,
            RateLimiter limiter,
            PrometheusMeterRegistry registry) {
        this.forwarder = forwarder;
        this.limiter = limiter;
        this.registry = registry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        String clientIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        Timer.Sample time = Timer.start(registry);
        log.info("-> {} {} from {}", msg.method(), msg.uri(), clientIp);

        final HttpVersion clientVersion = msg.protocolVersion();

        registry.counter("gateway_requests").increment();
        //rate limit
        limiter.tryAcquire(clientIp).addListener((Future<Boolean> f) -> {
            if (!f.isSuccess()) {
                log.error("x- Rate limiter failed for {} on {} {}, allowing request: {}", clientIp, msg.method(), msg.uri(), f.cause().toString());
            } else if (Boolean.FALSE.equals(f.getNow())) {
                log.warn("x- Rate limited {} for {} {}", clientIp, msg.method(), msg.uri());
                var status = HttpResponseStatus.TOO_MANY_REQUESTS;
                var res = new DefaultFullHttpResponse(clientVersion, status);
                res.headers().set(HttpHeaderNames.RETRY_AFTER, RETRY_AFTER_SECONDS);
                res.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                ctx.writeAndFlush(res);
                forwarder.stopTimer(time, status);
                return;
            }
            forwarder.forward(ctx, msg, clientIp, time);
        });
    }
}
