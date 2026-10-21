package dev.mayank.convoinsights.replay;

import dev.mayank.convoinsights.domain.ConversationEvent;
import dev.mayank.convoinsights.ingest.EventParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replays a JSONL file of {@link ConversationEvent}s onto {@code conversations.raw}, keyed by
 * conversationId so all events of a conversation land on one partition, in order.
 * Run with {@code --spring.profiles.active=replay --replay.file=... [--replay.limit=N] [--replay.rate=eps]}.
 */
@Component
@Profile("replay")
public class ReplayRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReplayRunner.class);

    private final KafkaTemplate<String, String> template;
    private final EventParser parser;
    private final String topic;
    private final Path file;
    private final long limit;
    private final double rate;

    public ReplayRunner(KafkaTemplate<String, String> template, EventParser parser,
                        @Value("${insights.topics.raw}") String topic,
                        @Value("${replay.file}") Path file,
                        @Value("${replay.limit:0}") long limit,
                        @Value("${replay.rate:0}") double rate) {
        this.template = template;
        this.parser = parser;
        this.topic = topic;
        this.file = file;
        this.limit = limit;
        this.rate = rate;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        AtomicLong acked = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        List<CompletableFuture<?>> inflight = new ArrayList<>();
        long sent = 0;
        long skipped = 0;
        long start = System.nanoTime();
        long nanosPerEvent = rate > 0 ? (long) (1_000_000_000L / rate) : 0;

        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null && (limit <= 0 || sent < limit)) {
                if (line.isBlank()) continue;
                ConversationEvent event;
                try {
                    event = parser.parse(line); // validate locally so the topic only gets well-formed events
                } catch (RuntimeException e) {
                    skipped++;
                    continue;
                }
                inflight.add(template.send(topic, event.conversationId(), line).whenComplete((r, ex) -> {
                    if (ex == null) acked.incrementAndGet();
                    else failed.incrementAndGet();
                }));
                sent++;
                if (nanosPerEvent > 0) {
                    long due = start + sent * nanosPerEvent;
                    long wait = due - System.nanoTime();
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
                if (sent % 1000 == 0) log.info("replay: sent={}", sent);
            }
        }
        template.flush();
        CompletableFuture.allOf(inflight.toArray(CompletableFuture[]::new)).exceptionally(ex -> null).join();
        double secs = (System.nanoTime() - start) / 1e9;
        log.info("replay done: file={} sent={} acked={} failed={} skippedInvalid={} seconds={} eventsPerSec={}",
                file, sent, acked.get(), failed.get(), skipped, String.format("%.1f", secs),
                String.format("%.1f", sent / Math.max(secs, 1e-9)));
    }
}
