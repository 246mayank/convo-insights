package dev.mayank.convoinsights.ingest;

import dev.mayank.convoinsights.api.LiveFeed;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Tails insights.enriched for the live page. Each instance uses its own throwaway group starting at
 * the latest offset, so every instance sees every new insight and nothing is replayed on startup.
 * It never throws: a failure here must not send an enriched record to the dead-letter topic.
 */
@Component
@ConditionalOnProperty(name = "insights.live-feed.enabled", havingValue = "true", matchIfMissing = true)
public class LiveFeedListener {

    private static final Logger log = LoggerFactory.getLogger(LiveFeedListener.class);

    private final LiveFeed feed;

    public LiveFeedListener(LiveFeed feed) {
        this.feed = feed;
    }

    @KafkaListener(id = "live-feed", topics = "${insights.topics.enriched}", groupId = "live-feed-${random.uuid}",
            concurrency = "1", properties = "auto.offset.reset=latest")
    public void onInsight(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            if (feed.clients() > 0 && record.value() != null) feed.publish(record.value());
        } catch (RuntimeException e) {
            log.debug("live feed push failed: {}", e.toString());
        } finally {
            ack.acknowledge();
        }
    }
}
