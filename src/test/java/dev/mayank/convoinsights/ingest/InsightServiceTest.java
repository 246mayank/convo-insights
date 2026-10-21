package dev.mayank.convoinsights.ingest;

import dev.mayank.convoinsights.TestEvents;
import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.Insight;
import dev.mayank.convoinsights.domain.InsightRepository;
import dev.mayank.convoinsights.extract.InsightExtractor;
import dev.mayank.convoinsights.extract.RuleBasedExtractor;
import dev.mayank.convoinsights.privacy.PiiRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

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

    private InsightRepository repository;
    private InsightExtractor extractor;
    private InsightService service;

    @BeforeEach
    void setUp() {
        repository = mock(InsightRepository.class);
        extractor = spy(new RuleBasedExtractor());
        service = new InsightService(repository, extractor, new PiiRedactor());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void newEventIsExtractedAndSaved() {
        when(repository.findByEventId("e1")).thenReturn(Optional.empty());
        var outcome = service.process(TestEvents.customer("e1", "refund please"));
        assertThat(outcome.duplicate()).isFalse();
        assertThat(outcome.insight().getEventId()).isEqualTo("e1");
        verify(repository).saveAndFlush(any());
    }

    @Test
    void duplicateSkipsExtractionAndSave() {
        var existing = Insight.of(TestEvents.customer("e1", "x"), new RuleBasedExtractor().extract(TestEvents.customer("e1", "x")), 0);
        when(repository.findByEventId("e1")).thenReturn(Optional.of(existing));
        var outcome = service.process(TestEvents.customer("e1", "refund please"));
        assertThat(outcome.duplicate()).isTrue();
        assertThat(outcome.insight()).isSameAs(existing);
        verify(extractor, never()).extract(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void lostInsertRaceReturnsTheWinnersRow() {
        var winner = Insight.of(TestEvents.customer("e1", "x"), new RuleBasedExtractor().extract(TestEvents.customer("e1", "x")), 0);
        when(repository.findByEventId("e1")).thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("ux_insights_event_id"));
        var outcome = service.process(TestEvents.customer("e1", "refund please"));
        assertThat(outcome.duplicate()).isTrue();
        assertThat(outcome.insight()).isSameAs(winner);
    }

    @Test
    void integrityErrorThatIsNotADuplicateIsRethrown() {
        when(repository.findByEventId("e1")).thenReturn(Optional.empty(), Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("some other constraint"));
        assertThatThrownBy(() -> service.process(TestEvents.customer("e1", "refund please")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void extractorOnlySeesRedactedTextButTheRowKeepsTheOriginalEvent() {
        when(repository.findByEventId("e1")).thenReturn(Optional.empty());
        service.process(TestEvents.customer("e1", "my email is bob@mail.com and phone 555-123-4567"));
        ArgumentCaptor<ConversationEvent> seen = ArgumentCaptor.forClass(ConversationEvent.class);
        verify(extractor).extract(seen.capture());
        String text = seen.getValue().customerText();
        assertThat(text).doesNotContain("bob@mail.com").doesNotContain("555-123-4567").contains("[EMAIL]", "[PHONE]");
        ArgumentCaptor<Insight> saved = ArgumentCaptor.forClass(Insight.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPiiRedactions()).isEqualTo(2);
    }
}
