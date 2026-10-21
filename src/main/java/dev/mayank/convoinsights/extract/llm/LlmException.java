package dev.mayank.convoinsights.extract.llm;

import dev.mayank.convoinsights.domain.FallbackReason;

/** A model call that produced no usable answer. Carries the reason so the fallback can record it. */
public class LlmException extends RuntimeException {

    private final FallbackReason reason;

    public LlmException(FallbackReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public FallbackReason reason() {
        return reason;
    }
}
