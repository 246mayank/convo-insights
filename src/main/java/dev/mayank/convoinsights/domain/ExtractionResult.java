package dev.mayank.convoinsights.domain;

/**
 * Output of an {@code InsightExtractor}. {@code llmLatencyMs} is the time spent waiting on the
 * model, recorded even when its answer was rejected and the rules fallback was used.
 */
public record ExtractionResult(
        String summary,
        Sentiment sentiment,
        Intent intent,
        double confidence,
        Extractor extractor,
        FallbackReason fallbackReason,
        String model,
        String promptVersion,
        Long llmLatencyMs) {

    public ExtractionResult asFallback(FallbackReason reason, String model, String promptVersion, Long llmLatencyMs) {
        return new ExtractionResult(summary, sentiment, intent, confidence, Extractor.RULES,
                reason, model, promptVersion, llmLatencyMs);
    }
}
