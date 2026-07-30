package com.cretas.aims.entity.notify;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 通知发送审计日志 — Phase 3 Canvas-Notify Step T2.
 *
 * <p>每次 {@link com.cretas.aims.service.notify.NotifySender#send} 完成 (无论 SENT 或
 * FAILED) 写一条记录, 用于追踪推送可达性 + 客户咨询时排查"为什么我没收到".
 *
 * <p>1 NotifyRequest 多 channel fan-out 会写 N 条 NotifyLog (N = channels 数).
 *
 * <p>Maps to table {@code notify_logs} (V20260621_02).
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Entity
@Table(name = "notify_logs",
       indexes = {
           @Index(name = "idx_notify_logs_factory_sent",
                  columnList = "factory_id, sent_at DESC"),
           @Index(name = "idx_notify_logs_recipient",
                  columnList = "recipient_user_id, channel")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class NotifyLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /** 关联模板 code (可为 null — ad-hoc 发送时直接传 title/body). */
    @Column(name = "template_code", length = 100)
    private String templateCode;

    /** 收件用户 id. 1 NotifyRequest 多 recipient 时, 每 recipient × channel 写 1 条. */
    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20, nullable = false)
    private NotifyChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private NotifyStatus status;

    /** FAILED 时填异常摘要, SENT 时 null. */
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @PrePersist
    protected void onSent() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }
}
