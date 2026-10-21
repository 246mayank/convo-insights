package dev.mayank.convoinsights.api;

import dev.mayank.convoinsights.domain.Insight;
import dev.mayank.convoinsights.domain.InsightRepository;
import dev.mayank.convoinsights.domain.InsightRepository.KeyCount;
import dev.mayank.convoinsights.domain.Intent;
import dev.mayank.convoinsights.domain.Sentiment;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/insights")
public class InsightController {

    static final int MAX_PAGE_SIZE = 200;

    private final InsightRepository repository;

    public InsightController(InsightRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<InsightView> byEventId(@PathVariable String eventId) {
        return repository.findByEventId(eventId)
                .map(InsightView::of)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public PagedModel<InsightView> search(@RequestParam(required = false) String conversationId,
                                          @RequestParam(required = false) Intent intent,
                                          @RequestParam(required = false) Sentiment sentiment,
                                          @RequestParam(required = false) String brand,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        List<Specification<Insight>> filters = new ArrayList<>();
        addEq(filters, "conversationId", conversationId);
        addEq(filters, "intent", intent);
        addEq(filters, "sentiment", sentiment);
        addEq(filters, "brand", brand);
        Specification<Insight> spec = filters.isEmpty()
                ? (root, query, cb) -> cb.conjunction()
                : Specification.allOf(filters);
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return new PagedModel<>(repository.findAll(spec, pageable).map(InsightView::of));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        long total = repository.count();
        Map<String, Long> byExtractor = toMap(repository.countByExtractor());
        long rules = byExtractor.getOrDefault("RULES", 0L);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("byIntent", toMap(repository.countByIntent()));
        out.put("bySentiment", toMap(repository.countBySentiment()));
        out.put("byExtractor", byExtractor);
        out.put("rulesShare", total == 0 ? 0.0 : (double) rules / total);
        return out;
    }

    private static void addEq(List<Specification<Insight>> filters, String field, Object value) {
        if (value != null) {
            filters.add((root, query, cb) -> cb.equal(root.get(field), value));
        }
    }

    private static Map<String, Long> toMap(List<KeyCount> rows) {
        Map<String, Long> m = new LinkedHashMap<>();
        rows.forEach(r -> m.put(String.valueOf(r.getKey()), r.getCount()));
        return m;
    }
}
