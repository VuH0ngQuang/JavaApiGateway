package com.vuhongquang.resilience;

public class CircuitBreaker {
    private final int windowSize;
    private final int minimumCalls;
    private final double failureRateThreshold;
    private final long openDurationMs;

    private final boolean[] failed;
    private int index;
    private int recorded;
    private int failures;
    private CircuitStateEnum state;
    private long openAt;
    private boolean probeInFlight = false;

    public CircuitBreaker(long openDurationMs, double failureRateThreshold, int minimumCalls, int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive, got " + windowSize);
        }
        if (minimumCalls <= 0 || minimumCalls > windowSize) {
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

        this.failed = new boolean[windowSize];
        this.index = 0;
        this.recorded = 0;
        this.failures = 0;
        this.state = CircuitStateEnum.CLOSED;
        this.openAt = 0;
    }

    public synchronized void recordFailure() {
        record(true);
        if (state == CircuitStateEnum.HALF_OPEN) {
            open();
        } else if (state == CircuitStateEnum.CLOSED && thresholdBreached()) {
            open();
        }
    }

    public synchronized void recordSuccess() {
        record(false);
        if (state == CircuitStateEnum.HALF_OPEN) {
            state = CircuitStateEnum.CLOSED;
            index = 0;
            recorded = 0;
            failures = 0;
            probeInFlight = false;
        }
    }

    public synchronized boolean allowRequest() {
        switch (state) {
            case CLOSED -> {
                return true;
            }
            case OPEN -> {
                if (System.currentTimeMillis() - openAt >= openDurationMs) {
                    state = CircuitStateEnum.HALF_OPEN;
                    probeInFlight = true;
                    return true;
                }
                return false;
            }
            case HALF_OPEN -> {
                if (!probeInFlight) {
                    probeInFlight = true;
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    public synchronized boolean isAvailable() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> System.currentTimeMillis() - openAt >= openDurationMs;
            case HALF_OPEN -> !probeInFlight;
        };
    }

    public synchronized CircuitStateEnum state() {
        return state;
    }

    public synchronized double failureRate() {
        return recorded == 0 ? 0.0 : (double) failures / recorded;
    }

    private void open() {
        state = CircuitStateEnum.OPEN;
        openAt = System.currentTimeMillis();
        probeInFlight = false;
    }

    private boolean thresholdBreached() {
        return recorded >= minimumCalls && (double) failures / recorded >= failureRateThreshold;
    }

    private void record(boolean isFailure) {
        if (recorded == windowSize && failed[index]) {
            failures--;
        }
        failed[index] = isFailure;
        if (isFailure) {
            failures++;
        }
        index = (index + 1) % windowSize;
        if (recorded < windowSize) {
            recorded++;
        }
    }
}
