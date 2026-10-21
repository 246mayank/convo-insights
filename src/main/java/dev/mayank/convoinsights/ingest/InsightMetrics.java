package dev.mayank.convoinsights.ingest;

import dev.mayank.convoinsights.domain.ExtractionResult;
import dev.mayank.convoinsights.outbox.OutboxRepository;
import dev.mayank.convoinsights.privacy.PiiRedactor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The service's own meters. Kafka client metrics (including consumer lag,
 * {@code kafka_consumer_fetch_manager_records_lag_max}) are bound by Spring Boot separately.
 */
@Component
public class InsightMetrics {

    private final MeterRegistry registry;
    private final Counter duplicates;
    private final Counter deadLettered;

    public InsightMetrics(MeterRegistry registry, OutboxRepository outbox) {
        this.registry = registry;
        this.duplicates = Counter.builder("insights.duplicates")
                .description("Redelivered events skipped by the eventId dedupe").register(registry);
        this.deadLettered = Counter.builder("insights.dlt")
                .description("Records sent to the dead-letter topic").register(registry);
        Gauge.builder("insights.outbox.pending", outbox, OutboxRepository::countPending)
                .description("Outbox rows not yet published").register(registry);
    }

    public void processed(ExtractionResult result, Duration total) {
        Counter.builder("insights.processed").tag("extractor", result.extractor().name())
                .register(registry).increment();
        if (result.fallbackReason() != null) {
            Counter.builder("insights.fallback").tag("reason", result.fallbackReason().name())
                    .register(registry).increment();
        }
        if (result.llmLatencyMs() != null) {
            Timer.builder("insights.llm.latency")
                    .description("Time spent waiting on the model, including answers that were rejected")
                    .tag("model", result.model() == null ? "none" : result.model())
                    .publishPercentileHistogram()
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry)
                    .record(Duration.ofMillis(result.llmLatencyMs()));
        }
        Timer.builder("insights.processing")
                .description("Dedupe check through commit of insight + outbox row")
                .publishPercentileHistogram()
                .register(registry)
                .record(total);
    }

    public void redacted(PiiRedactor.Redacted redacted) {
        redacted.counts().forEach((type, n) ->
                Counter.builder("insights.pii.redactions").tag("type", type.name()).register(registry).increment(n));
    }

    public void duplicate() {
        duplicates.increment();
    }

    public void deadLettered() {
        deadLettered.increment();
    }
}
