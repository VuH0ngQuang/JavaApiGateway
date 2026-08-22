package com.vuhongquang.resilience;

public record StateHolder(CircuitStateEnum state, long openAt, boolean probeInFlight) {}