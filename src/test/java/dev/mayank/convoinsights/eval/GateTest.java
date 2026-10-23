package dev.mayank.convoinsights.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GateTest {

    @TempDir
    Path dir;

    private Path baseline(double acc, long p95) throws Exception {
        Path f = dir.resolve("baseline.json");
        Gate.writeBaseline(f, "qwen2.5:3b@v1", "golden", 60, Map.of("intentAccuracy", acc, "intentMacroF1", 0.8,
                "latencyMsP95", p95, "fallbackRate", 0.0));
        return f;
    }

    private static Map<String, Object> run(double acc, long p95) {
        return Map.of("intentAccuracy", acc, "latencyMsP95", p95);
    }

    @Test
    void passesWithinLimits() throws Exception {
        var r = Gate.check(baseline(0.85, 4000), "qwen2.5:3b@v1", 60, run(0.81, 5900), 0.05, 0.5);
        assertThat(r.passed()).isTrue();
        assertThat(r.markdown()).contains("PASSED");
    }

    @Test
    void failsOnAccuracyRegression() throws Exception {
        var r = Gate.check(baseline(0.85, 4000), "qwen2.5:3b@v2", 60, run(0.70, 4000), 0.05, 0.5);
        assertThat(r.passed()).isFalse();
        assertThat(r.markdown()).contains("FAILED").contains("**regressed**").contains("compare with care");
    }

    @Test
    void failsOnLatencyRegression() throws Exception {
        var r = Gate.check(baseline(0.85, 4000), "qwen2.5:3b@v1", 60, run(0.86, 6100), 0.05, 0.5);
        assertThat(r.passed()).isFalse();
    }

    @Test
    void evenlySpacedSubsetCoversTheWholeList() {
        List<Integer> all = java.util.stream.IntStream.range(0, 200).boxed().toList();
        List<Integer> s = EvalMain.evenlySpaced(all, 60);
        assertThat(s).hasSize(60).startsWith(0).doesNotHaveDuplicates();
        assertThat(s.getLast()).isGreaterThan(190);
    }
}
