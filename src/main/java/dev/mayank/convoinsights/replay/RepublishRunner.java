package dev.mayank.convoinsights.replay;

import dev.mayank.convoinsights.api.InsightView;
import dev.mayank.convoinsights.domain.Insight;
import dev.mayank.convoinsights.domain.InsightRepository;
import dev.mayank.convoinsights.outbox.OutboxEvent;
import dev.mayank.convoinsights.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Queues every stored insight for publishing again, through the outbox like any other publish.
 * Used after the insight schema gains fields (here: channel, customerId, startedAt) so downstream
 * consumers see the current shape. Safe to repeat: downstream is keyed by eventId and the
 * analytics topology keeps one version per eventId.
 */
@Component
@Profile("republish")
public class RepublishRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RepublishRunner.class);

    private final InsightRepository insights;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final String topic;

    public RepublishRunner(InsightRepository insights, OutboxRepository outbox, TransactionTemplate tx,
                           ObjectMapper mapper, @Value("${insights.topics.enriched}") String topic) {
        this.insights = insights;
        this.outbox = outbox;
        this.tx = tx;
        this.mapper = mapper;
        this.topic = topic;
    }

    @Override
    public void run(ApplicationArguments args) {
        int page = 0;
        long queued = 0;
        List<Insight> batch;
        do {
            batch = insights.findAll(PageRequest.of(page++, 1000, Sort.by("id"))).getContent();
            List<OutboxEvent> rows = new ArrayList<>(batch.size());
            for (Insight i : batch) {
                rows.add(OutboxEvent.of(topic, i.getEventId(), mapper.writeValueAsString(InsightView.of(i))));
            }
            tx.executeWithoutResult(s -> outbox.saveAll(rows));
            queued += rows.size();
        } while (batch.size() == 1000);
        log.info("republish done: queued {} insights on the outbox for {}", queued, topic);
    }
}
