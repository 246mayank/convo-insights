package dev.mayank.convoinsights.ingest;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Bounded retries with exponential backoff, then the record goes to the dead-letter topic and the
 * partition moves on. Records that can never succeed skip the retries.
 */
@Configuration
class KafkaErrorHandlingConfig {

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template,
                                     @Value("${insights.topics.dead-letter}") String dltTopic,
                                     @Value("${insights.retry.max-attempts}") int maxRetries,
                                     @Value("${insights.retry.initial-interval-ms}") long initialMs,
                                     @Value("${insights.retry.multiplier}") double multiplier,
                                     InsightMetrics metrics) {
        // Partition -1 lets the producer pick one; the DLT has fewer partitions than the source topic.
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(dltTopic, -1));
        var backOff = new ExponentialBackOff(initialMs, multiplier);
        backOff.setMaxAttempts(maxRetries);
        var handler = new DefaultErrorHandler((record, ex) -> {
            recoverer.accept(record, ex);
            metrics.deadLettered();
        }, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        // With manual acks the container does not commit a recovered record by itself. Without this,
        // a dead-lettered record's offset stays uncommitted and the record is re-consumed (and
        // dead-lettered again) after every restart or rebalance. Found by PipelineIT.
        handler.setCommitRecovered(true);
        return handler;
    }
}
