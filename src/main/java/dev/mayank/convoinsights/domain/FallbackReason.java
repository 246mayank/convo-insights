package dev.mayank.convoinsights.domain;

/** Why an LLM answer was discarded in favour of the rule-based extractor. */
public enum FallbackReason {
    MALFORMED_JSON,
    SCHEMA_VIOLATION,
    TIMEOUT,
    HTTP_ERROR,
    /** The circuit breaker is open after repeated timeouts or errors; the model was not called. */
    CIRCUIT_OPEN
}
