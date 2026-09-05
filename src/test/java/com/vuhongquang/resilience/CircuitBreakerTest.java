package com.vuhongquang.resilience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void recordFailure_tripsOpenOnlyAfterMinimumCallsReached() {
        CircuitBreaker breaker = new CircuitBreaker(999_999, 0.5, 4, 10);
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(CircuitStateEnum.CLOSED, breaker.state());
        breaker.recordFailure();
        assertEquals(CircuitStateEnum.OPEN, breaker.state());
    }

    @Test
    void allowRequest_transitionsOpenToHalfOpenAfterDuration() {
        CircuitBreaker breaker = new CircuitBreaker(0, 0.5, 1, 10);
        breaker.recordFailure();
        assertEquals(CircuitStateEnum.OPEN, breaker.state());
        assertTrue(breaker.allowRequest());
        assertEquals(CircuitStateEnum.HALF_OPEN, breaker.state());
    }

    @Test
    void recordSuccess_closesCircuitFromHalfOpen() {
        CircuitBreaker breaker = new CircuitBreaker(0, 0.5, 1, 10);
        breaker.recordFailure();
        assertEquals(CircuitStateEnum.OPEN, breaker.state());
        assertTrue(breaker.allowRequest());
        assertEquals(CircuitStateEnum.HALF_OPEN, breaker.state());
        breaker.recordSuccess();
        assertEquals(CircuitStateEnum.CLOSED, breaker.state());
    }
}
