package dev.mayank.convoinsights.ingest;

import dev.mayank.convoinsights.domain.Insight;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An insight as a Segment Track call ("Conversation Analyzed"), following the Segment spec:
 * {@code type}, {@code event}, {@code userId} or {@code anonymousId}, {@code messageId},
 * {@code timestamp}, snake_case {@code properties}. {@code messageId} is the eventId, so Segment's
 * own messageId dedupe lines up with ours and an at-least-once re-send is dropped there too.
 * Contract: {@code contracts/segment-track.schema.json}.
 */
public final class SegmentTrack {

    public static final String EVENT = "Conversation Analyzed";

    private SegmentTrack() {
    }

    public static Map<String, Object> of(Insight i) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("conversation_id", i.getConversationId());
        props.put("brand", i.getBrand());
        props.put("channel", i.getChannel());
        props.put("intent", i.getIntent().name());
        props.put("sentiment", i.getSentiment().name());
        props.put("summary", i.getSummary());
        props.put("confidence", i.getConfidence());
        props.put("extractor", i.getExtractor().name());
        props.put("model", i.getModel());
        props.put("prompt_version", i.getPromptVersion());
        props.put("pii_redactions", i.getPiiRedactions());

        Map<String, Object> track = new LinkedHashMap<>();
        track.put("type", "track");
        track.put("event", EVENT);
        if (i.getCustomerId() != null) {
            track.put("userId", i.getCustomerId());
        } else {
            track.put("anonymousId", i.getConversationId());
        }
        track.put("messageId", i.getEventId());
        Instant ts = i.getStartedAt() != null ? i.getStartedAt() : i.getCreatedAt();
        track.put("timestamp", ts.toString());
        track.put("properties", props);
        track.put("context", Map.of("library", Map.of("name", "convo-insights", "version", "0.0.1")));
        return track;
    }
}
