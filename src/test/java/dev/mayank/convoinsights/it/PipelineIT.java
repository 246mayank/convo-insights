package dev.mayank.convoinsights.it;

import dev.mayank.convoinsights.TestcontainersConfiguration;
import dev.mayank.convoinsights.domain.Extractor;
import dev.mayank.convoinsights.domain.FallbackReason;
import dev.mayank.convoinsights.domain.Insight;
import dev.mayank.convoinsights.domain.InsightRepository;
import dev.mayank.convoinsights.domain.Intent;
import dev.mayank.convoinsights.ingest.twilio.TwilioSignature;
import dev.mayank.convoinsights.outbox.OutboxRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests against real Kafka and PostgreSQL (Testcontainers) with a scripted model.
 * Each test uses fresh event ids, so tests are independent while sharing one set of containers.
 */
@SpringBootTest(properties = {
        "insights.llm.provider=client",
        "insights.retry.initial-interval-ms=500",
        "insights.retry.multiplier=2.0",
        "insights.retry.max-attempts=6",
        "insights.outbox.poll-ms=100",
        // fail fast instead of hanging when the database is paused
        "spring.datasource.hikari.connection-timeout=2000",
        "spring.datasource.hikari.data-source-properties.socketTimeout=2",
        "insights.twilio.auth-token=it-token",
        "insights.twilio.webhook-url=https://insights.example.com/webhooks/twilio/conversations",
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PipelineIT.ScriptedModel.class})
class PipelineIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedModel {
        @Bean
        ScriptedLlmClient scriptedLlmClient() {
            return new ScriptedLlmClient();
        }
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired KafkaAdmin kafkaAdmin;
    @Autowired InsightRepository insights;
    @Autowired OutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScriptedLlmClient model;
    @Autowired PostgreSQLContainer postgres;
    @Autowired MockMvc mvc;

    private static String event(String eventId, String customerText) {
        return """
                {"eventId":"%s","conversationId":"conv-%s","brand":"AcmeSupport","source":"it",
                 "turns":[{"speaker":"CUSTOMER","text":"%s","ts":"2017-10-31T22:10:47Z"},
                          {"speaker":"AGENT","text":"Sorry to hear that, please DM us.","ts":"2017-10-31T22:12:00Z"}]}
                """.formatted(eventId, eventId, customerText);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void send(String key, String value) throws Exception {
        kafka.send("conversations.raw", key, value).get(10, TimeUnit.SECONDS);
    }

    private Insight awaitInsight(String eventId) {
        return await().atMost(Duration.ofSeconds(30)).until(() -> insights.findByEventId(eventId), Optional::isPresent).get();
    }

    @Test
    void happyPathStoresInsightAndPublishesItDownstream() throws Exception {
        String id = newId();
        send("conv-" + id, event(id, "my router keeps dropping the connection"));

        Insight insight = awaitInsight(id);
        assertThat(insight.getExtractor()).isEqualTo(Extractor.LLM);
        assertThat(insight.getIntent()).isEqualTo(Intent.TECHNICAL_SUPPORT);
        assertThat(insight.getModel()).isEqualTo("scripted/test");

        List<ConsumerRecord<String, String>> downstream = readAll("insights.enriched", r -> id.equals(r.key()), 1);
        assertThat(downstream).hasSize(1);
        assertThat(downstream.getFirst().value()).contains("\"eventId\":\"" + id + "\"").contains("TECHNICAL_SUPPORT");
        await().atMost(Duration.ofSeconds(10)).until(() -> pendingOutboxFor(id) == 0);
    }

    @Test
    void sameEventTwiceIsStoredAndPublishedOnce() throws Exception {
        String id = newId();
        String value = event(id, "the app crashes when I open it");
        send("conv-" + id, value);
        send("conv-" + id, value);

        awaitInsight(id);
        awaitConsumerCaughtUp();
        assertThat(jdbc.queryForObject("select count(*) from insights where event_id = ?", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox where record_key = ?", Long.class, id)).isEqualTo(1);
        // the model was asked once, not twice
        assertThat(model.seenMessages().stream().filter(m -> m.contains("app crashes when I open it")).count()).isEqualTo(1);
    }

    @Test
    void malformedModelOutputFallsBackToRules() throws Exception {
        String id = newId();
        send("conv-" + id, event(id, "I want a refund for my order " + ScriptedLlmClient.MALFORMED_MARKER));

        Insight insight = awaitInsight(id);
        assertThat(insight.getExtractor()).isEqualTo(Extractor.RULES);
        assertThat(insight.getFallbackReason()).isEqualTo(FallbackReason.MALFORMED_JSON);
        assertThat(insight.getIntent()).isEqualTo(Intent.REFUND);
    }

    @Test
    void unparseableRecordGoesStraightToTheDeadLetterTopic() throws Exception {
        String key = "poison-" + newId();
        send(key, "this is not json {");

        List<ConsumerRecord<String, String>> dlt = readAll("conversations.raw.DLT", r -> key.equals(r.key()), 1);
        assertThat(dlt).hasSize(1);
        assertThat(dlt.getFirst().value()).isEqualTo("this is not json {");
        var fqcn = dlt.getFirst().headers().lastHeader("kafka_dlt-exception-cause-fqcn");
        assertThat(fqcn).isNotNull();
        assertThat(new String(fqcn.value())).endsWith("InvalidEventException");
    }

    @Test
    void piiNeverReachesTheModel() throws Exception {
        String id = newId();
        send("conv-" + id, event(id, "my wifi is down, reach me at jane.doe@example.com or 555-123-4567"));

        Insight insight = awaitInsight(id);
        assertThat(insight.getPiiRedactions()).isEqualTo(2);
        String seen = model.seenMessages().stream().filter(m -> m.contains("my wifi is down")).findFirst().orElseThrow();
        assertThat(seen).doesNotContain("jane.doe@example.com").doesNotContain("555-123-4567").contains("[EMAIL]", "[PHONE]");
    }

    /**
     * The core delivery guarantee: while the database is unavailable the offset is NOT committed,
     * and once it recovers the event is stored exactly once. Nothing is acked before it is persisted.
     */
    @Test
    void offsetIsNotCommittedUntilTheInsightIsPersisted() throws Exception {
        awaitConsumerCaughtUp();
        String id = newId();
        String key = "conv-" + id;
        var docker = postgres.getDockerClient();
        docker.pauseContainerCmd(postgres.getContainerId()).exec();
        try {
            var meta = kafka.send("conversations.raw", key, event(id, "outage test, nothing loads")).get(10, TimeUnit.SECONDS).getRecordMetadata();
            var tp = new TopicPartition(meta.topic(), meta.partition());
            // give the consumer time to receive the record and fail against the paused database
            Thread.sleep(4000);
            assertThat(committedOffset(tp)).as("offset must not move past an unpersisted record")
                    .isLessThanOrEqualTo(meta.offset());
        } finally {
            docker.unpauseContainerCmd(postgres.getContainerId()).exec();
        }
        awaitInsight(id);
        awaitConsumerCaughtUp();
        assertThat(jdbc.queryForObject("select count(*) from insights where event_id = ?", Long.class, id)).isEqualTo(1);
    }

    /** Cross-channel ingest: a signed Twilio Conversations webhook (SMS) ends up as an insight like any tweet. */
    @Test
    void signedTwilioWebhookBecomesAnInsight() throws Exception {
        String conv = "CH" + newId();
        postWebhook(conv, "IM" + newId(), "+15550001111", "SMS", 0,
                "my internet has been down all day, call me back on 555-123-4567", "it-token", 200);
        String agentMsg = "IM" + newId();
        postWebhook(conv, agentMsg, "agent.priya", "SDK", 1, "Sorry about that, we are checking the line now.", "it-token", 200);
        postWebhook(conv, agentMsg, "agent.priya", "SDK", 1, "Sorry about that, we are checking the line now.", "it-token", 200); // Twilio retry

        Insight insight = await().atMost(Duration.ofSeconds(30)).until(
                () -> insights.findAll().stream().filter(i -> conv.equals(i.getConversationId()) && i.getTurnCount() == 2).findFirst(),
                Optional::isPresent).get();
        assertThat(insight.getSource()).isEqualTo("twilio");
        assertThat(insight.getChannel()).isEqualTo("sms");
        assertThat(insight.getCustomerId()).startsWith("tw-").doesNotContain("5550001111");
        assertThat(insight.getPiiRedactions()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from webhook_messages where conversation_sid = ?", Long.class, conv))
                .as("the retried message is stored once").isEqualTo(2);
    }

    @Test
    void webhookWithABadSignatureIsRejected() throws Exception {
        String conv = "CH" + newId();
        postWebhook(conv, "IM" + newId(), "+15550002222", "SMS", 0, "hello", "not-the-token", 403);
        assertThat(jdbc.queryForObject("select count(*) from webhook_messages where conversation_sid = ?", Long.class, conv)).isZero();
    }

    private void postWebhook(String conv, String msg, String author, String source, int index, String body,
                             String signWith, int expectedStatus) throws Exception {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("EventType", "onMessageAdded");
        params.put("ConversationSid", conv);
        params.put("MessageSid", msg);
        params.put("Author", author);
        params.put("Source", source);
        params.put("Index", String.valueOf(index));
        params.put("Body", body);
        params.put("DateCreated", "2026-10-23T10:0" + index + ":00Z");
        String sig = TwilioSignature.compute(signWith, "https://insights.example.com/webhooks/twilio/conversations", params);
        var req = post("/webhooks/twilio/conversations").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("X-Twilio-Signature", sig);
        params.forEach((k, v) -> req.param(k, v));
        mvc.perform(req).andExpect(status().is(expectedStatus));
    }

    // ---- helpers ----

    private long pendingOutboxFor(String key) {
        return jdbc.queryForObject("select count(*) from outbox where record_key = ? and sent_at is null", Long.class, key);
    }

    private List<ConsumerRecord<String, String>> readAll(String topic, java.util.function.Predicate<ConsumerRecord<String, String>> match, int expected) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> c = consumerFactory.createConsumer("it-reader-" + newId(), "it")) {
            c.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline && found.size() < expected) {
                for (var r : c.poll(Duration.ofMillis(500))) {
                    if (match.test(r)) found.add(r);
                }
            }
            // keep reading briefly to catch unexpected duplicates
            long settle = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < settle) {
                for (var r : c.poll(Duration.ofMillis(300))) {
                    if (match.test(r)) found.add(r);
                }
            }
        }
        return found;
    }

    private long committedOffset(TopicPartition tp) throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets("convo-insights")
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            OffsetAndMetadata m = offsets.get(tp);
            return m == null ? 0 : m.offset();
        }
    }

    /** Waits until the service's consumer group has committed up to the end of every raw partition. */
    private void awaitConsumerCaughtUp() {
        try {
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).until(() -> lagByPartition().isEmpty());
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            String state;
            try {
                state = String.valueOf(lagByPartition());
            } catch (Exception ex) {
                state = "unavailable: " + ex;
            }
            throw new AssertionError("consumer did not catch up; partitions behind (end/committed): " + state, e);
        }
    }

    /** Partitions of the raw topic whose committed offset is behind the log end, as "end/committed". */
    private Map<TopicPartition, String> lagByPartition() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            var partitions = admin.describeTopics(List.of("conversations.raw")).allTopicNames().get()
                    .get("conversations.raw").partitions().stream()
                    .map(p -> new TopicPartition("conversations.raw", p.partition()))
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            var ends = admin.listOffsets(partitions).all().get();
            var committed = admin.listConsumerGroupOffsets("convo-insights").partitionsToOffsetAndMetadata().get();
            Map<TopicPartition, String> behind = new java.util.TreeMap<>(java.util.Comparator.comparing(TopicPartition::toString));
            ends.forEach((tp, end) -> {
                var c = committed.get(tp);
                long done = c == null ? 0 : c.offset();
                if (end.offset() > done) behind.put(tp, end.offset() + "/" + (c == null ? "none" : done));
            });
            return behind;
        }
    }
}
