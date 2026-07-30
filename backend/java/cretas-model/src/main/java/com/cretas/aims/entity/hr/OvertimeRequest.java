package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.hr.enums.CompensationType;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.OvertimeType;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 加班/调休申请.
 *
 * <p>审批通过:
 * <ul>
 *   <li>{@code compensationType=CASH} → 不动 LeaveBalance, 待 Payroll 月度统计</li>
 *   <li>{@code compensationType=COMPTIME} → {@code LeaveBalance(COMPTIME).earnedHours += hours}</li>
 * </ul>
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-OVT-1)
 * @since 2026-05-16
 */
@Entity
@Table(name = "overtime_requests",
       indexes = {
           @Index(name = "idx_ovt_factory_user", columnList = "factory_id,user_id,start_time"),
           @Index(name = "idx_ovt_factory_status", columnList = "factory_id,status,submitted_at")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OvertimeRequest extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "overtime_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private OvertimeType overtimeType;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal hours;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "compensation_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CompensationType compensationType;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private HrRequestStatus status = HrRequestStatus.DRAFT;

    @Column(name = "approval_workflow_id", length = 36)
    private String approvalWorkflowId;

    @Column(name = "approver_ids", columnDefinition = "jsonb")
    private String approverIds;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;
}
