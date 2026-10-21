package dev.mayank.convoinsights.ingest;

import dev.mayank.convoinsights.domain.ConversationEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * At-least-once consumer. The offset is acknowledged only after the insight is persisted and the
 * enriched record is acked by the broker. A crash anywhere before that means redelivery, which the
 * dedupe in {@link InsightService} turns into a no-op (plus a re-publish of the stored insight).
 */
@Component
public class ConversationConsumer {

    private static final Logger log = LoggerFactory.getLogger(ConversationConsumer.class);

    private final EventParser parser;
    private final InsightService service;
    private final InsightPublisher publisher;

    public ConversationConsumer(EventParser parser, InsightService service, InsightPublisher publisher) {
        this.parser = parser;
        this.service = service;
        this.publisher = publisher;
    }

    @KafkaListener(id = "conversations", topics = "${insights.topics.raw}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        ConversationEvent event = parser.parse(record.value());
        InsightService.Outcome outcome = service.process(event);
        publisher.publish(outcome.insight());
        ack.acknowledge();
        if (outcome.duplicate()) {
            log.info("duplicate eventId={} partition={} offset={} (re-published, not re-extracted)",
                    event.eventId(), record.partition(), record.offset());
        } else {
            log.debug("processed eventId={} intent={} partition={} offset={}",
                    event.eventId(), outcome.insight().getIntent(), record.partition(), record.offset());
        }
    }
}
