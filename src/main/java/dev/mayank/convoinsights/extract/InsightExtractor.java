package dev.mayank.convoinsights.extract;

import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.ExtractionResult;

/**
 * Turns a conversation into a summary, sentiment and intent. Implementations must not throw for
 * bad model output; only infrastructure failures that are worth retrying should escape.
 */
public interface InsightExtractor {

    ExtractionResult extract(ConversationEvent event);
}
