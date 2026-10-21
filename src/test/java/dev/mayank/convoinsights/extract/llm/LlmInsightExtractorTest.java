package dev.mayank.convoinsights.extract.llm;

import dev.mayank.convoinsights.TestEvents;
import dev.mayank.convoinsights.domain.Extractor;
import dev.mayank.convoinsights.domain.FallbackReason;
import dev.mayank.convoinsights.domain.Intent;
import dev.mayank.convoinsights.extract.RuleBasedExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmInsightExtractorTest {

    private LlmClient client;
    private LlmInsightExtractor extractor;

    @BeforeEach
    void setUp() {
        client = mock(LlmClient.class);
        when(client.modelName()).thenReturn("stub/model");
        extractor = new LlmInsightExtractor(client, new LlmOutputValidator(JsonMapper.builder().build()),
                new RuleBasedExtractor(), "system prompt", "v1", 50);
    }

    @Test
    void usesValidModelOutput() {
        when(client.complete(anyString(), anyString(), any())).thenReturn(
                "{\"intent\":\"DELIVERY\",\"sentiment\":\"NEUTRAL\",\"summary\":\"Asks where the parcel is.\",\"confidence\":0.7}");
        var r = extractor.extract(TestEvents.customer("e1", "where is my parcel"));
        assertThat(r.extractor()).isEqualTo(Extractor.LLM);
        assertThat(r.intent()).isEqualTo(Intent.DELIVERY);
        assertThat(r.fallbackReason()).isNull();
        assertThat(r.model()).isEqualTo("stub/model");
        assertThat(r.promptVersion()).isEqualTo("v1");
        assertThat(r.llmLatencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
    }

    @Test
    void fallsBackToRulesOnMalformedOutput() {
        when(client.complete(anyString(), anyString(), any())).thenReturn("I think it's about a refund");
        var r = extractor.extract(TestEvents.customer("e1", "I want a refund"));
        assertThat(r.extractor()).isEqualTo(Extractor.RULES);
        assertThat(r.fallbackReason()).isEqualTo(FallbackReason.MALFORMED_JSON);
        assertThat(r.intent()).isEqualTo(Intent.REFUND); // the rules answer
        assertThat(r.model()).isEqualTo("stub/model");
    }

    @Test
    void fallsBackOnTimeoutWithoutThrowing() {
        when(client.complete(anyString(), anyString(), any()))
                .thenThrow(new LlmException(FallbackReason.TIMEOUT, "read timed out", null));
        var r = extractor.extract(TestEvents.customer("e1", "my bill is wrong"));
        assertThat(r.extractor()).isEqualTo(Extractor.RULES);
        assertThat(r.fallbackReason()).isEqualTo(FallbackReason.TIMEOUT);
        assertThat(r.intent()).isEqualTo(Intent.INVOICE);
    }

    @Test
    void sendsTranscriptTruncatedToLimitAndTheSchema() {
        when(client.complete(anyString(), anyString(), any())).thenReturn("{}");
        extractor.extract(TestEvents.customer("e1", "x".repeat(500)));
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(client).complete(org.mockito.ArgumentMatchers.eq("system prompt"), user.capture(),
                org.mockito.ArgumentMatchers.eq(LlmInsightExtractor.OUTPUT_SCHEMA));
        assertThat(user.getValue()).hasSize(50).startsWith("CUSTOMER: ");
    }

    @Test
    void schemaListsEveryIntent() {
        @SuppressWarnings("unchecked")
        var props = (java.util.Map<String, Object>) LlmInsightExtractor.OUTPUT_SCHEMA.get("properties");
        @SuppressWarnings("unchecked")
        var intent = (java.util.Map<String, Object>) props.get("intent");
        assertThat((java.util.List<?>) intent.get("enum")).hasSize(Intent.values().length);
    }
}
