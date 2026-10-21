package dev.mayank.convoinsights.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InsightRepository extends JpaRepository<Insight, Long>, JpaSpecificationExecutor<Insight> {

    Optional<Insight> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    @Query("select i.intent as key, count(i) as count from Insight i group by i.intent order by count(i) desc")
    List<KeyCount> countByIntent();

    @Query("select i.sentiment as key, count(i) as count from Insight i group by i.sentiment order by count(i) desc")
    List<KeyCount> countBySentiment();

    @Query("select i.extractor as key, count(i) as count from Insight i group by i.extractor")
    List<KeyCount> countByExtractor();

    interface KeyCount {
        Object getKey();
        long getCount();
    }
}
