package com.vuhongquang.forwarding;

import com.vuhongquang.cache.CachedResponse;
import com.vuhongquang.cache.ResponseCache;
import com.vuhongquang.loadbalancer.Backend;
import com.vuhongquang.loadbalancer.BackendPool;
import com.vuhongquang.pool.ConnectionPool;
import com.vuhongquang.pool.ConnectionPoolManager;
import com.vuhongquang.routing.Router;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class RequestForwarder {
    private static final Logger log = LoggerFactory.getLogger(RequestForwarder.class);

    private final Router router;
    private final ConnectionPoolManager manager;
    private final ResponseCache cache;
    private final PrometheusMeterRegistry registry;

    public RequestForwarder(Router router, ConnectionPoolManager manager, ResponseCache cache, PrometheusMeterRegistry registry) {
        this.router = router;
        this.manager = manager;
        this.cache = cache;
        this.registry = registry;
    }

    public void forward(
            ChannelHandlerContext ctx,
            FullHttpRequest msg,
            String clientIp,
            Timer.Sample time
    ) {
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
                stopTimer(time, cached.status());
                return;
            }
        }
        LinkedHashSet<Backend> triedBackend = new LinkedHashSet<>();
        msg.retain();
        attemptRequest(ctx, msg, clientIp, cacheable, 3, triedBackend, time);
    }

    private void sendError(
            ChannelHandlerContext ctx,
            FullHttpRequest msg,
            HttpResponseStatus status
    ) {
        var errRes = new DefaultFullHttpResponse(msg.protocolVersion(), status);
        msg.release();
        errRes.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(errRes);
    }

    private void finishExchange(
            AtomicBoolean done,
            Backend backend,
            ConnectionPool pool,
            Channel ch,
            ChannelHandler handler,
            boolean success,
            Timer.Sample time,
            HttpResponseStatus status
    ) {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        stopTimer(time, status);
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

    private void attemptRequest(
            ChannelHandlerContext ctx,
            FullHttpRequest msg,
            String clientIp,
            boolean cacheable,
            int attemptsLeft,
            Set<Backend> triedBackend,
            Timer.Sample time
    ) {
        //start request pool for calling to backend and return
        BackendPool pool = router.match(msg.uri());

        if (pool == null) {
            log.error("x- Failed to reach backend for {} {}: There is no match uri Backend", msg.method(), msg.uri());
            var status = HttpResponseStatus.NOT_FOUND;
            sendError(ctx, msg, status);
            stopTimer(time, status);
            return;
        }

        Backend backend = pool.select(triedBackend);
        triedBackend.add(backend);

        if (pool.size() == triedBackend.size()) {
            var it = triedBackend.iterator();
            it.next();
            it.remove();
        }

        if (backend == null) {
            log.error("x- Failed to reach backend for {} {}: There is no healthy Backend", msg.method(), msg.uri());
            var status = HttpResponseStatus.SERVICE_UNAVAILABLE;
            sendError(ctx, msg, status);
            stopTimer(time, status);
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

        final HttpMethod method = msg.method();
        final String uri = msg.uri();

        ConnectionPool connectionPool = manager.poolFor(backend);
        connectionPool.acquire().addListener((Future<Channel> future) -> {
            if (!future.isSuccess()) {
                Throwable cause = future.cause();
                HttpResponseStatus status;
                request.release();
                backend.decrementConnections();
                backend.getBreaker().recordFailure();
                if (attemptsLeft > 1) {
                    attemptRequest(ctx, msg, clientIp, cacheable, attemptsLeft - 1, triedBackend, time);
                    return;
                }
                if (cause instanceof TimeoutException) {
                    log.error("x- Pool at capacity for backend {} on {} {}: {}", backend.address(), method, uri, cause.toString());
                    status = HttpResponseStatus.GATEWAY_TIMEOUT;
                } else {
                    log.error("x- Failed to connect to backend {} for {} {}: {}", backend.address(), method, uri, cause.toString());
                    status = HttpResponseStatus.BAD_GATEWAY;
                }
                sendError(ctx, msg, status);
                stopTimer(time, status);
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
                            msg.release();
                            finishExchange(done, backend, connectionPool, ch, this, true, time, res.status());
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext backendCtx) {
                            log.error("x- Backend {} closed connection before responding to {} {}",
                                    backend.address(), method, uri);
                            sendError(ctx, msg, HttpResponseStatus.BAD_GATEWAY);
                            finishExchange(done, backend, connectionPool, ch, this, false, time, HttpResponseStatus.BAD_GATEWAY);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext backendCtx, Throwable cause) {
                            log.error("x- Error from backend {} for {} {}: {}",
                                    backend.address(), method, uri, cause.toString());
                            sendError(ctx, msg, HttpResponseStatus.BAD_GATEWAY);
                            finishExchange(done, backend, connectionPool, ch, this, false, time, HttpResponseStatus.BAD_GATEWAY);
                        }
                    };

            ch.pipeline().addLast("response", responseHandler);
            ch.writeAndFlush(request).addListener((ChannelFuture wf) -> {
                if (!wf.isSuccess()) {
                    log.error("x- Failed to send request to backend {} for {} {}: {}",
                            backend.address(), method, uri, wf.cause().toString());
                    sendError(ctx, msg, HttpResponseStatus.BAD_GATEWAY);
                    finishExchange(done, backend, connectionPool, ch, responseHandler, false, time, HttpResponseStatus.BAD_GATEWAY);
                }
            });
        });
    }

    public void stopTimer(Timer.Sample time, HttpResponseStatus status) {
        time.stop(registry.timer("gateway_request_duration_seconds", "status", String.valueOf(status.code())));
    }
}
