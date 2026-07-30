package com.cretas.aims.entity.workreport;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 员工工序片段 (P1-1, v1 §2.x 工人欠退扫码).
 *
 * <p>客户会议 4720s: "中间有员工走了, 扫一下这个员工, 这个员工相当于欠退了,
 * 从我这工序下班了"
 *
 * <p><b>Data model 瓶颈说明</b>: 之前 EmployeeWorkSession.workTypeId 是单值,
 * 一个员工一天只能绑一个工种, 无法表达"上午切配 / 下午包装"的工种切换.
 * 本 entity 把 "工序时段" 作为独立记录 — 一天可以有多条 segment, 每条对应
 * 一个工种/工序, 有独立的 start/end 时间和 checkoutReason.
 *
 * <p><b>状态流转</b>:
 * <pre>
 *   ACTIVE (扫码入场) → CLOSED_NORMAL (正常下班) / CLOSED_EARLY (欠退) / CLOSED_SWITCH (换岗)
 * </pre>
 *
 * <p><b>Service 层 wire up 留下次</b>: 需要 RN 扫码集成 (QR or 硬件扫码枪),
 * 本 session 只做 data model + repository.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "employee_process_segments",
        indexes = {
                @Index(name = "idx_eps_factory_employee", columnList = "factory_id,employee_id"),
                @Index(name = "idx_eps_session", columnList = "work_session_id"),
                @Index(name = "idx_eps_active", columnList = "factory_id,status")
        }
)
@Where(clause = "deleted_at IS NULL")
public class EmployeeProcessSegment extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    /** 关联的 EmployeeWorkSession ID (可 null 若独立记录). */
    @Column(name = "work_session_id", length = 64)
    private String workSessionId;

    /** 工种 ID (e.g. 切配 / 包装). */
    @Column(name = "work_type_id")
    private Integer workTypeId;

    /** 工序 ID (可选, 指向具体批次的某个工序). */
    @Column(name = "process_id", length = 64)
    private String processId;

    /** 批次 ID (可选). */
    @Column(name = "batch_id", length = 64)
    private String batchId;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at")
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SegmentStatus status = SegmentStatus.ACTIVE;

    /** 结束原因 (CLOSED_* 状态时填). */
    @Enumerated(EnumType.STRING)
    @Column(name = "checkout_reason", length = 20)
    private CheckoutReason checkoutReason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    public enum SegmentStatus {
        ACTIVE,
        CLOSED_NORMAL,   // 正常下班
        CLOSED_EARLY,    // 欠退 (早退)
        CLOSED_SWITCH    // 换岗 (切换到另一个工种)
    }

    public enum CheckoutReason {
        END_OF_SHIFT,    // 下班
        EARLY_LEAVE,     // 早退 (欠退)
        WORK_TYPE_SWITCH,// 换工种
        BATCH_DONE,      // 批次完成
        INCIDENT         // 事故/生病等
    }
}
