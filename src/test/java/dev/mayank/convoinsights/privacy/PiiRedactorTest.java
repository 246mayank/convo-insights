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

    /** Cases found by auditing the redactor over 20,000 real TWCS conversations. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // numeric TWCS handles were mistaken for phone numbers
            "@8005551234 my wifi is down|[HANDLE] my wifi is down",
            // Amazon order numbers pass Luhn by chance; they are references, not cards
            "order 402-8073552-0393941 never came|order [ID] never came",
            // UK numbers were only partly redacted ([PHONE] 493 0787)
            "call me on 0345 493 0787 please|call me on [PHONE] please",
            "my number is +44 20 7946 0958|my number is [PHONE]",
            // a 1-digit area code after the country code (Thailand)
            "call +66 2 515 9999 for help|call [PHONE] for help",
            // long numbers glued to letters survived the word-boundary rules
            "agent (Rep ID: i1281873914) was rude|agent (Rep ID: i[ID]) was rude",
            "order number4026785388-1769969 is late|order number[ID] is late",
            // company toll-free numbers are public and useful context
            "please call us at 1-800-869-3557, option 2|please call us at 1-800-869-3557, option 2",
            "ring 0800 123 4567 any time|ring 0800 123 4567 any time",
            // hashtags are not reference ids; '#' + digits is (a ticket number)
            "#CSWEEK2017 was great, ticket #17098456|#CSWEEK2017 was great, ticket [ID]",
            // dates are not reference ids
            "since 2017-10-31 nothing works|since 2017-10-31 nothing works",
            // names after a greeting
            "Hi Becca, please send us a DM|Hi [NAME], please send us a DM",
            "Thanks, Sam! Sorted now|Thanks, [NAME]! Sorted now",
            "Hi there, how can we help?|Hi there, how can we help?"
    })
    void auditFindings(String input, String expected) {
        assertThat(redact(input)).isEqualTo(expected);
    }

    @Test
    void groupedCardNumberStillRedactedAndCountedAsCard() {
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        assertThat(redactor.redact("charged on 4111-1111-1111-1111 twice", counts)).isEqualTo("charged on [CARD] twice");
        assertThat(counts).containsEntry(PiiType.CARD, 1).doesNotContainKey(PiiType.PHONE);
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
