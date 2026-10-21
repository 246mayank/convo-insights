package dev.mayank.convoinsights.domain;

/** Why an LLM answer was discarded in favour of the rule-based extractor. */
public enum FallbackReason {
    MALFORMED_JSON,
    SCHEMA_VIOLATION,
    TIMEOUT,
    HTTP_ERROR
}
