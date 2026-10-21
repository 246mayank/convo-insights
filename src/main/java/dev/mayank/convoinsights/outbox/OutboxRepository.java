package dev.mayank.convoinsights.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of pending rows. {@code SKIP LOCKED} lets several relay instances run at once:
     * each locks a different batch instead of blocking on, or double-sending, another's rows.
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE sent_at IS NULL
            ORDER BY id
            LIMIT :batch
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("batch") int batch);

    @Query("select count(o) from OutboxEvent o where o.sentAt is null")
    long countPending();
}
