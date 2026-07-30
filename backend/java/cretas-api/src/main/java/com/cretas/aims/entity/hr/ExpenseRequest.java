package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.hr.enums.ExpenseCategory;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 报销申请.
 *
 * <p>审批通过 → 创建 {@code PaymentRecord (status=PENDING, AP — 报销付款)},
 * {@code paymentRecordId} 反向链接. 财务确认转账后 {@code PaymentRecord.status=VERIFIED}
 * + ExpenseRequest 标记 {@code paidAt}.
 *
 * <p>票据附件通过多态 {@code Attachment(entity_type='EXPENSE_REQUEST', entity_id=id)}.
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-EXP-1)
 * @since 2026-05-16
 */
@Entity
@Table(name = "expense_requests",
       indexes = {
           @Index(name = "idx_exp_factory_user", columnList = "factory_id,user_id,expense_date"),
           @Index(name = "idx_exp_factory_status", columnList = "factory_id,status,submitted_at")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseRequest extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "category", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ExpenseCategory category;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private HrRequestStatus status = HrRequestStatus.DRAFT;

    @Column(name = "approval_workflow_id", length = 36)
    private String approvalWorkflowId;

    @Column(name = "approver_ids", columnDefinition = "jsonb")
    private String approverIds;

    /** APPROVED 后填, 链接到 PaymentRecord.id (length 191 匹配 PaymentRecord). */
    @Column(name = "payment_record_id", length = 191)
    private String paymentRecordId;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;
}
