package dev.mayank.convoinsights.analytics;

import dev.mayank.convoinsights.analytics.AnalyticsQueries.AnalyticsUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Reads the Kafka Streams state stores (interactive queries). */
@RestController
@RequestMapping("/api")
public class AnalyticsController {

    private final AnalyticsQueries queries;

    public AnalyticsController(AnalyticsQueries queries) {
        this.queries = queries;
    }

    /** Sentiment per event-time bucket for a brand, e.g. {@code /api/brands/AmazonHelp/sentiment?from=2017-10-01}. */
    @GetMapping("/brands/{brand}/sentiment")
    public List<AnalyticsQueries.BucketCounts> brandSentiment(@PathVariable String brand,
                                                             @RequestParam(required = false) String from,
                                                             @RequestParam(required = false) String to) {
        return queries.brandSentiment(brand, from, to);
    }

    @GetMapping("/profiles/{customerId}")
    public ResponseEntity<Map<String, Object>> profile(@PathVariable String customerId) {
        return queries.profile(customerId)
                .map(AnalyticsController::view)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private static Map<String, Object> view(CustomerProfile p) {
        return Map.of(
                "customerId", p.customerId,
                "conversations", p.conversations,
                "negativeConversations", p.negative,
                "lastIntent", String.valueOf(p.getLastIntent()),
                "lastSeen", String.valueOf(p.getLastSeen()),
                "atRisk", p.isAtRisk(),
                "recent", p.recent);
    }

    @ExceptionHandler(AnalyticsUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(AnalyticsUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }
}
