package dev.mayank.convoinsights.ingest;

import dev.mayank.convoinsights.api.InsightView;
import dev.mayank.convoinsights.domain.Insight;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Publishes enriched insights downstream, keyed by eventId so consumers can dedupe too. */
@Component
public class InsightPublisher {

    private final KafkaTemplate<String, String> template;
    private final ObjectMapper mapper;
    private final String topic;

    public InsightPublisher(KafkaTemplate<String, String> template, ObjectMapper mapper,
                            @Value("${insights.topics.enriched}") String topic) {
        this.template = template;
        this.mapper = mapper;
        this.topic = topic;
    }

    /** Blocks until the broker acks, so the caller only commits its offset after a durable publish. */
    public void publish(Insight insight) {
        String json = mapper.writeValueAsString(InsightView.of(insight));
        try {
            template.send(topic, insight.getEventId(), json).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing " + insight.getEventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("failed to publish " + insight.getEventId(), e);
        }
    }
}
