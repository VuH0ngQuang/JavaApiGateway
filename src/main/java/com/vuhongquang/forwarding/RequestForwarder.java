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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class RequestForwarder {
    private static final Logger log = LoggerFactory.getLogger(RequestForwarder.class);

    private final Router router;
    private final ConnectionPoolManager manager;
    private final ResponseCache cache;
    private final PrometheusMeterRegistry registry;

    private final ConcurrentHashMap<Integer, Timer> timerCache = new ConcurrentHashMap<>();
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    public RequestForwarder(Router router, ConnectionPoolManager manager, ResponseCache cache, PrometheusMeterRegistry registry) {
        this.router = router;
        this.manager = manager;
        this.cache = cache;
        this.registry = registry;
        registry.gauge("gateway_active_streaming_requests", activeStreams);
    }

    public void forward(
            ChannelHandlerContext ctx,
            FullHttpRequest msg,
            String clientIp,
            Timer.Sample time
    ) {
        //check cache for GET
        boolean cacheable = HttpMethod.GET.equals(msg.method());

        if (cache.maxBytes() != 0 && cacheable) {
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

    private void sendError(ChannelHandlerContext ctx,
                           HttpVersion version,
                           HttpResponseStatus status
    ) {
        var errRes = new DefaultFullHttpResponse(version, status);
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
        finishExchangeCore(backend, pool, ch, handler, success);
    }

    private void finishExchange(
            AtomicBoolean done,
            Backend backend,
            ConnectionPool pool,
            Channel ch,
            ChannelHandler handler,
            boolean success
    ) {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        finishExchangeCore(backend, pool, ch, handler, success);
    }

    private void finishExchangeCore(
            Backend backend,
            ConnectionPool pool,
            Channel ch,
            ChannelHandler handler,
            boolean success
    ) {
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

        Backend backend = pool.select(triedBackend, clientIp, msg.uri());
        triedBackend.add(backend);

        if (pool.size() == triedBackend.size()) {
            var it = triedBackend.iterator();
            it.next();
            it.remove();
        }

        if (backend == null) {
            log.error("x- Failed to reach backend for {} {}: no eligible backend (pool empty, all unhealthy, or circuit open)", msg.method(), msg.uri());
            var status = HttpResponseStatus.SERVICE_UNAVAILABLE;
            sendError(ctx, msg, status);
            stopTimer(time, status);
            return;
        }

        var req = new DefaultFullHttpRequest(
                msg.protocolVersion(),
                msg.method(),
                msg.uri(),
                msg.content().retain(),
                msg.headers(),
                msg.trailingHeaders()
        );
        req.headers().set(HttpHeaderNames.HOST, backend.address().getHostName());
        req.headers().set("X-Forwarded-For", clientIp);

        final HttpMethod method = msg.method();
        final String uri = msg.uri();

        ConnectionPool connectionPool = manager.poolFor(backend);
        connectionPool.acquire().addListener((Future<Channel> future) -> {
            if (!future.isSuccess()) {
                Throwable cause = future.cause();
                HttpResponseStatus status;
                req.release();
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

            ChannelInboundHandlerAdapter responseHandler = new ChannelInboundHandlerAdapter() {
                HttpResponseStatus status;
                ByteBuf byteBuf;
                HttpHeaders headers;
                @Override
                public void channelRead(ChannelHandlerContext backendCtx, Object backendMsg) {
                    try {
                        if (backendMsg instanceof HttpResponse res) {
                            status = res.status();
                            headers = res.headers();
                            if (cache.maxBytes() != 0 && cacheable && HttpResponseStatus.OK.equals(status)) {
                                byteBuf = backendCtx.alloc().buffer();
                            }
                            if (ctx.pipeline().context("backpressure") != null) {
                                ctx.pipeline().remove("backpressure");
                            }
                            ctx.pipeline().addLast("backpressure", new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelWritabilityChanged(ChannelHandlerContext clientCtx) {
                                    ch.config().setAutoRead(clientCtx.channel().isWritable());
                                }
                            });
                            ctx.writeAndFlush(res);
                        }
                        if (backendMsg instanceof HttpContent content) {
                            if (byteBuf != null) {
                                byteBuf.writeBytes(content.content().duplicate());
                                if (byteBuf.readableBytes() > cache.maxBytes()) {
                                    log.warn("Response for {} {} exceeded cache.maxBytes() ({} bytes), skipping cache for this response",
                                            method, uri, cache.maxBytes());
                                    byteBuf.release();
                                    byteBuf = null;
                                }
                            }
                            ctx.writeAndFlush(content);
                            if (backendMsg instanceof LastHttpContent) {
                                if (byteBuf != null) {
                                    cache.put(uri, status, ByteBufUtil.getBytes(byteBuf), headers);
                                    byteBuf.release();
                                }
                                finishExchange(done, backend, connectionPool, ch, this, true, time, status);
                                if (ctx.pipeline().context("backpressure") != null) {
                                    ctx.pipeline().remove("backpressure");
                                }
                                msg.release();
                            }
                        }
                    } catch (Exception e) {
                        log.error("x- Unexpected error handling response from backend {} for {} {}: {}", backend.address(), method, uri, e.toString());
                    }
                }
                @Override
                public void channelInactive(ChannelHandlerContext backendCtx) {
                    if (done.get()) return;
                    if (status == null) {
                        log.error("x- Backend {} closed connection before responding to {} {}", backend.address(), method, uri);
                        sendError(ctx, msg, HttpResponseStatus.BAD_GATEWAY);
                    } else {
                        log.error("x- Backend {} closed connection mid-response to {} {}", backend.address(), method, uri);
                        ctx.close();
                    }
                    if (byteBuf != null) byteBuf.release();
                    finishExchange(done, backend, connectionPool, ch, this, false, time, HttpResponseStatus.BAD_GATEWAY);
                    if (ctx.pipeline().context("backpressure") != null) {
                        ctx.pipeline().remove("backpressure");
                    }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext backendCtx, Throwable cause) {
                    if (done.get()) return;
                    log.error("x- Error from backend {} for {} {}: {}", backend.address(), method, uri, cause.toString());
                    if (status == null) {
                        sendError(ctx, msg, HttpResponseStatus.BAD_GATEWAY);
                    } else {
                        ctx.close();
                    }
                    if (byteBuf != null) byteBuf.release();
                    finishExchange(done, backend, connectionPool, ch, this, false, time, HttpResponseStatus.BAD_GATEWAY);
                    if (ctx.pipeline().context("backpressure") != null) {
                        ctx.pipeline().remove("backpressure");
                    }
                }
            };


            ch.pipeline().addLast("response", responseHandler);
            ch.writeAndFlush(req).addListener((ChannelFuture wf) -> {
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
        Timer timer = timerCache.computeIfAbsent(status.code(), code -> registry.timer("gateway_request_duration_seconds", "status", String.valueOf(code)));
        time.stop(timer);
    }

    public void forwardStreaming(ChannelHandlerContext ctx,
                                 HttpRequest headers,
                                 ByteBuf initialContent,
                                 String clientIp,
                                 BackendPool pool,
                                 Consumer<Channel> onBackendReady,
                                 boolean isLastContent
    ) {
        final HttpMethod method = headers.method();
        final String uri = headers.uri();

        Backend be = pool.select(new HashSet<>(), clientIp, headers.uri());
        if (be == null) {
            log.error("x- Failed to reach backend for {} {}: no eligible backend (pool empty, all unhealthy, or circuit open)", method, uri);
            sendError(ctx, headers.protocolVersion(), HttpResponseStatus.SERVICE_UNAVAILABLE);
            initialContent.release();
            onBackendReady.accept(null);
            return;
        }
        activeStreams.incrementAndGet();

        ConnectionPool connectionPool = manager.poolFor(be);
        connectionPool.acquire().addListener((Future<Channel> future) -> {
            if (!future.isSuccess()) {
                Throwable cause = future.cause();
                log.error("x- Failed to connect to backend {} for {} {}: {}", be.address(), method, uri, cause.toString());
                sendError(ctx, headers.protocolVersion(), HttpResponseStatus.BAD_GATEWAY);
                initialContent.release();
                be.decrementConnections();
                be.getBreaker().recordFailure();
                activeStreams.decrementAndGet();
                onBackendReady.accept(null);
                return;
            }

            Channel beChannel = future.getNow();
            AtomicBoolean done = new AtomicBoolean(false);
            long startMs = System.currentTimeMillis();

            ChannelInboundHandlerAdapter responseHandler = new ChannelInboundHandlerAdapter() {
                HttpResponseStatus status;
                @Override
                public void channelRead(ChannelHandlerContext backendCtx, Object backendMsg) {
                    try {
                        if (backendMsg instanceof HttpResponse res) {
                            status = res.status();
                            if (ctx.pipeline().context("backpressure") != null) {
                                ctx.pipeline().remove("backpressure");
                            }
                            ctx.pipeline().addLast("backpressure", new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelWritabilityChanged(ChannelHandlerContext clientCtx) {
                                    beChannel.config().setAutoRead(clientCtx.channel().isWritable());
                                }
                            });
                            ctx.writeAndFlush(res);
                        }
                        if (backendMsg instanceof HttpContent content) {
                            ctx.writeAndFlush(content);
                            if (backendMsg instanceof LastHttpContent) {
                                log.info("<= {} streamed exchange for {} {} to backend {} in {}ms",
                                        status, method, uri, be.address(), System.currentTimeMillis() - startMs);
                                activeStreams.decrementAndGet();
                                finishExchange(done, be, connectionPool, beChannel, this, true);
                                if (ctx.pipeline().context("backpressure") != null) {
                                    ctx.pipeline().remove("backpressure");
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("x- Unexpected error handling streamed response from backend {} for {} {}: {}", be.address(), method, uri, e.toString());
                    }
                }
                @Override
                public void channelInactive(ChannelHandlerContext backendCtx) {
                    if (done.get()) return;
                    if (status == null) {
                        log.error("x- Backend {} closed connection before responding to streamed {} {}", be.address(), method, uri);
                        sendError(ctx, headers.protocolVersion(), HttpResponseStatus.BAD_GATEWAY);
                    } else {
                        log.error("x- Backend {} closed connection mid-response to streamed {} {}", be.address(), method, uri);
                        ctx.close();
                    }
                    activeStreams.decrementAndGet();
                    finishExchange(done, be, connectionPool, beChannel, this, false);
                    if (ctx.pipeline().context("backpressure") != null) {
                        ctx.pipeline().remove("backpressure");
                    }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext backendCtx, Throwable cause) {
                    if (done.get()) return;
                    log.error("x- Error from backend {} for streamed {} {}: {}", be.address(), method, uri, cause.toString());
                    if (status == null) {
                        sendError(ctx, headers.protocolVersion(), HttpResponseStatus.BAD_GATEWAY);
                    } else {
                        ctx.close();
                    }
                    activeStreams.decrementAndGet();
                    finishExchange(done, be, connectionPool, beChannel, this, false);
                    if (ctx.pipeline().context("backpressure") != null) {
                        ctx.pipeline().remove("backpressure");
                    }
                }
            };

            beChannel.pipeline().addLast("response", responseHandler);

            var req = new DefaultHttpRequest(headers.protocolVersion(), method, uri, headers.headers());
            req.headers().set(HttpHeaderNames.HOST, be.address().getHostName());
            req.headers().set("X-Forwarded-For", clientIp);
            beChannel.writeAndFlush(req);
            if (isLastContent) {
                beChannel.writeAndFlush(new DefaultLastHttpContent(initialContent));
            } else if (initialContent.readableBytes() > 0) {
                beChannel.writeAndFlush(new DefaultHttpContent(initialContent));
            } else {
                initialContent.release();
            }

            onBackendReady.accept(beChannel);
        });
    }
}
