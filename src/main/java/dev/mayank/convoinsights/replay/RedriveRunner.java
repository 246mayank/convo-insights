package dev.mayank.convoinsights.replay;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Inspects the dead-letter topic and, when asked, re-publishes records to the topic they came from
 * (after the bug that sent them there is fixed). Dry run by default: it lists what it would send.
 * Re-driving is safe to repeat because the consumer dedupes on eventId.
 * <pre>
 *   --spring.profiles.active=redrive [--redrive.execute=true] [--redrive.match=ExceptionSimpleName]
 * </pre>
 */
@Component
@Profile("redrive")
public class RedriveRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RedriveRunner.class);
    private static final String CAUSE_FQCN = KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN;
    private static final String MESSAGE = KafkaHeaders.DLT_EXCEPTION_MESSAGE;
    private static final String ORIGINAL_TOPIC = KafkaHeaders.DLT_ORIGINAL_TOPIC;

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> template;
    private final String dltTopic;
    private final boolean execute;
    private final String match;

    public RedriveRunner(ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> template,
                         @Value("${insights.topics.dead-letter}") String dltTopic,
                         @Value("${redrive.execute:false}") boolean execute,
                         @Value("${redrive.match:}") String match) {
        this.consumerFactory = consumerFactory;
        this.template = template;
        this.dltTopic = dltTopic;
        this.execute = execute;
        this.match = match;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int seen = 0, selected = 0, sent = 0;
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("redrive-" + UUID.randomUUID(), "redrive")) {
            List<TopicPartition> partitions = consumer.partitionsFor(dltTopic).stream()
                    .map(p -> new TopicPartition(dltTopic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);

            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofSeconds(1))) {
                    seen++;
                    String cause = header(r, CAUSE_FQCN);
                    String target = header(r, ORIGINAL_TOPIC);
                    boolean pick = match.isBlank() || (cause != null && cause.endsWith(match));
                    log.info("dlt offset={} key={} cause={} message={} originalTopic={} {}", r.offset(), r.key(),
                            cause, abbreviate(header(r, MESSAGE)), target, pick ? "[selected]" : "");
                    if (!pick || target == null) continue;
                    selected++;
                    if (execute) {
                        template.send(target, r.key(), r.value()).get(10, TimeUnit.SECONDS);
                        sent++;
                    }
                }
            }
        }
        log.info("redrive done: seen={} selected={} sent={} execute={}", seen, selected, sent, execute);
    }

    private static String header(ConsumerRecord<?, ?> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static String abbreviate(String s) {
        return s == null || s.length() <= 160 ? s : s.substring(0, 157) + "...";
    }
}
