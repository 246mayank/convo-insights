package dev.mayank.convoinsights.privacy;

import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.Turn;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces personal data with typed placeholders before any text leaves the service for a model.
 * Patterns run in a fixed order so a more specific match (an email) is not half-eaten by a more
 * general one (a handle). Card numbers are only redacted when they pass the Luhn check, so
 * ordinary long numbers (tracking numbers, timestamps) go to the ID rule instead.
 */
@Component
public class PiiRedactor {

    public enum PiiType {
        EMAIL, URL, CARD, PHONE, ID, HANDLE
    }

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    private static final Pattern URL = Pattern.compile("(?:https?://|www\\.)\\S+");
    private static final Pattern CARD = Pattern.compile("(?<![\\d-])(?:\\d[ -]?){12,18}\\d(?![\\d-])");
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w-])(?:\\+?\\d{1,3}[ .-]?)?(?:\\(\\d{3}\\)|\\d{3})[ .-]?\\d{3}[ .-]?\\d{4}(?![\\w-])");
    // Order, account or booking references: 8+ chars of capitals/digits/dashes containing at least one digit.
    private static final Pattern ID = Pattern.compile("(?<![\\w-])(?=[A-Z-]*\\d)[A-Z0-9-]{8,}(?![\\w-])");
    private static final Pattern HANDLE = Pattern.compile("(?<![\\w@])@\\w{1,30}");

    public Redacted redact(ConversationEvent event) {
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        List<Turn> turns = event.turns().stream()
                .map(t -> new Turn(t.speaker(), redact(t.text(), counts), t.ts()))
                .toList();
        var redacted = new ConversationEvent(event.eventId(), event.conversationId(), event.brand(),
                event.source(), turns);
        return new Redacted(redacted, Map.copyOf(counts));
    }

    public String redact(String text, Map<PiiType, Integer> counts) {
        if (text == null || text.isEmpty()) return text;
        String s = replace(EMAIL, text, PiiType.EMAIL, counts);
        s = replace(URL, s, PiiType.URL, counts);
        s = replaceCards(s, counts);
        s = replace(PHONE, s, PiiType.PHONE, counts);
        s = replace(ID, s, PiiType.ID, counts);
        s = replace(HANDLE, s, PiiType.HANDLE, counts);
        return s;
    }

    private static String replace(Pattern p, String s, PiiType type, Map<PiiType, Integer> counts) {
        Matcher m = p.matcher(s);
        StringBuilder out = new StringBuilder();
        int n = 0;
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement("[" + type + "]"));
            n++;
        }
        m.appendTail(out);
        if (n > 0) counts.merge(type, n, Integer::sum);
        return out.toString();
    }

    private static String replaceCards(String s, Map<PiiType, Integer> counts) {
        Matcher m = CARD.matcher(s);
        StringBuilder out = new StringBuilder();
        int n = 0;
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && luhn(digits)) {
                m.appendReplacement(out, "[CARD]");
                n++;
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(out);
        if (n > 0) counts.merge(PiiType.CARD, n, Integer::sum);
        return out.toString();
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    public record Redacted(ConversationEvent event, Map<PiiType, Integer> counts) {
        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
