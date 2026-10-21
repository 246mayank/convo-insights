package dev.mayank.convoinsights.extract;

import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.ExtractionResult;
import dev.mayank.convoinsights.domain.Extractor;
import dev.mayank.convoinsights.domain.Intent;
import dev.mayank.convoinsights.domain.Sentiment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic keyword extractor. It is the fallback when the LLM answer is unusable, so it must
 * never throw and must give the same answer for the same input.
 */
@Component
public class RuleBasedExtractor implements InsightExtractor {

    static final int MAX_SUMMARY = 200;

    private static final Pattern MENTION = Pattern.compile("@\\w+");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern WORD = Pattern.compile("[a-z']+");

    // Insertion order breaks ties: more specific intents first.
    private static final Map<Intent, List<String>> INTENT_KEYWORDS = new LinkedHashMap<>();

    static {
        INTENT_KEYWORDS.put(Intent.REFUND, List.of("refund", "money back", "reimburse", "chargeback"));
        INTENT_KEYWORDS.put(Intent.CANCEL, List.of("cancellation fee", "early termination", "termination fee", "cancel fee"));
        INTENT_KEYWORDS.put(Intent.INVOICE, List.of("invoice", "bill", "billing", "receipt", "statement", "overcharged", "charged twice"));
        INTENT_KEYWORDS.put(Intent.PAYMENT, List.of("payment", "pay", "card", "paypal", "declined", "credit"));
        INTENT_KEYWORDS.put(Intent.SHIPPING, List.of("shipping address", "address", "ship to"));
        INTENT_KEYWORDS.put(Intent.DELIVERY, List.of("delivery", "deliver", "delivered", "package", "parcel", "arrive", "courier", "tracking"));
        INTENT_KEYWORDS.put(Intent.ORDER, List.of("order", "purchase", "bought", "cancel my order", "checkout"));
        INTENT_KEYWORDS.put(Intent.SUBSCRIPTION, List.of("subscription", "subscribe", "unsubscribe", "newsletter", "membership", "prime", "plan"));
        INTENT_KEYWORDS.put(Intent.ACCOUNT, List.of("account", "password", "login", "log in", "sign in", "username", "locked out", "profile", "register"));
        INTENT_KEYWORDS.put(Intent.TECHNICAL_SUPPORT, List.of("outage", "not working", "doesn't work", "won't", "crash", "error", "bug", "update",
                "wifi", "signal", "internet", "service down", "slow", "battery", "app", "screen", "connect", "broken"));
        INTENT_KEYWORDS.put(Intent.CONTACT, List.of("speak to", "talk to", "human", "agent", "representative", "customer service", "call me", "phone number", "dm"));
        INTENT_KEYWORDS.put(Intent.FEEDBACK, List.of("complaint", "review", "feedback", "worst", "terrible", "great service", "thank", "love"));
    }

    private static final Set<String> NEGATIVE = Set.of(
            "not", "no", "never", "worst", "terrible", "awful", "horrible", "bad", "angry", "annoyed", "frustrated",
            "frustrating", "ridiculous", "useless", "disappointed", "disappointing", "hate", "broken", "fail", "failed",
            "waiting", "still", "wrong", "problem", "issue", "unacceptable", "rude", "poor", "slow", "can't", "cannot",
            "won't", "doesn't", "didn't", "isn't");
    private static final Set<String> POSITIVE = Set.of(
            "thanks", "thank", "great", "awesome", "love", "amazing", "excellent", "perfect", "happy", "helpful",
            "appreciate", "resolved", "fixed", "good", "best", "wonderful", "glad");

    @Override
    public ExtractionResult extract(ConversationEvent event) {
        String customer = clean(event.customerText());
        String lower = customer.toLowerCase(Locale.ROOT);
        Intent intent = classify(lower);
        Sentiment sentiment = sentiment(lower);
        double confidence = intent == Intent.OTHER ? 0.1 : 0.4;
        return new ExtractionResult(summarize(customer), sentiment, intent, confidence, Extractor.RULES,
                null, null, null, null);
    }

    static Intent classify(String lower) {
        Intent best = Intent.OTHER;
        int bestScore = 0;
        for (Map.Entry<Intent, List<String>> e : INTENT_KEYWORDS.entrySet()) {
            int score = 0;
            for (String kw : e.getValue()) {
                if (containsWord(lower, kw)) {
                    score += kw.contains(" ") ? 2 : 1; // phrases are stronger evidence than single words
                }
            }
            if (score > bestScore) {
                best = e.getKey();
                bestScore = score;
            }
        }
        return best;
    }

    static Sentiment sentiment(String lower) {
        int score = 0;
        var m = WORD.matcher(lower);
        while (m.find()) {
            String w = m.group();
            if (NEGATIVE.contains(w)) score--;
            if (POSITIVE.contains(w)) score++;
        }
        if (score <= -1) return Sentiment.NEGATIVE;
        if (score >= 1) return Sentiment.POSITIVE;
        return Sentiment.NEUTRAL;
    }

    static String summarize(String customer) {
        String s = customer.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "(no customer text)";
        int end = firstSentenceEnd(s);
        if (end > 0 && end < s.length()) s = s.substring(0, end);
        return s.length() <= MAX_SUMMARY ? s : s.substring(0, MAX_SUMMARY - 3).trim() + "...";
    }

    private static int firstSentenceEnd(String s) {
        for (int i = 20; i < s.length(); i++) { // skip very short leading fragments like "Hi!"
            char c = s.charAt(i);
            if ((c == '.' || c == '?' || c == '!') && (i + 1 == s.length() || s.charAt(i + 1) == ' ')) {
                return i + 1;
            }
        }
        return -1;
    }

    static String clean(String text) {
        return URL.matcher(MENTION.matcher(text).replaceAll("")).replaceAll("").trim();
    }

    private static boolean containsWord(String haystack, String kw) {
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(kw, from);
            if (idx < 0) return false;
            boolean startOk = idx == 0 || !Character.isLetter(haystack.charAt(idx - 1));
            int end = idx + kw.length();
            boolean endOk = end == haystack.length() || !Character.isLetter(haystack.charAt(end));
            if (startOk && endOk) return true;
            from = idx + 1;
        }
    }
}
