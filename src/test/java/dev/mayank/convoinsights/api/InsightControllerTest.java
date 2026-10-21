package dev.mayank.convoinsights.api;

import dev.mayank.convoinsights.TestEvents;
import dev.mayank.convoinsights.domain.Extractor;
import dev.mayank.convoinsights.domain.Insight;
import dev.mayank.convoinsights.domain.InsightRepository;
import dev.mayank.convoinsights.domain.Intent;
import dev.mayank.convoinsights.extract.RuleBasedExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InsightController.class)
class InsightControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InsightRepository repository;

    private static Insight insight(String eventId, String text) {
        var event = TestEvents.customer(eventId, text);
        return Insight.of(event, new RuleBasedExtractor().extract(event), 0);
    }

    @Test
    void getByEventId() throws Exception {
        when(repository.findByEventId("e1")).thenReturn(Optional.of(insight("e1", "I want a refund")));
        mvc.perform(get("/api/insights/e1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("e1"))
                .andExpect(jsonPath("$.intent").value("REFUND"))
                .andExpect(jsonPath("$.extractor").value("RULES"));
    }

    @Test
    void unknownEventIdIs404() throws Exception {
        when(repository.findByEventId("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/insights/nope")).andExpect(status().isNotFound());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchReturnsAPage() throws Exception {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(insight("e1", "refund please")), Pageable.ofSize(20), 1));
        mvc.perform(get("/api/insights").param("intent", "REFUND").param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventId").value("e1"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void invalidEnumFilterIs400() throws Exception {
        mvc.perform(get("/api/insights").param("intent", "NOT_AN_INTENT")).andExpect(status().isBadRequest());
    }

    @Test
    void statsComputesRulesShare() throws Exception {
        when(repository.count()).thenReturn(4L);
        when(repository.countByIntent()).thenReturn(List.of(kc(Intent.REFUND, 4)));
        when(repository.countBySentiment()).thenReturn(List.of());
        when(repository.countByExtractor()).thenReturn(List.of(kc(Extractor.LLM, 3), kc(Extractor.RULES, 1)));
        mvc.perform(get("/api/insights/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.byIntent.REFUND").value(4))
                .andExpect(jsonPath("$.rulesShare").value(0.25));
    }

    private static InsightRepository.KeyCount kc(Object key, long count) {
        return new InsightRepository.KeyCount() {
            public Object getKey() { return key; }
            public long getCount() { return count; }
        };
    }
}
