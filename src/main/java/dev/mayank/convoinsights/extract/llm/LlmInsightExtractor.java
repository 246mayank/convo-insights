package dev.mayank.convoinsights.extract.llm;

import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.ExtractionResult;
import dev.mayank.convoinsights.domain.Extractor;
import dev.mayank.convoinsights.domain.Intent;
import dev.mayank.convoinsights.domain.Sentiment;
import dev.mayank.convoinsights.extract.InsightExtractor;
import dev.mayank.convoinsights.extract.RuleBasedExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM extraction with a deterministic safety net: any model answer that is late, broken or fails
 * validation is replaced by the rule-based result, tagged with the reason. This never throws for
 * model problems, so a bad model cannot stall the Kafka partition.
 */
public class LlmInsightExtractor implements InsightExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmInsightExtractor.class);

    static final Map<String, Object> OUTPUT_SCHEMA = outputSchema();

    private final LlmClient client;
    private final LlmOutputValidator validator;
    private final RuleBasedExtractor fallback;
    private final String systemPrompt;
    private final String promptVersion;
    private final int maxInputChars;

    public LlmInsightExtractor(LlmClient client, LlmOutputValidator validator, RuleBasedExtractor fallback,
                               String systemPrompt, String promptVersion, int maxInputChars) {
        this.client = client;
        this.validator = validator;
        this.fallback = fallback;
        this.systemPrompt = systemPrompt;
        this.promptVersion = promptVersion;
        this.maxInputChars = maxInputChars;
    }

    @Override
    public ExtractionResult extract(ConversationEvent event) {
        String transcript = truncate(event.transcript(), maxInputChars);
        long start = System.nanoTime();
        try {
            String raw = client.complete(systemPrompt, transcript, OUTPUT_SCHEMA);
            long latencyMs = elapsedMs(start);
            LlmOutputValidator.Validated v;
            try {
                v = validator.validate(raw);
            } catch (LlmException e) {
                log.debug("rejected LLM output for eventId={} reason={} detail={} raw={}",
                        event.eventId(), e.reason(), e.getMessage(), truncate(raw, 300));
                return fallback.extract(event).asFallback(e.reason(), client.modelName(), promptVersion, latencyMs);
            }
            return new ExtractionResult(v.summary(), v.sentiment(), v.intent(), v.confidence(), Extractor.LLM,
                    null, client.modelName(), promptVersion, latencyMs);
        } catch (LlmException e) {
            long latencyMs = elapsedMs(start);
            log.warn("LLM call failed for eventId={} reason={} after {} ms: {}",
                    event.eventId(), e.reason(), latencyMs, e.getMessage());
            return fallback.extract(event).asFallback(e.reason(), client.modelName(), promptVersion, latencyMs);
        }
    }

    public String modelName() {
        return client.modelName();
    }

    public String promptVersion() {
        return promptVersion;
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static Map<String, Object> outputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("intent", Map.of("type", "string", "enum", names(Intent.values())));
        props.put("sentiment", Map.of("type", "string", "enum", names(Sentiment.values())));
        props.put("summary", Map.of("type", "string"));
        props.put("confidence", Map.of("type", "number", "minimum", 0, "maximum", 1));
        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("intent", "sentiment", "summary", "confidence"));
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
