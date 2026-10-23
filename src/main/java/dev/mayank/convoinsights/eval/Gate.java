package dev.mayank.convoinsights.eval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pre-merge regression gate: compares an eval run with the committed baseline and fails when
 * intent accuracy drops by more than {@code maxAccuracyDrop} (absolute) or p95 model latency grows
 * by more than {@code maxP95Increase} (relative). The baseline is produced on the same CI runner
 * type, because latency on a laptop says nothing about latency on the runner.
 */
final class Gate {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Gate() {
    }

    record Result(boolean passed, String markdown) {
    }

    static void writeBaseline(Path file, String run, String dataset, int rows, Map<String, Object> metrics) throws IOException {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("run", run);
        b.put("dataset", dataset);
        b.put("rows", rows);
        b.put("intentAccuracy", metrics.get("intentAccuracy"));
        b.put("intentMacroF1", metrics.get("intentMacroF1"));
        b.put("latencyMsP95", metrics.get("latencyMsP95"));
        b.put("fallbackRate", metrics.get("fallbackRate"));
        b.put("createdAt", Instant.now().toString());
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), b);
        System.err.println("wrote baseline " + file);
    }

    static Result check(Path baselineFile, String run, int rows, Map<String, Object> metrics,
                        double maxAccuracyDrop, double maxP95Increase) throws IOException {
        JsonNode base = MAPPER.readTree(Files.readString(baselineFile));
        double baseAcc = base.get("intentAccuracy").asDouble();
        double acc = ((Number) metrics.get("intentAccuracy")).doubleValue();
        long baseP95 = base.get("latencyMsP95").asLong();
        long p95 = metrics.get("latencyMsP95") instanceof Number n ? n.longValue() : 0;

        boolean accOk = acc >= baseAcc - maxAccuracyDrop;
        boolean latOk = baseP95 == 0 || p95 <= baseP95 * (1 + maxP95Increase);
        boolean sameSetup = base.get("rows").asInt() == rows && base.get("run").asString().equals(run);
        boolean passed = accOk && latOk;

        String md = """
                ### Regression gate: %s

                | | Baseline | This change | Limit | |
                |---|---:|---:|---:|---|
                | Intent accuracy | %.1f%% | %.1f%% | >= %.1f%% | %s |
                | p95 model latency | %d ms | %d ms | <= %d ms | %s |

                Run `%s` on %d golden rows (baseline: `%s`, %d rows).%s
                """.formatted(passed ? "PASSED" : "FAILED",
                100 * baseAcc, 100 * acc, 100 * (baseAcc - maxAccuracyDrop), accOk ? "ok" : "**regressed**",
                baseP95, p95, Math.round(baseP95 * (1 + maxP95Increase)), latOk ? "ok" : "**regressed**",
                run, rows, base.get("run").asString(), base.get("rows").asInt(),
                sameSetup ? "" : "\n\nNote: model, prompt or row count differ from the baseline; compare with care.");
        return new Result(passed, md);
    }
}
