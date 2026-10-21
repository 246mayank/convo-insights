package dev.mayank.convoinsights.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Accuracy, macro-F1, confusion, latency percentiles and fallback rates over eval rows. */
final class Metrics {

    private Metrics() {
    }

    static Map<String, Object> compute(List<Map<String, Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        int n = rows.size();
        m.put("n", n);

        long intentHits = rows.stream().filter(r -> Objects.equals(r.get("expectedIntent"), r.get("predictedIntent"))).count();
        m.put("intentAccuracy", ratio(intentHits, n));
        m.put("intentMacroF1", macroF1(rows));

        List<Map<String, Object>> withSentiment = rows.stream().filter(r -> r.get("expectedSentiment") != null).toList();
        if (!withSentiment.isEmpty()) {
            long hits = withSentiment.stream()
                    .filter(r -> Objects.equals(r.get("expectedSentiment"), r.get("predictedSentiment"))).count();
            m.put("sentimentAccuracy", ratio(hits, withSentiment.size()));
        }

        Map<String, Integer> fallbacks = new TreeMap<>();
        for (var r : rows) {
            Object reason = r.get("fallbackReason");
            if (reason != null) fallbacks.merge(reason.toString(), 1, Integer::sum);
        }
        int totalFallbacks = fallbacks.values().stream().mapToInt(Integer::intValue).sum();
        m.put("fallbackRate", ratio(totalFallbacks, n));
        m.put("fallbackByReason", fallbacks);

        List<Long> latencies = new ArrayList<>();
        for (var r : rows) {
            if (r.get("latencyMs") instanceof Number num) latencies.add(num.longValue());
        }
        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            m.put("latencyMsP50", percentile(latencies, 0.50));
            m.put("latencyMsP95", percentile(latencies, 0.95));
            m.put("latencyMsMax", latencies.getLast());
        }
        m.put("intentConfusion", confusion(rows));
        return m;
    }

    /** Macro-F1 over every class that appears as a gold label, so unused classes don't dilute it. */
    static double macroF1(List<Map<String, Object>> rows) {
        var classes = new TreeSet<String>();
        rows.forEach(r -> classes.add((String) r.get("expectedIntent")));
        double sum = 0;
        for (String c : classes) {
            long tp = rows.stream().filter(r -> c.equals(r.get("expectedIntent")) && c.equals(r.get("predictedIntent"))).count();
            long fp = rows.stream().filter(r -> !c.equals(r.get("expectedIntent")) && c.equals(r.get("predictedIntent"))).count();
            long fn = rows.stream().filter(r -> c.equals(r.get("expectedIntent")) && !c.equals(r.get("predictedIntent"))).count();
            double p = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
            double rc = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
            sum += p + rc == 0 ? 0 : 2 * p * rc / (p + rc);
        }
        return classes.isEmpty() ? 0 : round(sum / classes.size());
    }

    /** Nearest-rank percentile on a sorted list. */
    static long percentile(List<Long> sorted, double q) {
        int rank = (int) Math.ceil(q * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private static Map<String, Map<String, Integer>> confusion(List<Map<String, Object>> rows) {
        Map<String, Map<String, Integer>> c = new TreeMap<>();
        for (var r : rows) {
            c.computeIfAbsent((String) r.get("expectedIntent"), k -> new TreeMap<>())
                    .merge((String) r.get("predictedIntent"), 1, Integer::sum);
        }
        return c;
    }

    private static double ratio(long a, long b) {
        return b == 0 ? 0 : round((double) a / b);
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
