package dev.mayank.convoinsights.analytics;

import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Interactive queries: reads the topology's state stores directly, so the API answers from the
 * same aggregates the stream maintains, without a second database. Single instance here; with
 * several instances each holds some partitions and a query would be routed by key metadata.
 */
@Component
public class AnalyticsQueries {

    private final ObjectProvider<StreamsBuilderFactoryBean> factory;

    public AnalyticsQueries(ObjectProvider<StreamsBuilderFactoryBean> factory) {
        this.factory = factory;
    }

    public record BucketCounts(String bucket, long positive, long neutral, long negative, long total, double negativeShare) {
    }

    /** Buckets for one brand in time order, optionally limited to [from, to] (bucket strings compare in time order). */
    public List<BucketCounts> brandSentiment(String brand, String from, String to) {
        ReadOnlyKeyValueStore<String, SentimentCounts> store = store(AnalyticsTopology.BRAND_SENTIMENT_STORE);
        List<BucketCounts> out = new ArrayList<>();
        try (var it = store.prefixScan(brand + AnalyticsTopology.SEP, new StringSerializer())) {
            it.forEachRemaining(kv -> {
                String bucket = AnalyticsTopology.bucketOf(kv.key);
                if ((from == null || bucket.compareTo(from) >= 0) && (to == null || bucket.compareTo(to) <= 0)) {
                    SentimentCounts c = kv.value;
                    out.add(new BucketCounts(bucket, c.positive, c.neutral, c.negative, c.total(), c.negativeShare()));
                }
            });
        }
        return out;
    }

    public Optional<CustomerProfile> profile(String customerId) {
        ReadOnlyKeyValueStore<String, CustomerProfile> store = store(AnalyticsTopology.PROFILE_STORE);
        return Optional.ofNullable(store.get(customerId));
    }

    private <V> ReadOnlyKeyValueStore<String, V> store(String name) {
        StreamsBuilderFactoryBean bean = factory.getIfAvailable();
        KafkaStreams streams = bean == null ? null : bean.getKafkaStreams();
        if (streams == null || streams.state() != KafkaStreams.State.RUNNING) {
            throw new AnalyticsUnavailableException("analytics are not running (state: "
                    + (streams == null ? "disabled" : streams.state()) + ")");
        }
        try {
            return streams.store(StoreQueryParameters.fromNameAndType(name, QueryableStoreTypes.keyValueStore()));
        } catch (InvalidStateStoreException e) {
            throw new AnalyticsUnavailableException("store " + name + " is not queryable yet: " + e.getMessage());
        }
    }

    public static class AnalyticsUnavailableException extends RuntimeException {
        public AnalyticsUnavailableException(String message) {
            super(message);
        }
    }
}
