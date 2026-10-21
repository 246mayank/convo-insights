package dev.mayank.convoinsights.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "insights")
public class Insight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    private String brand;

    @Column(nullable = false)
    private String source;

    @Column(name = "turn_count", nullable = false)
    private int turnCount;

    @Column(nullable = false, length = 500)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sentiment sentiment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Intent intent;

    @Column(nullable = false)
    private double confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Extractor extractor;

    @Enumerated(EnumType.STRING)
    @Column(name = "fallback_reason")
    private FallbackReason fallbackReason;

    private String model;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "llm_latency_ms")
    private Long llmLatencyMs;

    @Column(name = "pii_redactions", nullable = false)
    private int piiRedactions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Insight() {
    }

    public static Insight of(ConversationEvent event, ExtractionResult result, int piiRedactions) {
        Insight i = new Insight();
        i.eventId = event.eventId();
        i.conversationId = event.conversationId();
        i.brand = event.brand();
        i.source = event.source();
        i.turnCount = event.turns().size();
        i.summary = result.summary();
        i.sentiment = result.sentiment();
        i.intent = result.intent();
        i.confidence = result.confidence();
        i.extractor = result.extractor();
        i.fallbackReason = result.fallbackReason();
        i.model = result.model();
        i.promptVersion = result.promptVersion();
        i.llmLatencyMs = result.llmLatencyMs();
        i.piiRedactions = piiRedactions;
        i.createdAt = Instant.now();
        return i;
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getConversationId() { return conversationId; }
    public String getBrand() { return brand; }
    public String getSource() { return source; }
    public int getTurnCount() { return turnCount; }
    public String getSummary() { return summary; }
    public Sentiment getSentiment() { return sentiment; }
    public Intent getIntent() { return intent; }
    public double getConfidence() { return confidence; }
    public Extractor getExtractor() { return extractor; }
    public FallbackReason getFallbackReason() { return fallbackReason; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public Long getLlmLatencyMs() { return llmLatencyMs; }
    public int getPiiRedactions() { return piiRedactions; }
    public Instant getCreatedAt() { return createdAt; }
}
