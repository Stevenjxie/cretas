package com.cretas.aims.repository.notify;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
