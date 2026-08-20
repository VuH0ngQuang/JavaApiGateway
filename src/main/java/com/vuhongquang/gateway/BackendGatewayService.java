package com.vuhongquang.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.vuhongquang.gateway.request.AddBackendRequest;
import com.vuhongquang.gateway.request.DeleteBackendRequest;
import com.vuhongquang.gateway.request.PatchBackendRequest;
import com.vuhongquang.health.HealthChecker;
import com.vuhongquang.loadbalancer.*;
import com.vuhongquang.pool.ConnectionPoolManager;
import com.vuhongquang.resilience.CircuitBreaker;
import com.vuhongquang.routing.Router;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;

import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import java.net.InetSocketAddress;

import java.nio.charset.StandardCharsets;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class BackendGatewayService {

    private static final Logger log = LoggerFactory.getLogger(BackendGatewayService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Router router;
    private final ConnectionPoolManager poolManager;
    private final HealthChecker healthChecker;
    private final PrometheusMeterRegistry registry;

    public BackendGatewayService(Router router, ConnectionPoolManager poolManager, HealthChecker healthChecker, PrometheusMeterRegistry registry) {
        this.router = router;
        this.poolManager = poolManager;
        this.healthChecker = healthChecker;
        this.registry = registry;
    }

    public void handler(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            HttpMethod method = req.method();
            if (method.equals(HttpMethod.POST)) {
                addBackend(ctx, req);
            } else if (method.equals(HttpMethod.DELETE)) {
                deleteBackend(ctx, req);
            } else if (method.equals(HttpMethod.PATCH)) {
                patchBackend(ctx, req);
            } else {
                sendError(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED);
            }
        } catch (Exception e) {
            log.error("error while handle new backend request: {}", e.toString());
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void patchBackend (ChannelHandlerContext ctx, FullHttpRequest req) {
        String id = req.uri().substring("/gateway/backends/".length());
        String route = "";
        try {
            PatchBackendRequest beReq = validateJson(ByteBufUtil.getBytes(req.content()), PatchBackendRequest.class);
            if (beReq == null) {
                sendError(ctx, req, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            route = beReq.route();
            BackendPool backendPool = router.getExact(beReq.route());
            if (backendPool == null) {
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND);
                return;
            }
            Optional<Backend> beOpt = backendPool.findByAddress(id);
            if (beOpt.isEmpty()) {
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND);
                return;
            }
            Backend be = beOpt.get();
            CircuitBreaker oldBreaker = be.getBreaker();
            if (beReq.minimumCalls() != null ||
                beReq.windowSize() != null ||
                beReq.openDurationMs() != null ||
                beReq.failureRateThreshold() != null
            ) {
                int minimumCalls;
                int windowSize;
                long openDurationMs;
                Double failureRateThreshold;
                if (beReq.minimumCalls() != null) {
                    minimumCalls = beReq.minimumCalls();
                } else {
                    minimumCalls = oldBreaker.minimumCalls();
                }
                if (beReq.windowSize() != null) {
                    windowSize = beReq.windowSize();
                } else {
                    windowSize = oldBreaker.windowSize();
                }
                if (beReq.openDurationMs() != null) {
                    openDurationMs = beReq.openDurationMs();
                } else {
                    openDurationMs = oldBreaker.openDurationMs();
                }
                if (beReq.failureRateThreshold() != null) {
                    failureRateThreshold = beReq.failureRateThreshold();
                } else {
                    failureRateThreshold = oldBreaker.failureRateThreshold();
                }
                CircuitBreaker newBreaker = new CircuitBreaker(openDurationMs, failureRateThreshold, minimumCalls, windowSize);
                be.setBreaker(newBreaker);
            }
            sendSuccess(ctx, req);
        } catch (Exception e) {
            log.error("error while patch backend to route {}: {}", route, e.toString());
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void deleteBackend (ChannelHandlerContext ctx, FullHttpRequest req) {
        String id = req.uri().substring("/gateway/backends/".length());
        String route = "";
        try {
            DeleteBackendRequest beReq = validateJson(ByteBufUtil.getBytes(req.content()), DeleteBackendRequest.class);
            if (beReq == null) {
                sendError(ctx, req, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            route = beReq.route();
            BackendPool backendPool = router.getExact(beReq.route());
            if (backendPool == null) {
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND);
                return;
            }
            Optional<Backend> beOpt = backendPool.findByAddress(id);
            if (beOpt.isEmpty()) {
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND);
                return;
            }
            Backend be = beOpt.get();
            backendPool.removeBackend(be);
            poolManager.deleteBackend(be);
            healthChecker.deleteBackend(be);
            sendSuccess(ctx, req);
        } catch (Exception e) {
            log.error("error while delete backend to route {}: {}", route, e.toString());
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void addBackend (ChannelHandlerContext ctx, FullHttpRequest req) {
        String route = "";
        try {
            AddBackendRequest beReq =  validateJson(ByteBufUtil.getBytes(req.content()), AddBackendRequest.class);
            if (beReq == null) {
                sendError(ctx, req, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            route = beReq.route();
            BackendPool backendPool = router.getExact(beReq.route());
            if (backendPool == null) {
                LoadBalancingStrategy strategy = resolveStrategy(beReq.strategy());
                if (strategy == null) {
                    sendError(ctx, req, HttpResponseStatus.BAD_REQUEST);
                    return;
                }
                backendPool = createNewPool(beReq.route(), strategy);
            }
            Backend be = new Backend(
                    new InetSocketAddress(beReq.host(), beReq.port()),
                    new CircuitBreaker(beReq.openDurationMs(),
                            beReq.failureRateThreshold(),
                            beReq.minimumCalls(),
                            beReq.windowSize()
                    ),
                    registry
            );
            addToPool(backendPool, be);
            sendSuccess(ctx, req);
        } catch (Exception e) {
            log.error("error while add new backend to route {}: {}", route,e.toString());
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public void getMetrics (ChannelHandlerContext ctx, FullHttpRequest req) {
        var body = registry.scrape().getBytes(StandardCharsets.UTF_8);
        var res = new DefaultFullHttpResponse(
                req.protocolVersion(),
                HttpResponseStatus.OK,
                Unpooled.wrappedBuffer(body)
        );
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, PrometheusTextFormatWriter.CONTENT_TYPE);
        res.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(res);
    }

    private <T> T validateJson(byte[] json, Class<T> tClass) {
        try {
            return mapper.readValue(json, tClass);
        } catch (IOException e) {
            log.error("Gateway: Error when parsing json, {}", e.toString());
            return null;
        }
    }

    private void sendError(
            ChannelHandlerContext ctx,
            FullHttpRequest msg,
            HttpResponseStatus status
    ) {
        var errRes = new DefaultFullHttpResponse(msg.protocolVersion(), status);
        errRes.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(errRes);
    }

    private void sendSuccess(
            ChannelHandlerContext ctx,
            FullHttpRequest msg
    ) {
        var res = new DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.OK);
        res.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(res);
    }

    private BackendPool createNewPool(String route, LoadBalancingStrategy loadBalancingStrategy) {
        BackendPool pool = new BackendPool(new CopyOnWriteArrayList<>(), loadBalancingStrategy);
        router.register(route, pool);
        return pool;
    }

    private void addToPool(BackendPool pool, Backend be) {
        pool.addBackend(be);
        poolManager.addBackend(be);
        healthChecker.addBackend(be);
    }

    private LoadBalancingStrategy resolveStrategy(int id) {
        return switch (id) {
            case 0 -> new LeastConnectionsStrategy();
            case 1 -> new RoundRobinStrategy();
            default -> null;
        };
    }
}
