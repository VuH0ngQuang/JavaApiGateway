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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class BackendGatewayService {

    private static final Logger log = LoggerFactory.getLogger(BackendGatewayService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Router router;
    private final ConnectionPoolManager poolManager;
    private final HealthChecker healthChecker;
    private final PrometheusMeterRegistry registry;
    private final GatewayStateStore stateStore;

    public BackendGatewayService(Router router,
                                 ConnectionPoolManager poolManager,
                                 HealthChecker healthChecker,
                                 PrometheusMeterRegistry registry,
                                 GatewayStateStore stateStore) {
        this.router = router;
        this.poolManager = poolManager;
        this.healthChecker = healthChecker;
        this.registry = registry;
        this.stateStore = stateStore;
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
            persistState();
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
            persistState();
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
                log.warn("request contains empty data");
                sendError(ctx, req, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            route = beReq.route();
            registerBackend(beReq);
            persistState();
            sendSuccess(ctx, req);
        } catch (Exception e) {
            log.error("error while add new backend to route {}: {}", route,e.toString());
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public void restoreBackend() {
        List<AddBackendRequest> snapshot;
        try {
            snapshot = stateStore.load();
        } catch (IOException e) {
            log.error("Failed to load gateway state, starting with no backends: {}", e.toString());
            return;
        }
        for (AddBackendRequest beReq : snapshot) {
            try {
                registerBackend(beReq);
            } catch (Exception e) {
                log.error("Failed to restore backend for route {}: {}", beReq.route(), e.toString());
            }
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

    // Thêm hàm persistState() — duyệt router.routes() → mỗi pool → mỗi backend,
    // dựng lại List<AddBackendRequest>,
    // gọi stateStore.save(...).
    // Gọi hàm này ở cuối cả 3 handler addBackend, patchBackend, deleteBackend (ngay trước sendSuccess),
    // vì cả 3 đều làm thay đổi state cần lưu lại.
    private void persistState() {
        try {
            ArrayList<AddBackendRequest> copyBackends = new ArrayList<>();
            for (Map.Entry<String, BackendPool> poolEntry : router.routes().entrySet()) {
                String route = poolEntry.getKey();
                BackendPool pool = poolEntry.getValue();
                List<Backend> backends = pool.backends();
                for (Backend be : backends) {
                    CircuitBreaker breaker = be.getBreaker();
                    LoadBalancingStrategy strategy = pool.strategy();
                    Optional<StrategyType> strategyId = StrategyType.fromStrategy(strategy);
                    if (strategyId.isEmpty()) {
                        throw new IllegalStateException("Route " + route + " uses a LoadBalancingStrategy not registered in StrategyType: " + strategy.getClass());
                    }
                    AddBackendRequest request = new AddBackendRequest(route,
                            be.address().getHostName(),
                            be.address().getPort(),
                            breaker.openDurationMs(),
                            breaker.failureRateThreshold(),
                            breaker.minimumCalls(),
                            breaker.windowSize(),
                            strategyId.get().getId()
                    );
                    copyBackends.add(request);
                }
            }
            stateStore.save(copyBackends);
        } catch (Exception e) {
            log.error("Failed to persist gateway state: {}", e.toString());
        }
    }

    private void registerBackend(AddBackendRequest beReq) {
        if (beReq == null) {
            throw new IllegalArgumentException("registerBackend called with a null request");
        }
        String route = beReq.route();
        BackendPool backendPool = router.getExact(route);
        if (backendPool == null) {
            Optional<StrategyType> strategy = StrategyType.fromId(beReq.strategy());
            if (strategy.isEmpty()) {
                throw new IllegalArgumentException("unknown strategy id: " + beReq.strategy());
            }
            backendPool = createNewPool(route, strategy.get().create());
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
}
