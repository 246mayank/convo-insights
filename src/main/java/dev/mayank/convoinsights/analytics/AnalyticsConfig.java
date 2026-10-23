package dev.mayank.convoinsights.analytics;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.streams.StreamsBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.config.TopicBuilder;
import tools.jackson.databind.ObjectMapper;

/** Runs the analytics topology inside the service. Off in the one-shot tools (replay, re-drive). */
@Configuration
@EnableKafkaStreams
@ConditionalOnProperty(name = "insights.analytics.enabled", havingValue = "true", matchIfMissing = true)
class AnalyticsConfig {

    @Bean
    AnalyticsTopology.Settings analyticsSettings(@Value("${insights.topics.enriched}") String input,
                                                 @Value("${insights.topics.brand-sentiment}") String brandSentiment,
                                                 @Value("${insights.topics.alerts}") String alerts,
                                                 @Value("${insights.analytics.bucket:DAY}") AnalyticsTopology.Bucket bucket,
                                                 @Value("${insights.analytics.spike.min-conversations:20}") long minConversations,
                                                 @Value("${insights.analytics.spike.negative-share:0.8}") double negativeShare) {
        return new AnalyticsTopology.Settings(input, brandSentiment, alerts, bucket, minConversations, negativeShare);
    }

    @Bean
    Object analyticsTopology(StreamsBuilder builder, ObjectMapper mapper, AnalyticsTopology.Settings settings) {
        AnalyticsTopology.build(builder, mapper, settings);
        return settings;
    }

    @Bean
    NewTopic brandSentimentTopic(@Value("${insights.topics.brand-sentiment}") String name) {
        return TopicBuilder.name(name).partitions(6).replicas(1).compact().build();
    }

    @Bean
    NewTopic alertsTopic(@Value("${insights.topics.alerts}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).compact().build();
    }
}
