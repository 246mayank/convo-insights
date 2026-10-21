package dev.mayank.convoinsights.extract;

import dev.mayank.convoinsights.extract.llm.CircuitBreakingLlmClient;
import dev.mayank.convoinsights.extract.llm.LlmClient;
import dev.mayank.convoinsights.extract.llm.LlmInsightExtractor;
import dev.mayank.convoinsights.extract.llm.LlmOutputValidator;
import dev.mayank.convoinsights.extract.llm.OllamaClient;
import dev.mayank.convoinsights.extract.llm.Prompts;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Selects the extractor via {@code insights.llm.provider}:
 * {@code rules}, {@code ollama}, or {@code client} (an {@link LlmClient} bean supplied by the
 * context, which is how tests plug in a scripted model). Model clients are wrapped in a
 * circuit breaker whose state is exported as {@code insights.llm.circuit.state}.
 */
@Configuration
@EnableConfigurationProperties(ExtractorConfig.LlmProperties.class)
class ExtractorConfig {

    @Bean
    @Primary
    InsightExtractor insightExtractor(LlmProperties props, RuleBasedExtractor rules, ObjectMapper mapper,
                                      ObjectProvider<LlmClient> suppliedClient, MeterRegistry registry) {
        return switch (props.provider()) {
            case "rules" -> rules;
            case "ollama" -> llm(props, rules, mapper, registry, new OllamaClient(props.ollama().baseUrl(),
                    props.ollama().model(), props.timeout(), props.ollama().numCtx(), props.ollama().numPredict()));
            case "client" -> llm(props, rules, mapper, registry, suppliedClient.getObject());
            default -> throw new IllegalArgumentException("Unknown insights.llm.provider: " + props.provider());
        };
    }

    private static InsightExtractor llm(LlmProperties props, RuleBasedExtractor rules, ObjectMapper mapper,
                                        MeterRegistry registry, LlmClient client) {
        CircuitBreaker breaker = CircuitBreakingLlmClient.defaultBreaker("llm", props.circuitOpenFor());
        // 0 = closed, 1 = open, 2 = half-open (other states are not used here)
        Gauge.builder("insights.llm.circuit.state", breaker, b -> switch (b.getState()) {
                    case CLOSED -> 0;
                    case OPEN -> 1;
                    case HALF_OPEN -> 2;
                    default -> -1;
                })
                .description("LLM circuit breaker: 0 closed, 1 open, 2 half-open")
                .register(registry);
        return new LlmInsightExtractor(new CircuitBreakingLlmClient(client, breaker), new LlmOutputValidator(mapper),
                rules, Prompts.load(props.promptVersion()), props.promptVersion(), props.maxInputChars());
    }

    @ConfigurationProperties("insights.llm")
    record LlmProperties(String provider, String promptVersion, Duration timeout, int maxInputChars,
                         Duration circuitOpenFor, Ollama ollama) {
        LlmProperties {
            if (circuitOpenFor == null) circuitOpenFor = Duration.ofSeconds(30);
        }

        record Ollama(String baseUrl, String model, int numCtx, int numPredict) {
        }
    }
}
