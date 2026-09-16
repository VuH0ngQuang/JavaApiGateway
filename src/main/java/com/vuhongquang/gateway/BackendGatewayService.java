package com.vuhongquang.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.vuhongquang.gateway.request.AddBackendRequest;
import com.vuhongquang.gateway.request.AddDiscoveryRequest;
import com.vuhongquang.gateway.request.DeleteBackendRequest;
import com.vuhongquang.gateway.request.PatchBackendRequest;
import com.vuhongquang.loadbalancer.*;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;

import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;


import java.nio.charset.StandardCharsets;

public class BackendGatewayService {

    private static final Logger log = LoggerFactory.getLogger(BackendGatewayService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final PrometheusMeterRegistry registry;
    private final BackendRegistry beRegistry;
    private final DiscoveryRegistry discoveryRegistry;
    private final BackendStatePersister beStatePersister;
    private final DiscoveryStatePersister discoveryStatePersister;

    public BackendGatewayService(PrometheusMeterRegistry registry,
                                 BackendRegistry beRegistry,
                                 DiscoveryRegistry discoveryRegistry,
                                 BackendStatePersister beStatePersister,
                                 DiscoveryStatePersister discoveryStatePersister
    ) {
        this.registry = registry;
        this.beRegistry = beRegistry;
        this.discoveryRegistry = discoveryRegistry;
        this.beStatePersister = beStatePersister;
        this.discoveryStatePersister = discoveryStatePersister;
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
            beRegistry.patchBackend(id, beReq);
            beStatePersister.save();
            sendSuccess(ctx, req);
        } catch (Exception e) {
            log.error("error while patch backend to route {}: {}", route, e.toString());
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void deleteBackend (ChannelHandlerContext ctx, FullHttpRequest req) {
        String id = req.uri().substring("/gateway/backends/".length());
        try {
            DeleteBackendRequest beReq = validateJson(ByteBufUtil.getBytes(req.content()), DeleteBackendRequest.class);
            if (beReq == null) {
                sendError(ctx, req, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            beRegistry.removeBackend(beReq, id);
            beStatePersister.save();
            sendSuccess(ctx, req);
        } catch (Exception e) {
            log.error("error while delete backend for id {}: {}", id, e.toString());
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
            beRegistry.registerBackend(beReq);
            beStatePersister.save();
            log.info("Backend {}:{} added to route {}", beReq.host(), beReq.port(), route);
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

    public void addDiscovery(ChannelHandlerContext ctx, FullHttpRequest req) {
        String route = "";
        try {
            AddDiscoveryRequest discoveryReq = validateJson(ByteBufUtil.getBytes(req.content()), AddDiscoveryRequest.class);
            if (discoveryReq != null) {
                route = discoveryReq.route();
            }
            discoveryRegistry.startDiscovery(discoveryReq);
            discoveryStatePersister.save();
            sendSuccess(ctx, req);
        } catch (Exception e) {
            log.error("error while add new DNS discovery for route {}: {}", route,e.toString());
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
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
}
