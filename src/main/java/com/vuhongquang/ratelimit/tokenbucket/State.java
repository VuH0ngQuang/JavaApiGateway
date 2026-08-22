package com.vuhongquang.ratelimit.tokenbucket;

public record State(int token, long lastRefillTime) {}
