package dev.mayank.convoinsights.privacy;

import dev.mayank.convoinsights.TestEvents;
import dev.mayank.convoinsights.privacy.PiiRedactor.PiiType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor();

    private String redact(String s) {
        return redactor.redact(s, new EnumMap<>(PiiType.class));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "mail me at jane.doe+help@example.co.uk thanks|mail me at [EMAIL] thanks",
            "see https://t.co/AbC123 now|see [URL] now",
            "card 4111 1111 1111 1111 was charged|card [CARD] was charged",
            "call 555-123-4567 or (555) 123-4567|call [PHONE] or [PHONE]",
            "call +1 415 555 0132 today|call [PHONE] today",
            "order A10293384 is late|order [ID] is late",
            "ref ORD-558120 please|ref [ID] please",
            "@AmazonHelp @115712 hi|[HANDLE] [HANDLE] hi"
    })
    void redactsEachType(String input, String expected) {
        assertThat(redact(input)).isEqualTo(expected);
    }

    @Test
    void leavesOrdinaryTextAlone() {
        String s = "I waited 45 minutes on the 3rd floor, it's 2017 and the iPhone X still won't charge.";
        assertThat(redact(s)).isEqualTo(s);
    }

    @Test
    void numberThatFailsLuhnIsNotACard() {
        // 16 digits, not a valid card number: redacted as a generic ID, not as CARD
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        String out = redactor.redact("tracking 1234567812345678", counts);
        assertThat(out).isEqualTo("tracking [ID]");
        assertThat(counts).doesNotContainKey(PiiType.CARD).containsEntry(PiiType.ID, 1);
    }

    @Test
    void emailIsNotSplitIntoAHandle() {
        assertThat(redact("bob@mail.com")).isEqualTo("[EMAIL]");
    }

    @Test
    void luhn() {
        assertThat(PiiRedactor.luhn("4111111111111111")).isTrue();
        assertThat(PiiRedactor.luhn("4111111111111112")).isFalse();
    }

    @Test
    void redactsEveryTurnAndCounts() {
        var event = TestEvents.thread("e1", "I'm bob@mail.com, call 555-123-4567", "@115712 please DM us");
        var r = redactor.redact(event);
        assertThat(r.event().turns().get(0).text()).isEqualTo("I'm [EMAIL], call [PHONE]");
        assertThat(r.event().turns().get(1).text()).isEqualTo("[HANDLE] please DM us");
        assertThat(r.counts()).containsEntry(PiiType.EMAIL, 1).containsEntry(PiiType.PHONE, 1).containsEntry(PiiType.HANDLE, 1);
        assertThat(r.total()).isEqualTo(3);
        assertThat(r.event().eventId()).isEqualTo("e1");
    }
}
