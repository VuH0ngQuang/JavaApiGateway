package com.vuhongquang.gateway;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;

import java.nio.charset.StandardCharsets;

public class GatewayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final PrometheusMeterRegistry registry;

    public GatewayHandler(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        String uri = msg.uri();
        if (uri.startsWith("/gateway/")) {
            String path = uri.substring("/gateway/".length());
            switch (path) {
                case "metrics": {
                    var body = registry.scrape().getBytes(StandardCharsets.UTF_8);
                    var res = new DefaultFullHttpResponse(
                            msg.protocolVersion(),
                            HttpResponseStatus.OK,
                            Unpooled.wrappedBuffer(body)
                    );
                    res.headers().set(HttpHeaderNames.CONTENT_TYPE, PrometheusTextFormatWriter.CONTENT_TYPE);
                    res.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
                    ctx.writeAndFlush(res);
                    break;
                }

                default: {
                    var res = new DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.NOT_FOUND);
                    res.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                    ctx.writeAndFlush(res);
                }
            }
        } else {
            msg.retain();
            ctx.fireChannelRead(msg);
        }
    }
}
