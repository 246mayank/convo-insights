package dev.mayank.convoinsights.eval;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsTest {

    private static Map<String, Object> row(String expected, String predicted, Long latency, String reason) {
        Map<String, Object> r = new HashMap<>();
        r.put("expectedIntent", expected);
        r.put("predictedIntent", predicted);
        r.put("latencyMs", latency);
        r.put("fallbackReason", reason);
        return r;
    }

    @Test
    void accuracyF1AndFallbacks() {
        var rows = List.of(
                row("A", "A", 100L, null),
                row("A", "B", 200L, "TIMEOUT"),
                row("B", "B", 300L, null),
                row("B", "B", 400L, null));
        var m = Metrics.compute(rows);
        assertThat(m.get("intentAccuracy")).isEqualTo(0.75);
        // A: p=1, r=0.5 -> 0.6667; B: p=0.6667, r=1 -> 0.8; mean 0.7333
        assertThat(m.get("intentMacroF1")).isEqualTo(0.7333);
        assertThat(m.get("fallbackRate")).isEqualTo(0.25);
        assertThat(m.get("fallbackByReason")).isEqualTo(Map.of("TIMEOUT", 1));
        assertThat(m.get("latencyMsP50")).isEqualTo(200L);
        assertThat(m.get("latencyMsP95")).isEqualTo(400L);
    }

    @Test
    void nearestRankPercentile() {
        var sorted = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(Metrics.percentile(sorted, 0.5)).isEqualTo(5L);
        assertThat(Metrics.percentile(sorted, 0.95)).isEqualTo(10L);
        assertThat(Metrics.percentile(List.of(42L), 0.95)).isEqualTo(42L);
    }
}
