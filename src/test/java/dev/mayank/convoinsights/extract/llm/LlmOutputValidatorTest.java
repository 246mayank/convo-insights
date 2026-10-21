package dev.mayank.convoinsights.extract.llm;

import dev.mayank.convoinsights.domain.FallbackReason;
import dev.mayank.convoinsights.domain.Intent;
import dev.mayank.convoinsights.domain.Sentiment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmOutputValidatorTest {

    private final LlmOutputValidator validator = new LlmOutputValidator(JsonMapper.builder().build());

    private static final String GOOD =
            "{\"intent\":\"REFUND\",\"sentiment\":\"NEGATIVE\",\"summary\":\"Wants a refund.\",\"confidence\":0.8}";

    @Test
    void acceptsValidOutput() {
        var v = validator.validate(GOOD);
        assertThat(v.intent()).isEqualTo(Intent.REFUND);
        assertThat(v.sentiment()).isEqualTo(Sentiment.NEGATIVE);
        assertThat(v.summary()).isEqualTo("Wants a refund.");
        assertThat(v.confidence()).isEqualTo(0.8);
    }

    @Test
    void repairsCodeFencesAndChatter() {
        var v = validator.validate("Sure! Here you go:\n```json\n" + GOOD + "\n```");
        assertThat(v.intent()).isEqualTo(Intent.REFUND);
    }

    @Test
    void readsPercentageConfidence() {
        var v = validator.validate(GOOD.replace("0.8", "90"));
        assertThat(v.confidence()).isEqualTo(0.9);
    }

    @Test
    void normalisesEnumCase() {
        var v = validator.validate(GOOD.replace("\"REFUND\"", "\"technical support\""));
        assertThat(v.intent()).isEqualTo(Intent.TECHNICAL_SUPPORT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not json at all", "{\"intent\": \"REFUND\"", "[1,2,3]"})
    void malformedJson(String raw) {
        assertReason(raw, FallbackReason.MALFORMED_JSON);
    }

    @Test
    void unknownIntentIsSchemaViolation() {
        assertReason(GOOD.replace("REFUND", "WARRANTY"), FallbackReason.SCHEMA_VIOLATION);
    }

    @Test
    void unknownSentimentIsSchemaViolation() {
        assertReason(GOOD.replace("NEGATIVE", "ANGRY"), FallbackReason.SCHEMA_VIOLATION);
    }

    @Test
    void missingOrBlankSummaryIsSchemaViolation() {
        assertReason(GOOD.replace("\"Wants a refund.\"", "\"  \""), FallbackReason.SCHEMA_VIOLATION);
        assertReason("{\"intent\":\"REFUND\",\"sentiment\":\"NEGATIVE\",\"confidence\":0.8}", FallbackReason.SCHEMA_VIOLATION);
    }

    @Test
    void overlongSummaryIsSchemaViolation() {
        assertReason(GOOD.replace("Wants a refund.", "x".repeat(LlmOutputValidator.MAX_SUMMARY + 1)),
                FallbackReason.SCHEMA_VIOLATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"high\"", "-0.1", "150", "null"})
    void badConfidenceIsSchemaViolation(String confidence) {
        assertReason(GOOD.replace("0.8", confidence), FallbackReason.SCHEMA_VIOLATION);
    }

    private void assertReason(String raw, FallbackReason reason) {
        assertThatThrownBy(() -> validator.validate(raw))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).reason())
                .isEqualTo(reason);
    }
}
