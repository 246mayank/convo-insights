package dev.mayank.convoinsights.extract;

import dev.mayank.convoinsights.extract.llm.LlmInsightExtractor;
import dev.mayank.convoinsights.extract.llm.LlmOutputValidator;
import dev.mayank.convoinsights.extract.llm.OllamaClient;
import dev.mayank.convoinsights.extract.llm.Prompts;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/** Selects the extractor the pipeline uses via {@code insights.llm.provider}: rules or ollama. */
@Configuration
@EnableConfigurationProperties(ExtractorConfig.LlmProperties.class)
class ExtractorConfig {

    @Bean
    @Primary
    InsightExtractor insightExtractor(LlmProperties props, RuleBasedExtractor rules, ObjectMapper mapper) {
        return switch (props.provider()) {
            case "rules" -> rules;
            case "ollama" -> new LlmInsightExtractor(
                    new OllamaClient(props.ollama().baseUrl(), props.ollama().model(), props.timeout(),
                            props.ollama().numCtx(), props.ollama().numPredict()),
                    new LlmOutputValidator(mapper), rules,
                    Prompts.load(props.promptVersion()), props.promptVersion(), props.maxInputChars());
            default -> throw new IllegalArgumentException("Unknown insights.llm.provider: " + props.provider());
        };
    }

    @ConfigurationProperties("insights.llm")
    record LlmProperties(String provider, String promptVersion, Duration timeout, int maxInputChars, Ollama ollama) {
        record Ollama(String baseUrl, String model, int numCtx, int numPredict) {
        }
    }
}
