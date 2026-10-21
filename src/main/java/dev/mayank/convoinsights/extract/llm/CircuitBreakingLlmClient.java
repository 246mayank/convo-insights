package dev.mayank.convoinsights.extract.llm;

import dev.mayank.convoinsights.domain.FallbackReason;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;
import java.util.Map;

/**
 * Stops calling a model that keeps failing. After enough timeouts or HTTP errors in the recent
 * window the breaker opens and calls fail immediately with {@code CIRCUIT_OPEN}, so each event
 * goes straight to the rules fallback instead of waiting out a timeout. After a cool-down a few
 * trial calls are let through (half-open); if they succeed the breaker closes again.
 * Malformed output does not count as a failure: the model is up, it just answered badly.
 */
public class CircuitBreakingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final CircuitBreaker breaker;

    public CircuitBreakingLlmClient(LlmClient delegate, CircuitBreaker breaker) {
        this.delegate = delegate;
        this.breaker = breaker;
    }

    public static CircuitBreaker defaultBreaker(String name, Duration openFor) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(openFor)
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(e -> e instanceof LlmException le
                        && (le.reason() == FallbackReason.TIMEOUT || le.reason() == FallbackReason.HTTP_ERROR))
                .build());
    }

    @Override
    public String complete(String systemPrompt, String userMessage, Map<String, Object> jsonSchema) {
        try {
            return breaker.executeSupplier(() -> delegate.complete(systemPrompt, userMessage, jsonSchema));
        } catch (CallNotPermittedException e) {
            throw new LlmException(FallbackReason.CIRCUIT_OPEN, "circuit breaker " + breaker.getName() + " is open", e);
        }
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    public CircuitBreaker breaker() {
        return breaker;
    }
}
