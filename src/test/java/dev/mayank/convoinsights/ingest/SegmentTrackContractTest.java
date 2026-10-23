package dev.mayank.convoinsights.ingest;

import dev.mayank.convoinsights.TestEvents;
import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.Insight;
import dev.mayank.convoinsights.extract.RuleBasedExtractor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Track call we publish must satisfy {@code contracts/segment-track.schema.json}: every required
 * field present, enums and ranges respected. Checked against the schema file itself, so changing
 * the contract without changing the mapper (or the other way round) fails the build.
 */
class SegmentTrackContractTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private JsonNode schema() throws Exception {
        return mapper.readTree(Files.readString(Path.of("contracts/segment-track.schema.json")));
    }

    private JsonNode track(ConversationEvent e) {
        Insight i = Insight.of(e, new RuleBasedExtractor().extract(e), 1);
        return mapper.valueToTree(SegmentTrack.of(i));
    }

    @Test
    void knownCustomerIsIdentifiedByUserId() throws Exception {
        var base = TestEvents.customer("e1", "I want a refund");
        var e = new ConversationEvent(base.eventId(), base.conversationId(), base.brand(), base.source(), base.turns(),
                "sms", "tw-0123456789abcdef", Instant.parse("2026-10-23T10:00:00Z"));
        JsonNode t = track(e);
        assertThat(violations(schema(), t)).isEmpty();
        assertThat(t.get("userId").stringValue()).isEqualTo("tw-0123456789abcdef");
        assertThat(t.has("anonymousId")).isFalse();
        assertThat(t.get("messageId").stringValue()).isEqualTo("e1");
        assertThat(t.get("timestamp").stringValue()).isEqualTo("2026-10-23T10:00:00Z");
        assertThat(t.at("/properties/channel").stringValue()).isEqualTo("sms");
    }

    @Test
    void unknownCustomerFallsBackToAnonymousId() throws Exception {
        JsonNode t = track(TestEvents.customer("e2", "the app crashes"));
        assertThat(violations(schema(), t)).isEmpty();
        assertThat(t.has("userId")).isFalse();
        assertThat(t.get("anonymousId").stringValue()).isEqualTo("conv-e2");
    }

    /** A small checker for the parts of the schema we use: required, const, enum, number ranges, oneOf-required. */
    private static List<String> violations(JsonNode schema, JsonNode doc) {
        List<String> out = new ArrayList<>();
        check(schema, doc, "", out);
        JsonNode oneOf = schema.get("oneOf");
        long matches = 0;
        for (JsonNode alt : oneOf) {
            boolean ok = true;
            for (JsonNode r : alt.get("required")) ok &= doc.has(r.stringValue());
            if (ok) matches++;
        }
        if (matches != 1) out.add("exactly one of userId / anonymousId must be present, found " + matches);
        return out;
    }

    private static void check(JsonNode schema, JsonNode doc, String path, List<String> out) {
        if (schema.has("required")) {
            for (JsonNode r : schema.get("required")) {
                if (!doc.has(r.stringValue())) out.add(path + "/" + r.stringValue() + " is required");
            }
        }
        if (schema.has("const") && !schema.get("const").equals(doc)) out.add(path + " must be " + schema.get("const"));
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode e : schema.get("enum")) found |= e.equals(doc) || (e.isNull() && (doc == null || doc.isNull()));
            if (!found) out.add(path + " = " + doc + " is not in " + schema.get("enum"));
        }
        if (schema.has("minimum") && doc.isNumber() && doc.doubleValue() < schema.get("minimum").doubleValue()) out.add(path + " below minimum");
        if (schema.has("maximum") && doc.isNumber() && doc.doubleValue() > schema.get("maximum").doubleValue()) out.add(path + " above maximum");
        if (schema.has("properties") && doc.isObject()) {
            for (var entry : schema.get("properties").properties()) {
                JsonNode child = doc.get(entry.getKey());
                if (child != null) check(entry.getValue(), child, path + "/" + entry.getKey(), out);
            }
        }
    }
}
