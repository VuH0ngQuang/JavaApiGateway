package com.vuhongquang.gateway.request;

import java.util.Objects;

public record DeleteBackendRequest(String route) {
    public DeleteBackendRequest {
        Objects.requireNonNull(route);
    }
}