package dev.mayank.convoinsights.privacy;

import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.Turn;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces personal data with typed placeholders before any text leaves the service for a model.
 *
 * <p>Rules run in a fixed order, each one on the output of the previous one, so a specific match is
 * never half-eaten by a general one: emails before handles (the {@code @}), handles before phones
 * (TWCS user handles are long numbers), cards before phones and ids. Each rule is a regex for
 * candidates plus a check that decides whether a candidate really is personal data.
 *
 * <p>Rules were tuned by auditing what they changed across 20,000 real TWCS conversations
 * (bench/redaction-audit-*.md): numeric handles were mistaken for phones, Amazon order numbers
 * passed the Luhn check and were tagged as cards, UK numbers were only partly redacted,
 * hashtags were taken for reference ids, and company toll-free numbers were removed although they
 * belong to the business, not the customer.
 */
@Component
public class PiiRedactor {

    public enum PiiType {
        EMAIL, URL, HANDLE, CARD, PHONE, ID, NAME
    }

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    private static final Pattern URL = Pattern.compile("(?:https?://|www\\.)\\S+");
    private static final Pattern HANDLE = Pattern.compile("(?<![\\w@])@\\w{1,30}");
    /** Card numbers as people write them: 13-19 digits in a row, or groups of four separated by spaces or dashes. */
    private static final Pattern CARD = Pattern.compile("(?<![\\d-])(?:\\d{4}(?:[ -]\\d{4}){2,3}(?:[ -]?\\d{1,3})?|\\d{13,19})(?![\\d-])");
    /** Phone candidates: optional +country code, optional (area code), then 2-5 digit groups; checked for 9-15 digits. */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w#-])(?:\\+\\d{1,3}[ .-]?)?(?:\\(\\d{2,5}\\)[ .-]?)?\\d{1,5}(?:[ .-]?\\d{2,5}){1,4}(?![\\w-])");
    /** Safety net: any 9+ digit run that survived the rules above, even glued to letters ("number4026785388"). */
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d(?:[ .-]?\\d){8,}(?!\\d)");
    /** Order, account, booking or tracking references: 8+ capitals/digits/dashes with at least one digit, optional leading #. */
    private static final Pattern ID = Pattern.compile("(?<![\\w-])#?(?=[A-Z#-]*\\d)[A-Z0-9-]{8,}(?![\\w-])");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** A first name right after a greeting or thanks: "Hi Becca", "Thanks, Sam", "Dear John". */
    private static final Pattern NAME = Pattern.compile(
            "\\b(?:Hi|Hello|Hey|Dear|Thanks|Thank you|Sorry|Morning|Evening)(?:,)? ([A-Z][a-z]{1,20})\\b");
    private static final Set<String> NOT_NAMES = Set.of("There", "All", "Everyone", "Team", "Folks", "Guys", "Again",
            "Friend", "Friends", "Sir", "Madam", "Mam", "Ma", "So", "For", "You", "Very", "Much", "Love",
            "Mate", "Buddy", "Customer", "Support", "Delta", "Amazon", "Apple", "Uber", "Lyft", "Spotify");
    /** Toll-free business numbers (North America, UK): public, and useful context for the model. */
    private static final Pattern TOLL_FREE = Pattern.compile("^(?:1?8(?:00|33|44|55|66|77|88)\\d{7}|08(?:00|08)\\d{6,7})$");

    public Redacted redact(ConversationEvent event) {
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        List<Turn> turns = event.turns().stream()
                .map(t -> new Turn(t.speaker(), redact(t.text(), counts), t.ts()))
                .toList();
        var redacted = event.withTurns(turns);
        return new Redacted(redacted, Map.copyOf(counts));
    }

    public String redact(String text, Map<PiiType, Integer> counts) {
        if (text == null || text.isEmpty()) return text;
        String s = replace(EMAIL, text, PiiType.EMAIL, counts, m -> true);
        s = replace(URL, s, PiiType.URL, counts, m -> true);
        s = replace(HANDLE, s, PiiType.HANDLE, counts, m -> true);
        s = replace(CARD, s, PiiType.CARD, counts, m -> luhn(digits(m)));
        s = replace(PHONE, s, PiiType.PHONE, counts, PiiRedactor::isPersonalPhone);
        s = replace(ID, s, PiiType.ID, counts, PiiRedactor::isReference);
        s = replace(LONG_NUMBER, s, PiiType.ID, counts, PiiRedactor::isLongIdentifier);
        s = replaceNames(s, counts);
        return s;
    }

    static boolean isPersonalPhone(String candidate) {
        String d = digits(candidate);
        if (d.length() < 9 || d.length() > 15) return false;  // too short to be a phone, or a long id the ID rule handles
        return !TOLL_FREE.matcher(d).matches();
    }

    static boolean isReference(String candidate) {
        if (ISO_DATE.matcher(candidate).matches()) return false;          // 2017-10-31
        if (candidate.startsWith("#") && candidate.chars().anyMatch(Character::isLetter)) return false; // #CSWEEK2017
        if (TOLL_FREE.matcher(digits(candidate)).matches()) return false;  // left alone by the phone rule on purpose
        return true;
    }

    static boolean isLongIdentifier(String candidate) {
        String d = digits(candidate);
        return d.length() >= 9 && !TOLL_FREE.matcher(d).matches();
    }

    private static String replace(Pattern p, String s, PiiType type, Map<PiiType, Integer> counts, Predicate<String> isPii) {
        Matcher m = p.matcher(s);
        StringBuilder out = new StringBuilder();
        int n = 0;
        while (m.find()) {
            if (isPii.test(m.group())) {
                m.appendReplacement(out, Matcher.quoteReplacement("[" + type + "]"));
                n++;
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(out);
        if (n > 0) counts.merge(type, n, Integer::sum);
        return out.toString();
    }

    private static String replaceNames(String s, Map<PiiType, Integer> counts) {
        Matcher m = NAME.matcher(s);
        StringBuilder out = new StringBuilder();
        int n = 0;
        while (m.find()) {
            String whole = m.group();
            if (NOT_NAMES.contains(m.group(1))) {
                m.appendReplacement(out, Matcher.quoteReplacement(whole));
            } else {
                String greeting = whole.substring(0, m.start(1) - m.start());
                m.appendReplacement(out, Matcher.quoteReplacement(greeting + "[NAME]"));
                n++;
            }
        }
        m.appendTail(out);
        if (n > 0) counts.merge(PiiType.NAME, n, Integer::sum);
        return out.toString();
    }

    private static String digits(String s) {
        return s.replaceAll("\\D", "");
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
