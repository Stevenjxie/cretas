package com.cretas.aims.repository.notify;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for {@link NotifyLog} — Phase 3 Canvas-Notify Step T3.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Repository
public interface NotifyLogRepository extends JpaRepository<NotifyLog, UUID> {

    /** Factory-scoped audit query, latest first. Used by Canvas log viewer + AI tool {@code notify_log_query}. */
    Page<NotifyLog> findByFactoryIdAndStatusOrderBySentAtDesc(
            String factoryId, NotifyStatus status, Pageable pageable);

    /** Recipient-scoped query — "为什么我没收到 X 通知" 排查路径. */
    Page<NotifyLog> findByRecipientUserIdAndChannelOrderBySentAtDesc(
            Long recipientUserId, NotifyChannel channel, Pageable pageable);

    /** Factory-scoped, all statuses, latest first — default tool query path. */
    Page<NotifyLog> findByFactoryIdOrderBySentAtDesc(String factoryId, Pageable pageable);

    /**
     * Flexible filter query — null params fall through (CAST hint for PG type inference,
     * per .claude/rules/database-entity-sync.md).
     */
    @Query("SELECT l FROM NotifyLog l WHERE l.factoryId = :factoryId " +
           "AND (CAST(:channel AS string) IS NULL OR l.channel = :channel) " +
           "AND (CAST(:status AS string) IS NULL OR l.status = :status) " +
           "AND (:recipientUserId IS NULL OR l.recipientUserId = :recipientUserId) " +
           "ORDER BY l.sentAt DESC")
    Page<NotifyLog> findWithFilters(
            @Param("factoryId") String factoryId,
            @Param("channel") NotifyChannel channel,
            @Param("status") NotifyStatus status,
            @Param("recipientUserId") Long recipientUserId,
            Pageable pageable);
}
