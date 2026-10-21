package dev.mayank.convoinsights.extract;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Selects the extractor the pipeline uses via {@code insights.llm.provider}. */
@Configuration
class ExtractorConfig {

    @Bean
    @Primary
    InsightExtractor insightExtractor(@Value("${insights.llm.provider}") String provider, RuleBasedExtractor rules) {
        return switch (provider) {
            case "rules" -> rules;
            default -> throw new IllegalArgumentException("Unknown insights.llm.provider: " + provider);
        };
    }
}
