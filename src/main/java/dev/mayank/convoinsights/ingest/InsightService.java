package dev.mayank.convoinsights.ingest;

import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.ExtractionResult;
import dev.mayank.convoinsights.domain.Insight;
import dev.mayank.convoinsights.domain.InsightRepository;
import dev.mayank.convoinsights.extract.InsightExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Idempotent processing of one event. The dedupe check happens before the slow LLM call, and the
 * unique index on {@code event_id} is the real guarantee if two deliveries race. The LLM call is
 * deliberately outside any DB transaction so no connection is held while waiting on the model.
 */
@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    private final InsightRepository repository;
    private final InsightExtractor extractor;

    public InsightService(InsightRepository repository, InsightExtractor extractor) {
        this.repository = repository;
        this.extractor = extractor;
    }

    public Outcome process(ConversationEvent event) {
        var existing = repository.findByEventId(event.eventId());
        if (existing.isPresent()) {
            return new Outcome(existing.get(), true);
        }
        ExtractionResult result = extractor.extract(event);
        try {
            return new Outcome(repository.saveAndFlush(Insight.of(event, result)), false);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent delivery of the same event: the other one won, use its row.
            log.info("duplicate insert for eventId={}, using existing row", event.eventId());
            return repository.findByEventId(event.eventId())
                    .map(i -> new Outcome(i, true))
                    .orElseThrow(() -> e);
        }
    }

    public record Outcome(Insight insight, boolean duplicate) {
    }
}
