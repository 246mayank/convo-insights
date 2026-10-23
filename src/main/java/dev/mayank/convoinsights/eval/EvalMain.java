package dev.mayank.convoinsights.eval;

import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.ExtractionResult;
import dev.mayank.convoinsights.extract.InsightExtractor;
import dev.mayank.convoinsights.extract.RuleBasedExtractor;
import dev.mayank.convoinsights.extract.llm.LlmInsightExtractor;
import dev.mayank.convoinsights.extract.llm.LlmOutputValidator;
import dev.mayank.convoinsights.extract.llm.OllamaClient;
import dev.mayank.convoinsights.extract.llm.Prompts;
import dev.mayank.convoinsights.privacy.PiiRedactor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline evaluation: runs an extractor over a labelled set and writes per-row results plus
 * summary metrics as JSON. Deliberately not a Spring app, so it runs anywhere (CI included)
 * without Kafka or Postgres. Usage (all flags optional except --dataset):
 * <pre>
 *   --dataset=golden:eval/golden.jsonl | twcs:eval/twcs-labels.jsonl:data/conversations.jsonl
 *   --extractor=rules|ollama --model=qwen2.5:1.5b --prompt=v1 --limit=0 --out=eval/results/x.json
 * </pre>
 */
public final class EvalMain {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public static void main(String[] argv) throws IOException {
        Map<String, String> args = parseArgs(argv);
        String extractorName = args.getOrDefault("extractor", "rules");
        String model = args.getOrDefault("model", "qwen2.5:3b");
        String prompt = args.getOrDefault("prompt", "v1");
        int limit = Integer.parseInt(args.getOrDefault("limit", "0"));
        String dataset = required(args, "dataset");

        List<LabelledRow> rows = load(dataset);
        if (limit > 0 && rows.size() > limit) rows = rows.subList(0, limit);
        int subset = Integer.parseInt(args.getOrDefault("subset", "0"));
        if (subset > 0 && rows.size() > subset) rows = evenlySpaced(rows, subset);

        var rules = new RuleBasedExtractor();
        InsightExtractor extractor = switch (extractorName) {
            case "rules" -> rules;
            case "ollama" -> new LlmInsightExtractor(
                    new OllamaClient(args.getOrDefault("ollama-url", "http://localhost:11434"), model,
                            Duration.ofSeconds(Long.parseLong(args.getOrDefault("timeout-s", "30"))), 2048, 160),
                    new LlmOutputValidator(MAPPER), rules, Prompts.load(prompt), prompt, 2000);
            default -> throw new IllegalArgumentException("unknown --extractor " + extractorName);
        };
        var redactor = new PiiRedactor();

        List<Map<String, Object>> results = new ArrayList<>();
        long started = System.nanoTime();
        for (int i = 0; i < rows.size(); i++) {
            LabelledRow row = rows.get(i);
            ExtractionResult r = extractor.extract(redactor.redact(row.event()).event());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("eventId", row.event().eventId());
            out.put("expectedIntent", row.intent());
            out.put("predictedIntent", r.intent().name());
            out.put("expectedSentiment", row.sentiment());
            out.put("predictedSentiment", r.sentiment().name());
            out.put("extractor", r.extractor().name());
            out.put("fallbackReason", r.fallbackReason() == null ? null : r.fallbackReason().name());
            out.put("latencyMs", r.llmLatencyMs());
            out.put("summary", r.summary());
            results.add(out);
            if ((i + 1) % 20 == 0) System.err.printf("%d/%d%n", i + 1, rows.size());
        }
        double wallSeconds = (System.nanoTime() - started) / 1e9;

        String setName = dataset.substring(0, dataset.indexOf(':'));
        String runName = extractorName.equals("rules") ? "rules" : model + "@" + prompt;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run", runName);
        report.put("dataset", setName);
        report.put("extractor", extractorName);
        report.put("model", extractorName.equals("rules") ? null : model);
        report.put("promptVersion", extractorName.equals("rules") ? null : prompt);
        report.put("createdAt", Instant.now().toString());
        report.put("wallSeconds", Math.round(wallSeconds * 10) / 10.0);
        report.put("metrics", Metrics.compute(results));
        report.put("rows", results);

        Path out = Path.of(args.getOrDefault("out",
                "eval/results/" + setName + "-" + runName.replace(':', '_').replace('@', '_') + ".json"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
        System.err.println("wrote " + out + " " + MAPPER.writeValueAsString(report.get("metrics")));

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) report.get("metrics");
        if (args.containsKey("write-baseline")) {
            Gate.writeBaseline(Path.of(args.get("write-baseline")), runName, setName, rows.size(), metrics);
        }
        if (args.containsKey("baseline")) {
            Gate.Result gate = Gate.check(Path.of(args.get("baseline")), runName, rows.size(), metrics,
                    Double.parseDouble(args.getOrDefault("max-accuracy-drop", "0.05")),
                    Double.parseDouble(args.getOrDefault("max-p95-increase", "0.5")));
            System.out.println(gate.markdown());
            if (!gate.passed()) System.exit(1);
        }
    }

    /** n rows spread evenly over the list, so a small CI subset still covers every category of a sorted file. */
    static <T> List<T> evenlySpaced(List<T> all, int n) {
        List<T> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(all.get((int) ((long) i * all.size() / n)));
        return out;
    }

    record LabelledRow(ConversationEvent event, String intent, String sentiment) {
    }

    static List<LabelledRow> load(String spec) throws IOException {
        String[] parts = spec.split(":");
        return switch (parts[0]) {
            case "golden" -> loadGolden(Path.of(parts[1]));
            case "twcs" -> loadTwcs(Path.of(parts[1]), Path.of(parts[2]));
            default -> throw new IllegalArgumentException("unknown dataset kind " + parts[0]);
        };
    }

    private static List<LabelledRow> loadGolden(Path file) throws IOException {
        List<LabelledRow> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode n = MAPPER.readTree(line);
            rows.add(new LabelledRow(MAPPER.treeToValue(n.get("event"), ConversationEvent.class),
                    n.get("expected").get("intent").stringValue(), null));
        }
        return rows;
    }

    /** Hand labels hold only ids; the conversation text is joined from the local (uncommitted) replay file. */
    private static List<LabelledRow> loadTwcs(Path labels, Path events) throws IOException {
        Map<String, ConversationEvent> byId = new HashMap<>();
        try (var lines = Files.lines(events, StandardCharsets.UTF_8)) {
            lines.filter(l -> !l.isBlank()).forEach(l -> {
                ConversationEvent e = MAPPER.readValue(l, ConversationEvent.class);
                byId.put(e.eventId(), e);
            });
        }
        List<LabelledRow> rows = new ArrayList<>();
        for (String line : Files.readAllLines(labels, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode n = MAPPER.readTree(line);
            if (n.path("unsure").asBoolean(false)) continue;
            ConversationEvent e = byId.get(n.get("eventId").stringValue());
            if (e == null) throw new IllegalStateException("label for unknown eventId " + n.get("eventId"));
            rows.add(new LabelledRow(e, n.get("intent").stringValue(), n.get("sentiment").stringValue()));
        }
        return rows;
    }

    private static Map<String, String> parseArgs(String[] argv) {
        Map<String, String> m = new HashMap<>();
        for (String a : argv) {
            if (!a.startsWith("--") || !a.contains("=")) throw new IllegalArgumentException("expected --key=value: " + a);
            m.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
        }
        return m;
    }

    private static String required(Map<String, String> args, String key) {
        String v = args.get(key);
        if (v == null) throw new IllegalArgumentException("missing --" + key);
        return v;
    }
}
