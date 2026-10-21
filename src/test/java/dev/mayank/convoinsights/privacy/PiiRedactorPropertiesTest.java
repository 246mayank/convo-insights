package dev.mayank.convoinsights.privacy;

import dev.mayank.convoinsights.privacy.PiiRedactor.PiiType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.EnumMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Property-based checks: thousands of generated inputs instead of a few hand-picked ones. */
class PiiRedactorPropertiesTest {

    private final PiiRedactor redactor = new PiiRedactor();

    private String redact(String s) {
        return redactor.redact(s, new EnumMap<>(PiiType.class));
    }

    @Property(tries = 500)
    void generatedEmailsNeverSurvive(@ForAll("prose") String before, @ForAll("emails") String email, @ForAll("prose") String after) {
        String out = redact(before + " " + email + " " + after);
        assertThat(out).doesNotContain(email).contains("[EMAIL]");
    }

    @Property(tries = 500)
    void luhnValidCardNumbersAreAlwaysRedacted(@ForAll("cards") String card, @ForAll("prose") String before) {
        String out = redact(before + " card " + card + " please");
        assertThat(out).doesNotContain(card).contains("[CARD]");
    }

    @Property(tries = 1000)
    void redactionIsIdempotent(@ForAll String anything) {
        String once = redact(anything);
        assertThat(redact(once)).isEqualTo(once);
    }

    @Property(tries = 500)
    void plainProseIsUntouched(@ForAll("prose") String prose) {
        assertThat(redact(prose)).isEqualTo(prose);
    }

    @Provide
    Arbitrary<String> prose() {
        return Arbitraries.strings().withCharRange('a', 'z').withChars(' ', ',', '.', '!', '?')
                .ofMinLength(0).ofMaxLength(60);
    }

    @Provide
    Arbitrary<String> emails() {
        Arbitrary<String> local = Arbitraries.strings().withCharRange('a', 'z').withChars('.', '_', '+').ofMinLength(1).ofMaxLength(12)
                .filter(s -> Character.isLetter(s.charAt(0)) && Character.isLetter(s.charAt(s.length() - 1)));
        Arbitrary<String> domain = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(2).ofMaxLength(10);
        Arbitrary<String> tld = Arbitraries.of("com", "org", "co.uk", "in", "io");
        return Combinators.combine(local, domain, tld).as((l, d, t) -> l + "@" + d + "." + t);
    }

    /** 16-digit numbers with a correct Luhn check digit, optionally grouped in fours. */
    @Provide
    Arbitrary<String> cards() {
        Arbitrary<String> body = Arbitraries.strings().numeric().ofLength(15).filter(s -> s.charAt(0) != '0');
        return Combinators.combine(body, Arbitraries.of(true, false)).as((b, grouped) -> {
            String digits = b + checkDigit(b);
            return grouped ? digits.replaceAll("(\\d{4})(?=\\d)", "$1 ") : digits;
        });
    }

    private static int checkDigit(String body) {
        for (int d = 0; d <= 9; d++) {
            if (PiiRedactor.luhn(body + d)) return d;
        }
        throw new IllegalStateException();
    }
}
