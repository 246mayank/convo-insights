package dev.mayank.convoinsights.extract.llm;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiClientTest {

    @Test
    @SuppressWarnings("unchecked")
    void translatesOurSchemaToGeminisSubset() {
        Map<String, Object> g = GeminiClient.toGeminiSchema(LlmInsightExtractor.OUTPUT_SCHEMA);
        assertThat(g.get("type")).isEqualTo("OBJECT");
        var props = (Map<String, Object>) g.get("properties");
        var confidence = (Map<String, Object>) props.get("confidence");
        assertThat(confidence).containsEntry("type", "NUMBER").doesNotContainKeys("minimum", "maximum");
        var intent = (Map<String, Object>) props.get("intent");
        assertThat(intent).containsEntry("type", "STRING").containsKey("enum");
        assertThat(g.get("required")).isEqualTo(LlmInsightExtractor.OUTPUT_SCHEMA.get("required"));
    }

    @Test
    void refusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new GeminiClient(RestClient.builder(), "", "gemini-x", Duration.ofSeconds(5), 256))
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    void namesTheProvider() {
        assertThat(new GeminiClient(RestClient.builder(), "k", "gemini-x", Duration.ofSeconds(5), 256).modelName())
                .isEqualTo("gemini/gemini-x");
    }
}
