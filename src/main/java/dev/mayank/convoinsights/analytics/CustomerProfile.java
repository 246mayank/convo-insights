package dev.mayank.convoinsights.analytics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.mayank.convoinsights.api.InsightView;
import dev.mayank.convoinsights.domain.Sentiment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Segment-style computed traits for one customer, maintained incrementally from their insights:
 * how many conversations, how many negative, what they last contacted support about, and whether
 * they look at risk (at least 2 of their 3 most recent conversations were negative).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomerProfile {

    static final int RECENT = 5;

    public String customerId;
    public long conversations;
    public long negative;
    /** Most recent conversations first, at most {@value #RECENT}. */
    public List<Recent> recent = new ArrayList<>();

    public record Recent(String eventId, Instant startedAt, String brand, String intent, String sentiment) {
    }

    public CustomerProfile add(InsightView v) {
        customerId = v.customerId();
        conversations++;
        if (v.sentiment() == Sentiment.NEGATIVE) negative++;
        recent.removeIf(r -> r.eventId().equals(v.eventId()));
        recent.add(new Recent(v.eventId(), v.startedAt(), v.brand(), v.intent().name(), v.sentiment().name()));
        recent.sort(Comparator.comparing(Recent::startedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        if (recent.size() > RECENT) recent = new ArrayList<>(recent.subList(0, RECENT));
        return this;
    }

    public CustomerProfile subtract(InsightView v) {
        conversations--;
        if (v.sentiment() == Sentiment.NEGATIVE) negative--;
        recent.removeIf(r -> r.eventId().equals(v.eventId()));
        return this;
    }

    public String getLastIntent() {
        return recent.isEmpty() ? null : recent.getFirst().intent();
    }

    public Instant getLastSeen() {
        return recent.isEmpty() ? null : recent.getFirst().startedAt();
    }

    public boolean isAtRisk() {
        return recent.stream().limit(3).filter(r -> Sentiment.NEGATIVE.name().equals(r.sentiment())).count() >= 2;
    }
}
