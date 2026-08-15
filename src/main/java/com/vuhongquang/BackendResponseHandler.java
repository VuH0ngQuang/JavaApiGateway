package com.vuhongquang;

import com.vuhongquang.cache.CachedResponse;
import com.vuhongquang.cache.ResponseCache;
import com.vuhongquang.loadbalancer.Backend;
import com.vuhongquang.loadbalancer.BackendPool;
import com.vuhongquang.pool.ConnectionPool;
import com.vuhongquang.pool.ConnectionPoolManager;
import com.vuhongquang.ratelimit.RateLimiter;
import com.vuhongquang.routing.Router;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class BackendResponseHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(BackendResponseHandler.class);

    private static final int RETRY_AFTER_SECONDS = 60;

    private final Router router;
    private final ConnectionPoolManager manager;
    private final ResponseCache cache;
    private final RateLimiter limiter;

    public BackendResponseHandler(
            Router router,
            ConnectionPoolManager manager,
            ResponseCache cache,
            RateLimiter limiter) {
        this.router = router;
        this.manager = manager;
        this.cache = cache;
        this.limiter = limiter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        String clientIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        log.info("-> {} {} from {}", msg.method(), msg.uri(), clientIp);

        final HttpVersion clientVersion = msg.protocolVersion();

        //rate limit
        limiter.tryAcquire(clientIp).addListener((Future<Boolean> f) -> {
            if (!f.isSuccess()) {
                log.error("x- Rate limiter failed for {} on {} {}, allowing request: {}", clientIp, msg.method(), msg.uri(), f.cause().toString());
            } else if (Boolean.FALSE.equals(f.getNow())) {
                log.warn("x- Rate limited {} for {} {}", clientIp, msg.method(), msg.uri());
                var res = new DefaultFullHttpResponse(clientVersion, HttpResponseStatus.TOO_MANY_REQUESTS);
                res.headers().set(HttpHeaderNames.RETRY_AFTER, RETRY_AFTER_SECONDS);
                res.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                ctx.writeAndFlush(res);
                return;
            }
            forward(ctx, msg, clientIp);
        });
    }

    private void forward(ChannelHandlerContext ctx, FullHttpRequest msg, String clientIp) {
        //check cache for GET
        boolean cacheable = HttpMethod.GET.equals(msg.method());

        if (cacheable) {
            CachedResponse cached = cache.get(msg.uri());
            if (cached != null) {
                var res = new DefaultFullHttpResponse(
                        msg.protocolVersion(),
                        cached.status(),
                        Unpooled.wrappedBuffer(cached.body())
                );
                res.headers().set(cached.headers());
                res.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
                res.headers().remove(HttpHeaderNames.CONNECTION);
                res.headers().set(HttpHeaderNames.CONTENT_LENGTH, cached.body().length);
                log.info("<= {} ({} bytes) cache hit for {} {}",
                        cached.status(), cached.body().length, msg.method(), msg.uri());
                ctx.writeAndFlush(res);
                return;
            }
        }

        //start request pool for calling to backend and return
        BackendPool pool = router.match(msg.uri());

        if (pool == null) {
            log.error("x- Failed to reach backend for {} {}: There is no match uri Backend", msg.method(), msg.uri());
            sendError(ctx, msg.protocolVersion(), HttpResponseStatus.NOT_FOUND);
            return;
        }

        Backend backend = pool.select();

        if (backend == null) {
            log.error("x- Failed to reach backend for {} {}: There is no healthy Backend", msg.method(), msg.uri());
            sendError(ctx, msg.protocolVersion(), HttpResponseStatus.SERVICE_UNAVAILABLE);
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

        final HttpVersion version = msg.protocolVersion();
        final HttpMethod method = msg.method();
        final String uri = msg.uri();

        ConnectionPool connectionPool = manager.poolFor(backend);
        connectionPool.acquire().addListener((Future<Channel> future) -> {
            if (!future.isSuccess()) {
                Throwable cause = future.cause();
                request.release();
                backend.decrementConnections();
                backend.getBreaker().recordFailure();
                if (cause instanceof TimeoutException) {
                    log.error("x- Pool at capacity for backend {} on {} {}: {}",
                            backend.address(), method, uri, cause.toString());
                    sendError(ctx, version, HttpResponseStatus.GATEWAY_TIMEOUT);
                } else {
                    log.error("x- Failed to connect to backend {} for {} {}: {}",
                            backend.address(), method, uri, cause.toString());
                    sendError(ctx, version, HttpResponseStatus.BAD_GATEWAY);
                }
                return;
            }

            Channel ch = future.getNow();

            AtomicBoolean done = new AtomicBoolean(false);

            SimpleChannelInboundHandler<FullHttpResponse> responseHandler =
                    new SimpleChannelInboundHandler<>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext backendCtx, FullHttpResponse res) {
                            log.info("<- {} ({} bytes) from backend {} for {} {}",
                                    res.status(), res.content().readableBytes(),
                                    backend.address(), method, uri);
                            res.retain();
                            if (cacheable && res.status() == HttpResponseStatus.OK) {
                                byte[] body = ByteBufUtil.getBytes(res.content());
                                cache.put(uri, res.status(), body, res.headers());
                            }
                            ctx.writeAndFlush(res);
                            finishExchange(done, backend, connectionPool, ch, this, true);
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext backendCtx) {
                            log.error("x- Backend {} closed connection before responding to {} {}",
                                    backend.address(), method, uri);
                            sendError(ctx, version, HttpResponseStatus.BAD_GATEWAY);
                            finishExchange(done, backend, connectionPool, ch, this, false);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext backendCtx, Throwable cause) {
                            log.error("x- Error from backend {} for {} {}: {}",
                                    backend.address(), method, uri, cause.toString());
                            sendError(ctx, version, HttpResponseStatus.BAD_GATEWAY);
                            finishExchange(done, backend, connectionPool, ch, this, false);
                        }
                    };

            ch.pipeline().addLast("response", responseHandler);

            ch.writeAndFlush(request).addListener((ChannelFuture wf) -> {
                if (!wf.isSuccess()) {
                    log.error("x- Failed to send request to backend {} for {} {}: {}",
                            backend.address(), method, uri, wf.cause().toString());
                    sendError(ctx, version, HttpResponseStatus.BAD_GATEWAY);
                    finishExchange(done, backend, connectionPool, ch, responseHandler, false);
                }
            });
        });
    }

    private static void sendError(ChannelHandlerContext ctx, HttpVersion version, HttpResponseStatus status) {
        var errRes = new DefaultFullHttpResponse(version, status);
        errRes.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(errRes);
    }

    private static void finishExchange(AtomicBoolean done, Backend backend, ConnectionPool pool, Channel ch, ChannelHandler handler, boolean success) {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        if (success) {
            backend.getBreaker().recordSuccess();
        } else {
            backend.getBreaker().recordFailure();
        }
        backend.decrementConnections();
        if (ch.pipeline().context(handler) != null) {
            ch.pipeline().remove(handler);
        }
        pool.release(ch);
    }
}
