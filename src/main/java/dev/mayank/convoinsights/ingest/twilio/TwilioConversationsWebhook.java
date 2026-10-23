package dev.mayank.convoinsights.ingest.twilio;

import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.domain.Speaker;
import dev.mayank.convoinsights.domain.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Cross-channel ingest: receives Twilio Conversations {@code onMessageAdded} webhooks (SMS,
 * WhatsApp or chat), verifies the signature, stores the message once (Twilio can retry), and
 * publishes the conversation so far to {@code conversations.raw}, the same topic the Twitter
 * replay feeds. Downstream nothing knows or cares which channel an event came from.
 *
 * <p>Delivery: 200 is returned only after Kafka acks the event. If the send fails Twilio gets a
 * 500 and retries; the retried message is already stored, and the event is simply rebuilt and sent
 * again, which the consumer dedupes by eventId.
 */
@RestController
public class TwilioConversationsWebhook {

    private static final Logger log = LoggerFactory.getLogger(TwilioConversationsWebhook.class);
    /** Participants who joined over SMS / WhatsApp / Messenger have an address as their author; agents use an identity. */
    private static final Pattern CUSTOMER_AUTHOR = Pattern.compile("^(\\+|whatsapp:|messenger:|sms:).*");
    static final int MAX_TURNS = 12;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String rawTopic;
    private final String authToken;
    private final String webhookUrl;
    private final String brand;

    public TwilioConversationsWebhook(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                                      @Value("${insights.topics.raw}") String rawTopic,
                                      @Value("${insights.twilio.auth-token:}") String authToken,
                                      @Value("${insights.twilio.webhook-url}") String webhookUrl,
                                      @Value("${insights.twilio.brand}") String brand) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.mapper = mapper;
        this.rawTopic = rawTopic;
        this.authToken = authToken;
        this.webhookUrl = webhookUrl;
        this.brand = brand;
    }

    @PostMapping(path = "/webhooks/twilio/conversations", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> onEvent(@RequestParam MultiValueMap<String, String> form,
                                          @RequestHeader(value = "X-Twilio-Signature", required = false) String signature)
            throws Exception {
        if (authToken.isBlank()) {
            return ResponseEntity.status(503).body("webhook disabled: no auth token configured");
        }
        Map<String, String> params = form.toSingleValueMap();
        if (!TwilioSignature.isValid(authToken, webhookUrl, params, signature)) {
            log.warn("rejected webhook with an invalid X-Twilio-Signature");
            return ResponseEntity.status(403).body("invalid signature");
        }
        if (!"onMessageAdded".equals(params.get("EventType"))) {
            return ResponseEntity.ok("ignored");
        }
        String conversationSid = required(params, "ConversationSid");
        String messageSid = required(params, "MessageSid");
        String author = params.getOrDefault("Author", "");
        Speaker speaker = CUSTOMER_AUTHOR.matcher(author).matches() ? Speaker.CUSTOMER : Speaker.AGENT;
        Instant sentAt = params.containsKey("DateCreated") ? Instant.parse(params.get("DateCreated")) : Instant.now();

        int inserted = jdbc.update("""
                        INSERT INTO webhook_messages
                            (message_sid, conversation_sid, msg_index, speaker, customer_hash, body, source, sent_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (message_sid) DO NOTHING
                        """,
                messageSid, conversationSid, parseIndex(params.get("Index")), speaker.name(),
                speaker == Speaker.CUSTOMER ? hash(author).substring(0, 16) : null,
                params.getOrDefault("Body", ""), params.get("Source"), Timestamp.from(sentAt));
        if (inserted == 0) {
            log.info("webhook retry for messageSid={} (already stored), re-publishing", messageSid);
        }

        ConversationEvent event = buildEvent(conversationSid);
        kafka.send(rawTopic, conversationSid, mapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        return ResponseEntity.ok("ok");
    }

    /** The conversation so far (latest {@value #MAX_TURNS} messages). Its eventId changes with every new message. */
    ConversationEvent buildEvent(String conversationSid) {
        record Row(String sid, String speaker, String customerHash, String body, String source, Instant sentAt) {
        }
        List<Row> rows = jdbc.query("""
                        SELECT * FROM (
                            SELECT message_sid, speaker, customer_hash, body, source, sent_at, msg_index
                            FROM webhook_messages WHERE conversation_sid = ?
                            ORDER BY msg_index DESC NULLS LAST, sent_at DESC LIMIT ?) latest
                        ORDER BY msg_index NULLS LAST, sent_at
                        """,
                (rs, i) -> new Row(rs.getString("message_sid"), rs.getString("speaker"), rs.getString("customer_hash"),
                        rs.getString("body"), rs.getString("source"), rs.getTimestamp("sent_at").toInstant()),
                conversationSid, MAX_TURNS);

        List<Turn> turns = rows.stream()
                .filter(r -> !r.body().isBlank())
                .map(r -> new Turn(Speaker.valueOf(r.speaker()), r.body(), r.sentAt().toString()))
                .toList();
        Row firstCustomer = rows.stream().filter(r -> r.customerHash() != null).findFirst().orElse(null);
        String eventId = hash("twilio:" + conversationSid + ":"
                + rows.stream().map(Row::sid).collect(Collectors.joining(","))).substring(0, 32);
        return new ConversationEvent(eventId, conversationSid, brand, "twilio", turns,
                channelOf(firstCustomer == null ? null : firstCustomer.source()),
                firstCustomer == null ? null : "tw-" + firstCustomer.customerHash(),
                rows.isEmpty() ? null : rows.getFirst().sentAt());
    }

    static String channelOf(String source) {
        if (source == null) return "chat";
        return switch (source.toUpperCase(Locale.ROOT)) {
            case "SMS" -> "sms";
            case "WHATSAPP" -> "whatsapp";
            default -> "chat";
        };
    }

    private static Integer parseIndex(String index) {
        try {
            return index == null ? null : Integer.valueOf(index);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String required(Map<String, String> params, String name) {
        String v = params.get(name);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing " + name);
        return v;
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
