package dev.mayank.convoinsights.analytics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.mayank.convoinsights.api.InsightView;

/**
 * Sentiment counts for one brand in one time bucket. Built with add/subtract so that an updated or
 * re-delivered insight replaces its old contribution instead of being counted twice.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SentimentCounts {

    public long positive;
    public long neutral;
    public long negative;

    public SentimentCounts add(InsightView v) {
        return change(v, 1);
    }

    public SentimentCounts subtract(InsightView v) {
        return change(v, -1);
    }

    private SentimentCounts change(InsightView v, int delta) {
        switch (v.sentiment()) {
            case POSITIVE -> positive += delta;
            case NEUTRAL -> neutral += delta;
            case NEGATIVE -> negative += delta;
        }
        return this;
    }

    public long total() {
        return positive + neutral + negative;
    }

    public double negativeShare() {
        long t = total();
        return t == 0 ? 0 : (double) negative / t;
    }
}
