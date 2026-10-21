package dev.mayank.convoinsights.ingest;

import dev.mayank.convoinsights.api.InsightView;
import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.ExtractionResult;
import dev.mayank.convoinsights.domain.Insight;
import dev.mayank.convoinsights.domain.InsightRepository;
import dev.mayank.convoinsights.extract.InsightExtractor;
import dev.mayank.convoinsights.outbox.OutboxEvent;
import dev.mayank.convoinsights.outbox.OutboxRepository;
import dev.mayank.convoinsights.privacy.PiiRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Idempotent processing of one event.
 * <ol>
 *   <li>Dedupe on eventId before the slow LLM call.</li>
 *   <li>Redact PII, then extract. No DB transaction is open while waiting on the model.</li>
 *   <li>One short transaction writes the insight and its outbox row together, so a stored
 *       insight always has a pending publish and a publish never exists without its insight.</li>
 * </ol>
 * The unique index on {@code event_id} is the real dedupe guarantee if two deliveries race.
 */
@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    private final InsightRepository insights;
    private final OutboxRepository outbox;
    private final InsightExtractor extractor;
    private final PiiRedactor redactor;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final String enrichedTopic;
    private final InsightMetrics metrics;

    public InsightService(InsightRepository insights, OutboxRepository outbox, InsightExtractor extractor,
                          PiiRedactor redactor, TransactionTemplate tx, ObjectMapper mapper,
                          @Value("${insights.topics.enriched}") String enrichedTopic, InsightMetrics metrics) {
        this.insights = insights;
        this.outbox = outbox;
        this.extractor = extractor;
        this.redactor = redactor;
        this.tx = tx;
        this.mapper = mapper;
        this.enrichedTopic = enrichedTopic;
        this.metrics = metrics;
    }

    public Outcome process(ConversationEvent event) {
        long start = System.nanoTime();
        var existing = insights.findByEventId(event.eventId());
        if (existing.isPresent()) {
            metrics.duplicate();
            return new Outcome(existing.get(), true);
        }
        // Redact before extraction so no extractor, local or hosted, ever sees raw personal data.
        PiiRedactor.Redacted redacted = redactor.redact(event);
        metrics.redacted(redacted);
        ExtractionResult result = extractor.extract(redacted.event());
        Insight insight = Insight.of(event, result, redacted.total());
        try {
            Insight saved = tx.execute(status -> {
                Insight s = insights.saveAndFlush(insight);
                outbox.save(OutboxEvent.of(enrichedTopic, s.getEventId(),
                        mapper.writeValueAsString(InsightView.of(s))));
                return s;
            });
            metrics.processed(result, Duration.ofNanos(System.nanoTime() - start));
            return new Outcome(saved, false);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent delivery of the same event: the other one won, use its row.
            log.info("duplicate insert for eventId={}, using existing row", event.eventId());
            metrics.duplicate();
            return insights.findByEventId(event.eventId())
                    .map(i -> new Outcome(i, true))
                    .orElseThrow(() -> e);
        }
    }

    public record Outcome(Insight insight, boolean duplicate) {
    }
}
