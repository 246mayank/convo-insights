package dev.mayank.convoinsights.api;

import dev.mayank.convoinsights.domain.Extractor;
import dev.mayank.convoinsights.domain.FallbackReason;
import dev.mayank.convoinsights.domain.Insight;
import dev.mayank.convoinsights.domain.Intent;
import dev.mayank.convoinsights.domain.Sentiment;

import java.time.Instant;

/** Wire format for an insight, shared by the REST API and the {@code insights.enriched} topic. */
public record InsightView(
        String eventId,
        String conversationId,
        String brand,
        String source,
        int turnCount,
        String summary,
        Sentiment sentiment,
        Intent intent,
        double confidence,
        Extractor extractor,
        FallbackReason fallbackReason,
        String model,
        String promptVersion,
        Long llmLatencyMs,
        int piiRedactions,
        String channel,
        String customerId,
        Instant startedAt,
        Instant createdAt) {

    public static InsightView of(Insight i) {
        return new InsightView(i.getEventId(), i.getConversationId(), i.getBrand(), i.getSource(),
                i.getTurnCount(), i.getSummary(), i.getSentiment(), i.getIntent(), i.getConfidence(),
                i.getExtractor(), i.getFallbackReason(), i.getModel(), i.getPromptVersion(),
                i.getLlmLatencyMs(), i.getPiiRedactions(), i.getChannel(), i.getCustomerId(), i.getStartedAt(),
                i.getCreatedAt());
    }
}
