package com.vuhongquang;

import com.vuhongquang.loadbalancer.Backend;
import com.vuhongquang.loadbalancer.BackendPool;
import com.vuhongquang.routing.Router;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class BackendResponseHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(BackendResponseHandler.class);

    private final Router router;

    public BackendResponseHandler(Router router) {
        this.router = router;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        String clientIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        log.info("-> {} {} from {}", msg.method(), msg.uri(), clientIp);

        BackendPool pool = router.match(msg.uri());

        if (pool == null) {
            log.error("x- Failed to reach backend for {} {}: There is no match uri Backend", msg.method(), msg.uri());
            var errRes = new DefaultFullHttpResponse(
                    msg.protocolVersion(),
                    HttpResponseStatus.NOT_FOUND
            );
            errRes.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(errRes);
            return;
        }

        Backend backend = pool.select();

        if (backend == null) {
            log.error("x- Failed to reach backend for {} {}: There is no healthy Backend", msg.method(), msg.uri());
            var errRes = new DefaultFullHttpResponse(
                    msg.protocolVersion(),
                    HttpResponseStatus.SERVICE_UNAVAILABLE
            );
            errRes.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(errRes);
            return;
        }

        var request = new DefaultFullHttpRequest(
                msg.protocolVersion(),
                msg.method(),
                msg.uri(),
                msg.content().retain(),
                msg.headers(),
                msg.trailingHeaders()
        );
        request.headers().set(HttpHeaderNames.HOST, backend.address().getHostName());
        request.headers().set("X-Forwarded-For", clientIp);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(
                                new HttpClientCodec(),
                                new HttpObjectAggregator(64 * 1024),
                                new SimpleChannelInboundHandler<FullHttpResponse>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext context, FullHttpResponse msg) throws Exception {
                                        log.info("<- {} ({} bytes)", msg.status(), msg.content().readableBytes());
                                        msg.retain();
                                        ctx.writeAndFlush(msg);
                                        backend.decrementConnections();
                                    }
                                });
                    }
                });
        bootstrap.connect(backend.address().getHostName(), backend.address().getPort()).addListener( (ChannelFuture future) -> {
            if (future.isSuccess()) {
                future.channel().writeAndFlush(request);
            } else {
                log.error("x- Failed to reach backend for {} {}: {}", msg.method(), msg.uri(), future.cause().toString());
                var errRes = new DefaultFullHttpResponse(
                        msg.protocolVersion(),
                        HttpResponseStatus.BAD_GATEWAY
                );
                errRes.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                request.release();
                backend.decrementConnections();
                ctx.writeAndFlush(errRes);
            }
        });
    }
}
