package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.ReminderStatus;
import com.cretas.aims.entity.enums.ReminderType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 提醒单 — Sprint 4 W2 S-REMIND-1.
 *
 * <p>Scanner-pattern 驱动: ReminderScanService @Scheduled 每日扫描 SalesOrder,
 * 对未结清且交货日期 <= 阈值的订单创建 PAYMENT_DUE 提醒分配给销售员.
 * 用户可 snooze (延后) 或 dismiss (处理完成).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "reminders",
        indexes = {
                @Index(name = "idx_reminders_factory_assignee_status_due",
                        columnList = "factory_id, assignee_id, status, due_date"),
                @Index(name = "idx_reminders_source",
                        columnList = "factory_id, type, source_type, source_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class Reminder extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @NotBlank
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private ReminderType type;

    /** 来源业务对象类型 (字符串保留扩展性, 当前仅 SALES_ORDER). */
    @NotBlank
    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    /** 来源业务对象 ID (e.g. SalesOrder.id). */
    @NotBlank
    @Column(name = "source_id", nullable = false, length = 191)
    private String sourceId;

    /** 提醒到期日期 — 大于 today 表示尚未触发但已生成. */
    @NotNull
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** 分配给的用户 ID (typically SalesOrder.salespersonId). */
    @NotNull
    @Column(name = "assignee_id", nullable = false)
    private Long assigneeId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReminderStatus status = ReminderStatus.PENDING;

    /** SNOOZED 状态下的恢复日期 (达到该日期后 scanner 自动转回 PENDING). */
    @Column(name = "snoozed_until")
    private LocalDate snoozedUntil;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;
}
