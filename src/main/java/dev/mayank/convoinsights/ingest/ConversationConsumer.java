package dev.mayank.convoinsights.ingest;

import dev.mayank.convoinsights.domain.ConversationEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * At-least-once consumer. The offset is acknowledged only after the insight and its outbox row
 * have committed together. A crash before that means redelivery, which the dedupe in
 * {@link InsightService} turns into a no-op; publishing downstream is the outbox relay's job.
 */
@Component
public class ConversationConsumer {

    private static final Logger log = LoggerFactory.getLogger(ConversationConsumer.class);

    private final EventParser parser;
    private final InsightService service;

    public ConversationConsumer(EventParser parser, InsightService service) {
        this.parser = parser;
        this.service = service;
    }

    // idIsGroup = false: without it the listener id silently becomes the consumer group and
    // spring.kafka.consumer.group-id is ignored.
    @KafkaListener(id = "conversations", idIsGroup = false, topics = "${insights.topics.raw}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        ConversationEvent event = parser.parse(record.value());
        InsightService.Outcome outcome = service.process(event);
        ack.acknowledge();
        if (outcome.duplicate()) {
            log.info("duplicate eventId={} partition={} offset={} (already stored, skipped)",
                    event.eventId(), record.partition(), record.offset());
        } else {
            log.debug("processed eventId={} intent={} partition={} offset={}",
                    event.eventId(), outcome.insight().getIntent(), record.partition(), record.offset());
        }
    }
}
