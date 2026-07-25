package com.vuhongquang;

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

    private final BackendPool pool;

    public BackendResponseHandler(BackendPool pool) {
        this.pool = pool;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        String clientIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        log.info("-> {} {} from {}", msg.method(), msg.uri(), clientIp);

        Backend backend = pool.leastConnections();

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
