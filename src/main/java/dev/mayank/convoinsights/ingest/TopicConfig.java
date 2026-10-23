package dev.mayank.convoinsights.ingest;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this service owns. KafkaAdmin creates any that are missing at startup and
 * leaves existing ones alone, so the service doesn't depend on broker auto-creation.
 */
@Configuration
class TopicConfig {

    @Bean
    NewTopic rawTopic(@Value("${insights.topics.raw}") String name) {
        return TopicBuilder.name(name).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic enrichedTopic(@Value("${insights.topics.enriched}") String name) {
        return TopicBuilder.name(name).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic segmentTopic(@Value("${insights.topics.segment}") String name) {
        return TopicBuilder.name(name).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic deadLetterTopic(@Value("${insights.topics.dead-letter}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }
}
