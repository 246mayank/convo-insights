package dev.mayank.convoinsights.ingest;

import dev.mayank.convoinsights.TestEvents;
import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.Insight;
import dev.mayank.convoinsights.domain.InsightRepository;
import dev.mayank.convoinsights.extract.InsightExtractor;
import dev.mayank.convoinsights.extract.RuleBasedExtractor;
import dev.mayank.convoinsights.outbox.OutboxEvent;
import dev.mayank.convoinsights.outbox.OutboxRepository;
import dev.mayank.convoinsights.privacy.PiiRedactor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InsightServiceTest {

    private InsightRepository insights;
    private OutboxRepository outbox;
    private InsightExtractor extractor;
    private InsightService service;

    @BeforeEach
    void setUp() {
        insights = mock(InsightRepository.class);
        outbox = mock(OutboxRepository.class);
        extractor = spy(new RuleBasedExtractor());
        var tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        service = new InsightService(insights, outbox, extractor, new PiiRedactor(), tx,
                JsonMapper.builder().build(), "insights.enriched", "insights.segment", new InsightMetrics(new SimpleMeterRegistry(), outbox),
                dev.mayank.convoinsights.outbox.OutboxTracing.disabled());
        when(insights.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Insight stored(String eventId) {
        var e = TestEvents.customer(eventId, "x");
        return Insight.of(e, new RuleBasedExtractor().extract(e), 0);
    }

    @Test
    void newEventIsExtractedSavedAndQueuedForPublishing() {
        when(insights.findByEventId("e1")).thenReturn(Optional.empty());
        var outcome = service.process(TestEvents.customer("e1", "refund please"));
        assertThat(outcome.duplicate()).isFalse();
        assertThat(outcome.insight().getEventId()).isEqualTo("e1");
        ArgumentCaptor<OutboxEvent> rows = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, org.mockito.Mockito.times(2)).save(rows.capture());
        var enriched = rows.getAllValues().get(0);
        assertThat(enriched.getTopic()).isEqualTo("insights.enriched");
        assertThat(enriched.getRecordKey()).isEqualTo("e1");
        assertThat(enriched.getPayload()).contains("\"eventId\":\"e1\"").contains("\"intent\":\"REFUND\"");
        assertThat(enriched.getSentAt()).isNull();
        var track = rows.getAllValues().get(1);
        assertThat(track.getTopic()).isEqualTo("insights.segment");
        assertThat(track.getRecordKey()).isEqualTo("e1");
        assertThat(track.getPayload()).contains("\"type\":\"track\"").contains("\"messageId\":\"e1\"");
    }

    @Test
    void duplicateSkipsExtractionSaveAndOutbox() {
        var existing = stored("e1");
        when(insights.findByEventId("e1")).thenReturn(Optional.of(existing));
        var outcome = service.process(TestEvents.customer("e1", "refund please"));
        assertThat(outcome.duplicate()).isTrue();
        assertThat(outcome.insight()).isSameAs(existing);
        verify(extractor, never()).extract(any());
        verify(insights, never()).saveAndFlush(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void lostInsertRaceReturnsTheWinnersRow() {
        var winner = stored("e1");
        when(insights.findByEventId("e1")).thenReturn(Optional.empty(), Optional.of(winner));
        when(insights.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("ux_insights_event_id"));
        var outcome = service.process(TestEvents.customer("e1", "refund please"));
        assertThat(outcome.duplicate()).isTrue();
        assertThat(outcome.insight()).isSameAs(winner);
        verify(outbox, never()).save(any());
    }

    @Test
    void integrityErrorThatIsNotADuplicateIsRethrown() {
        when(insights.findByEventId("e1")).thenReturn(Optional.empty(), Optional.empty());
        when(insights.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("some other constraint"));
        assertThatThrownBy(() -> service.process(TestEvents.customer("e1", "refund please")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void extractorOnlySeesRedactedTextButTheRowKeepsTheCount() {
        when(insights.findByEventId("e1")).thenReturn(Optional.empty());
        service.process(TestEvents.customer("e1", "my email is bob@mail.com and phone 555-123-4567"));
        ArgumentCaptor<ConversationEvent> seen = ArgumentCaptor.forClass(ConversationEvent.class);
        verify(extractor).extract(seen.capture());
        assertThat(seen.getValue().customerText())
                .doesNotContain("bob@mail.com").doesNotContain("555-123-4567").contains("[EMAIL]", "[PHONE]");
        ArgumentCaptor<Insight> saved = ArgumentCaptor.forClass(Insight.class);
        verify(insights).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPiiRedactions()).isEqualTo(2);
    }
}
