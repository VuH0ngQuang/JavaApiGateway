package com.vuhongquang;

import com.vuhongquang.forwarding.RequestForwarder;
import com.vuhongquang.loadbalancer.BackendPool;
import com.vuhongquang.routing.Router;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;

public class HybridRequestAggregator extends ChannelInboundHandlerAdapter {
    private final Router router;
    private final RequestForwarder forwarder;
    private final GatewayConfig config;

    private HttpRequest crtReq;
    private ByteBuf accumulator;
    private BackendPool pool;
    private boolean streaming = false;
    private Channel ch;
    private String clientIp;
    private final Deque<HttpContent> pending = new ArrayDeque<>();

    public HybridRequestAggregator(Router router, RequestForwarder forwarder, GatewayConfig config) {
        this.router = router;
        this.forwarder = forwarder;
        this.config = config;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest req) {
            resetState();
            if (req.uri().startsWith("/gateway/")) {
                crtReq = req;
                accumulator = ctx.alloc().buffer();
                streaming = false;
                pool = null;
                return;
            }
            pool = router.match(req.uri());
            SocketAddress address = ctx.channel().remoteAddress();
            if (address instanceof InetSocketAddress inetAddress) {
                clientIp = inetAddress.getAddress().getHostAddress();
            }
            if (pool != null && pool.isForceStream()) {
                streaming = true;
                ctx.channel().config().setAutoRead(false);
                forwarder.forwardStreaming(
                        ctx,
                        req,
                        ctx.alloc().buffer(0),
                        clientIp,
                        pool,
                        beCh -> onStreamingBackendReady(ctx, beCh),
                        false);
                return;
            }
            crtReq = req;
            accumulator = ctx.alloc().buffer();
            streaming = false;
            return;
        }

        if (msg instanceof HttpContent content) {
            if (streaming) {
                if (ch != null) {
                    ch.writeAndFlush(content);
                    if (content instanceof LastHttpContent) resetState();
                } else {
                    pending.addLast(content);
                }
                return;
            }

            int newSize = accumulator.readableBytes() + content.content().readableBytes();
            if (pool != null && newSize > config.maxContentLength()) {
                boolean isLast = content instanceof LastHttpContent;
                accumulator.writeBytes(content.content());
                content.release();
                streaming = true;
                ctx.channel().config().setAutoRead(false);
                ByteBuf initialContent = accumulator;
                accumulator = null;
                forwarder.forwardStreaming(ctx, crtReq, initialContent, clientIp, pool,
                        beCh -> onStreamingBackendReady(ctx, beCh), isLast);
                return;
            }

            accumulator.writeBytes(content.content());
            content.release();
            if (content instanceof LastHttpContent) {
                var req = new DefaultFullHttpRequest(
                        crtReq.protocolVersion(),
                        crtReq.method(),
                        crtReq.uri(),
                        accumulator,
                        crtReq.headers(),
                        ((LastHttpContent) content).trailingHeaders()
                );
                accumulator = null;
                ctx.fireChannelRead(req);
                resetState();
            }
        }
    }

    private void onStreamingBackendReady(ChannelHandlerContext ctx, Channel beCh) {
        ctx.channel().config().setAutoRead(true);
        if (beCh == null) {
            resetState();
            return;
        }
        ch = beCh;
        HttpContent queued;
        while ((queued = pending.pollFirst()) != null) {
            ch.writeAndFlush(queued);
            if (queued instanceof LastHttpContent) {
                resetState();
                return;
            }
        }
    }

    private void resetState() {
        pool = null;
        streaming = false;
        ch = null;
        clientIp = null;
        if (accumulator != null) {
            accumulator.release();
        }
        accumulator = null;
        HttpContent leftover;
        while ((leftover = pending.pollFirst()) != null) {
            leftover.release();
        }
    }
}
