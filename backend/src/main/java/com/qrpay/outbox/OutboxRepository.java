package com.qrpay.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * FOR UPDATE SKIP LOCKED lets several app instances relay concurrently: each one grabs a disjoint
     * batch instead of blocking on (or double-publishing) rows another relay is working on.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("limit") int limit);

    @Modifying
    @Query(value = "DELETE FROM outbox_events WHERE status = 'PUBLISHED' AND published_at < :cutoff", nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
