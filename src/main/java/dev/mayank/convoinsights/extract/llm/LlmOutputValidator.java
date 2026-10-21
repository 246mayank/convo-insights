package dev.mayank.convoinsights.extract.llm;

import dev.mayank.convoinsights.domain.FallbackReason;
import dev.mayank.convoinsights.domain.Intent;
import dev.mayank.convoinsights.domain.Sentiment;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * Decides whether raw model text is a usable insight. Structured outputs make bad JSON rare but
 * not impossible (truncation at the token limit, a model that ignores the schema), so nothing is
 * trusted: the text is parsed, every field is checked, and anything off is rejected with a reason.
 * Two narrow repairs are allowed and nothing else: stripping markdown code fences / surrounding
 * prose, and reading a 0-100 confidence as a percentage.
 */
public class LlmOutputValidator {

    static final int MAX_SUMMARY = 300;

    private final ObjectMapper mapper;

    public LlmOutputValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Validated validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw reject(FallbackReason.MALFORMED_JSON, "empty output");
        }
        JsonNode node = tryParse(raw);
        if (node == null) {
            node = tryParse(outermostObject(raw));
        }
        if (node == null || !node.isObject()) {
            throw reject(FallbackReason.MALFORMED_JSON, "not a JSON object");
        }

        String summary = string(node, "summary");
        if (summary == null || summary.isBlank()) {
            throw reject(FallbackReason.SCHEMA_VIOLATION, "missing summary");
        }
        summary = summary.trim();
        if (summary.length() > MAX_SUMMARY) {
            throw reject(FallbackReason.SCHEMA_VIOLATION, "summary longer than " + MAX_SUMMARY);
        }

        Intent intent = enumValue(Intent.class, string(node, "intent"));
        if (intent == null) {
            throw reject(FallbackReason.SCHEMA_VIOLATION, "unknown intent: " + string(node, "intent"));
        }
        Sentiment sentiment = enumValue(Sentiment.class, string(node, "sentiment"));
        if (sentiment == null) {
            throw reject(FallbackReason.SCHEMA_VIOLATION, "unknown sentiment: " + string(node, "sentiment"));
        }

        JsonNode c = node.get("confidence");
        if (c == null || !c.isNumber()) {
            throw reject(FallbackReason.SCHEMA_VIOLATION, "confidence is not a number");
        }
        double confidence = c.doubleValue();
        if (confidence > 1 && confidence <= 100) {
            confidence = confidence / 100; // the model answered on a percentage scale
        }
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
            throw reject(FallbackReason.SCHEMA_VIOLATION, "confidence out of range: " + c);
        }
        return new Validated(summary, sentiment, intent, confidence);
    }

    private JsonNode tryParse(String text) {
        if (text == null) return null;
        try {
            return mapper.readTree(text);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** The substring from the first '{' to the last '}', which drops code fences and chatter. */
    static String outermostObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : null;
    }

    private static String string(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.stringValue() : null;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LlmException reject(FallbackReason reason, String message) {
        return new LlmException(reason, message, null);
    }

    public record Validated(String summary, Sentiment sentiment, Intent intent, double confidence) {
    }
}
