package dev.mayank.convoinsights.analytics;

import dev.mayank.convoinsights.api.InsightView;
import dev.mayank.convoinsights.domain.Extractor;
import dev.mayank.convoinsights.domain.Intent;
import dev.mayank.convoinsights.domain.Sentiment;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsTopologyTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private TopologyTestDriver driver;
    private TestInputTopic<String, String> input;
    private TestOutputTopic<String, String> alerts;

    @BeforeEach
    void setUp() {
        var builder = new StreamsBuilder();
        AnalyticsTopology.build(builder, mapper, new AnalyticsTopology.Settings(
                "insights.enriched", "analytics.brand-sentiment", "alerts.sentiment-spike",
                AnalyticsTopology.Bucket.DAY, 3, 0.8));
        var props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "analytics-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        driver = new TopologyTestDriver(builder.build(), props);
        input = driver.createInputTopic("insights.enriched", new StringSerializer(), new StringSerializer());
        alerts = driver.createOutputTopic("alerts.sentiment-spike", new StringDeserializer(), new StringDeserializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    private void send(String eventId, String customer, Sentiment sentiment, String startedAt) {
        var v = new InsightView(eventId, "conv-" + eventId, "AmazonHelp", "twcs", 2, "summary", sentiment,
                Intent.DELIVERY, 0.9, Extractor.LLM, null, "m", "v1", 100L, 0, "twitter", customer,
                Instant.parse(startedAt), Instant.parse("2026-10-21T00:00:00Z"));
        input.pipeInput(eventId, mapper.writeValueAsString(v));
    }

    private SentimentCounts counts(String key) {
        KeyValueStore<String, SentimentCounts> store = driver.getKeyValueStore(AnalyticsTopology.BRAND_SENTIMENT_STORE);
        return store.get(key);
    }

    @Test
    void countsPerBrandAndEventTimeDay() {
        send("e1", "c1", Sentiment.NEGATIVE, "2017-10-31T10:00:00Z");
        send("e2", "c2", Sentiment.POSITIVE, "2017-10-31T23:59:00Z");
        send("e3", "c3", Sentiment.NEGATIVE, "2017-11-01T00:01:00Z"); // next day by event time

        assertThat(counts("AmazonHelp|2017-10-31").total()).isEqualTo(2);
        assertThat(counts("AmazonHelp|2017-10-31").negative).isEqualTo(1);
        assertThat(counts("AmazonHelp|2017-11-01").total()).isEqualTo(1);
    }

    @Test
    void redeliveredInsightIsNotCountedTwice() {
        send("e1", "c1", Sentiment.NEGATIVE, "2017-10-31T10:00:00Z");
        send("e1", "c1", Sentiment.NEGATIVE, "2017-10-31T10:00:00Z"); // the outbox relay re-sent it
        send("e1", "c1", Sentiment.NEGATIVE, "2017-10-31T10:00:00Z");

        assertThat(counts("AmazonHelp|2017-10-31").total()).isEqualTo(1);
    }

    @Test
    void updatedInsightMovesItsCountInsteadOfAddingOne() {
        send("e1", "c1", Sentiment.POSITIVE, "2017-10-31T10:00:00Z");
        send("e1", "c1", Sentiment.NEGATIVE, "2017-10-31T10:00:00Z");

        var c = counts("AmazonHelp|2017-10-31");
        assertThat(c.positive).isZero();
        assertThat(c.negative).isEqualTo(1);
    }

    @Test
    void spikeAlertRaisesAndClearsWithATombstone() {
        send("e1", "c1", Sentiment.NEGATIVE, "2017-10-31T10:00:00Z");
        send("e2", "c2", Sentiment.NEGATIVE, "2017-10-31T11:00:00Z");
        assertThat(alerts.readKeyValuesToList()).isEmpty(); // 2 conversations, below the minimum of 3

        send("e3", "c3", Sentiment.NEGATIVE, "2017-10-31T12:00:00Z");
        var raised = alerts.readKeyValuesToList();
        assertThat(raised).hasSize(1);
        assertThat(raised.getFirst().key).isEqualTo("AmazonHelp|2017-10-31");
        assertThat(raised.getFirst().value).contains("\"negativeShare\":1.0").contains("\"conversations\":3");

        send("e4", "c4", Sentiment.POSITIVE, "2017-10-31T13:00:00Z"); // 3 of 4 negative = 0.75, below 0.8
        var cleared = alerts.readKeyValuesToList();
        assertThat(cleared).hasSize(1);
        assertThat(cleared.getFirst().value).as("tombstone clears the alert").isNull();
    }

    @Test
    void profileTracksConversationsAndRisk() {
        send("e1", "c1", Sentiment.POSITIVE, "2017-10-01T10:00:00Z");
        send("e2", "c1", Sentiment.NEGATIVE, "2017-10-05T10:00:00Z");
        send("e3", "c1", Sentiment.NEGATIVE, "2017-10-09T10:00:00Z");
        send("e3", "c1", Sentiment.NEGATIVE, "2017-10-09T10:00:00Z"); // redelivery

        KeyValueStore<String, CustomerProfile> store = driver.getKeyValueStore(AnalyticsTopology.PROFILE_STORE);
        CustomerProfile p = store.get("c1");
        assertThat(p.conversations).isEqualTo(3);
        assertThat(p.negative).isEqualTo(2);
        assertThat(p.isAtRisk()).isTrue();
        assertThat(p.getLastSeen()).isEqualTo(Instant.parse("2017-10-09T10:00:00Z"));
        assertThat(p.recent).extracting(CustomerProfile.Recent::eventId).containsExactly("e3", "e2", "e1");
    }
}
