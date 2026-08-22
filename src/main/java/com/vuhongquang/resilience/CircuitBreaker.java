package com.vuhongquang.resilience;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public class CircuitBreaker {

    private final double failureRateThreshold;
    private final long openDurationMs;
    private final int minimumCalls;
    private final int windowSize;

    private final LongAdder recorded = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final AtomicReference<StateHolder> stateRef = new AtomicReference<>(new StateHolder(CircuitStateEnum.CLOSED, 0L, false));

    public CircuitBreaker(long openDurationMs, double failureRateThreshold, int minimumCalls, int windowSize) {
        if (minimumCalls <= 0) {
            throw new IllegalArgumentException(
                    "minimumCalls must be in 1.." + windowSize + ", got " + minimumCalls);
        }
        if (failureRateThreshold <= 0 || failureRateThreshold > 1) {
            throw new IllegalArgumentException(
                    "failureRateThreshold must be in (0,1], got " + failureRateThreshold);
        }

        this.openDurationMs = openDurationMs;
        this.failureRateThreshold = failureRateThreshold;
        this.minimumCalls = minimumCalls;
        this.windowSize = windowSize;
    }

    public void recordFailure() {
        recorded.increment();
        failures.increment();
        StateHolder s = stateRef.get();
        if (s.state() == CircuitStateEnum.HALF_OPEN) {
            stateRef.compareAndSet(s, new StateHolder(CircuitStateEnum.OPEN, System.currentTimeMillis(), false));
        } else if (s.state() == CircuitStateEnum.CLOSED
                && recorded.sum() >= minimumCalls
                && (double) failures.sum() / recorded.sum() >= failureRateThreshold) {
            stateRef.compareAndSet(s, new StateHolder(CircuitStateEnum.OPEN, System.currentTimeMillis(), false));
        }
    }

    public void recordSuccess() {
        recorded.increment();
        StateHolder s = stateRef.get();
        if (s.state() == CircuitStateEnum.HALF_OPEN
                && stateRef.compareAndSet(s, new StateHolder(CircuitStateEnum.CLOSED, 0L, false))) {
            recorded.reset();
            failures.reset();
        }
    }

    public boolean allowRequest() {
        StateHolder s = stateRef.get();
        return switch (s.state()) {
            case CLOSED -> true;
            case OPEN -> {
                if (System.currentTimeMillis() - s.openAt() >= openDurationMs) {
                    yield stateRef.compareAndSet(s, new StateHolder(CircuitStateEnum.HALF_OPEN, s.openAt(), true));
                }
                yield false;
            }
            case HALF_OPEN -> !s.probeInFlight()
                    && stateRef.compareAndSet(s, new StateHolder(s.state(), s.openAt(), true));
        };
    }

    public boolean isAvailable() {
        StateHolder s = stateRef.get();
        return switch (s.state()) {
            case CLOSED -> true;
            case OPEN -> System.currentTimeMillis() - s.openAt() >= openDurationMs;
            case HALF_OPEN -> !s.probeInFlight();
        };
    }

    public CircuitStateEnum state() {
        return stateRef.get().state();
    }

    public double failureRate() {
        long rec = recorded.sum();
        return rec == 0 ? 0.0 : (double) failures.sum() / rec;
    }

    public long openDurationMs() {
        return openDurationMs;
    }

    public double failureRateThreshold() {
        return failureRateThreshold;
    }

    public int minimumCalls() {
        return minimumCalls;
    }

    public int windowSize() {
        return windowSize;
    }
}
