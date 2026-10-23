package dev.mayank.convoinsights.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Moves committed outbox rows to Kafka. Each poll locks a batch, sends it, waits for the broker
 * acks, and marks the acked rows sent, all in one transaction. A crash after the send but before
 * the commit leaves the rows pending, so they are sent again: delivery is at least once, keyed by
 * eventId, which downstream consumers dedupe on.
 */
@Component
@ConditionalOnProperty(name = "insights.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> template;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final OutboxTracing tracing;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> template, TransactionTemplate tx,
                       @Value("${insights.outbox.batch-size:200}") int batchSize, OutboxTracing tracing) {
        this.tracing = tracing;
        this.repository = repository;
        this.template = template;
        this.tx = tx;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${insights.outbox.poll-ms:200}")
    public void poll() {
        int sent;
        do {
            Integer n = tx.execute(status -> relayBatch());
            sent = n == null ? 0 : n;
        } while (sent == batchSize); // drain a backlog without waiting for the next tick
    }

    /** Returns the number of rows marked sent. Must run inside a transaction (the row locks live there). */
    int relayBatch() {
        List<OutboxEvent> batch = repository.lockPendingBatch(batchSize);
        if (batch.isEmpty()) return 0;

        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(batch.size());
        for (OutboxEvent e : batch) {
            // continue the trace of the transaction that wrote the row
            futures.add(tracing.inChildSpan(e.getTraceParent(), "outbox publish " + e.getTopic(),
                    () -> template.send(e.getTopic(), e.getRecordKey(), e.getPayload())));
        }
        template.flush();

        Instant now = Instant.now();
        int ok = 0;
        for (int i = 0; i < batch.size(); i++) {
            OutboxEvent e = batch.get(i);
            try {
                futures.get(i).get(10, TimeUnit.SECONDS);
                e.markSent(now);
                ok++;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                e.markFailed("interrupted");
            } catch (ExecutionException | TimeoutException ex) {
                e.markFailed(ex.toString());
                log.warn("outbox send failed id={} topic={} key={}: {}", e.getId(), e.getTopic(), e.getRecordKey(), ex.toString());
            }
        }
        repository.saveAll(batch);
        return ok;
    }
}
