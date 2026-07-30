package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.LeaveType;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 请假申请.
 *
 * <p>提交流程: DRAFT → SUBMITTED → APPROVED/REJECTED.
 * 审批通过后 {@code LeaveBalance.usedHours += durationHours} (仅
 * leaveType ∈ {ANNUAL, SICK, COMPTIME, OTHER}).
 *
 * <p>关联:
 * <ul>
 *   <li>{@code approval_workflow_id} → {@code ApprovalWorkflow.id} (graph)
 *       或 {@code ApprovalChainConfig.id} (flat fallback)</li>
 *   <li>附件通过多态 {@code Attachment(entity_type='LEAVE_REQUEST', entity_id=id)}</li>
 * </ul>
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-LEAVE-1)
 * @since 2026-05-16
 */
@Entity
@Table(name = "leave_requests",
       indexes = {
           @Index(name = "idx_leave_factory_user", columnList = "factory_id,user_id,start_date"),
           @Index(name = "idx_leave_factory_status", columnList = "factory_id,status,submitted_at")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveRequest extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "leave_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private LeaveType leaveType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** 申请时长 (小时). 跨日按 8h/day 计 (前端预填, 后端可校验). */
    @Column(name = "duration_hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal durationHours;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private HrRequestStatus status = HrRequestStatus.DRAFT;

    /** ApprovalWorkflow.id 或 ApprovalChainConfig.id, SUBMITTED 后填. */
    @Column(name = "approval_workflow_id", length = 36)
    private String approvalWorkflowId;

    /** JSONB Long[] — 已审批人员 / 当前节点候选审批人. Jackson serde at service layer. */
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
