package com.vuhongquang.gateway;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

public class GatewayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final BackendGatewayService gatewayService;

    public GatewayHandler(BackendGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        String uri = msg.uri();
        if (uri.startsWith("/gateway/")) {
            String path = uri.substring("/gateway/".length());
            if (path.startsWith("backend")) {
                gatewayService.handler(ctx, msg);
                return;
            } else if (path.startsWith("metrics")) {
                gatewayService.getMetrics(ctx, msg);
                return;
            }
            var errRes = new DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.NOT_FOUND);
            errRes.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(errRes);
        } else {
            msg.retain();
            ctx.fireChannelRead(msg);
        }
    }
}
