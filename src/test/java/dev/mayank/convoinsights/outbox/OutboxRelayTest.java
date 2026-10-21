package dev.mayank.convoinsights.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private OutboxRepository repository;
    private KafkaTemplate<String, String> template;
    private OutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(OutboxRepository.class);
        template = mock(KafkaTemplate.class);
        relay = new OutboxRelay(repository, template, new TransactionTemplate(mock(PlatformTransactionManager.class)), 2);
    }

    private static CompletableFuture<SendResult<String, String>> acked(String topic, String key) {
        return CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>(topic, key, "v"), null));
    }

    @Test
    void marksAckedRowsSentAndKeepsFailedRowsPending() {
        var ok = OutboxEvent.of("insights.enriched", "e1", "{}");
        var bad = OutboxEvent.of("insights.enriched", "e2", "{}");
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(ok, bad));
        when(template.send("insights.enriched", "e1", "{}")).thenReturn(acked("insights.enriched", "e1"));
        when(template.send("insights.enriched", "e2", "{}"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        int sent = relay.relayBatch();

        assertThat(sent).isEqualTo(1);
        assertThat(ok.getSentAt()).isNotNull();
        assertThat(bad.getSentAt()).as("a failed send stays pending and is retried on the next poll").isNull();
        assertThat(bad.getAttempts()).isEqualTo(1);
        assertThat(bad.getLastError()).contains("broker unavailable");
        verify(repository).saveAll(List.of(ok, bad));
    }

    @Test
    void emptyBatchSendsNothing() {
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of());
        assertThat(relay.relayBatch()).isZero();
        verify(template, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void pollDrainsFullBatchesUntilAShortOne() {
        var a = OutboxEvent.of("t", "a", "{}");
        var b = OutboxEvent.of("t", "b", "{}");
        var c = OutboxEvent.of("t", "c", "{}");
        when(repository.lockPendingBatch(2)).thenReturn(List.of(a, b), List.of(c));
        when(template.send(eq("t"), anyString(), anyString())).thenAnswer(inv -> acked("t", inv.getArgument(1)));

        relay.poll();

        assertThat(List.of(a, b, c)).allSatisfy(e -> assertThat(e.getSentAt()).isNotNull());
    }
}
