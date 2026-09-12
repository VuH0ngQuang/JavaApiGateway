package com.vuhongquang.ratelimit;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class RateLimitHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RateLimitHandler.class);
    private static final AttributeKey<Timer.Sample> TIMER_KEY = AttributeKey.valueOf("timerSample");
    private static final int RETRY_AFTER_SECONDS = 60;

    private boolean swallowing = false;

    private final RateLimiter limiter;
    private final PrometheusMeterRegistry registry;

    public RateLimitHandler(RateLimiter limiter, PrometheusMeterRegistry registry) {
        this.limiter = limiter;
        this.registry = registry;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (swallowing) {
            if (msg instanceof LastHttpContent) {
                swallowing = false;
            }
            ReferenceCountUtil.release(msg);
            return;
        }
        if (msg instanceof HttpRequest req) {
            ctx.channel().config().setAutoRead(false);
            Timer.Sample time = Timer.start(registry);
            ctx.channel().attr(TIMER_KEY).set(time);
            InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
            String clientIp = remoteAddress.getAddress().getHostAddress();
            limiter.tryAcquire(clientIp).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("x- Rate limiter failed for {} on {} {}, allowing request: {}", clientIp, req.method(), req.uri(), future.cause().toString());
                    ctx.fireChannelRead(req);
                } else if (Boolean.FALSE.equals(future.getNow())) {
                    log.warn("x- Rate limited {} for {} {}", clientIp, req.method(), req.uri());
                    var res = new DefaultFullHttpResponse(req.protocolVersion(), HttpResponseStatus.TOO_MANY_REQUESTS);
                    res.headers().set(HttpHeaderNames.RETRY_AFTER, RETRY_AFTER_SECONDS);
                    res.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                    ctx.writeAndFlush(res);
                    swallowing = true;
                } else {
                    ctx.fireChannelRead(req);
                }
                ctx.channel().config().setAutoRead(true);
            });
            return;
        }
        ctx.fireChannelRead(msg);
    }

    public static AttributeKey<Timer.Sample> getTimerKey() {
        return TIMER_KEY;
    }
}
