package com.cretas.aims.entity.inventory;

import com.cretas.aims.security.PriceSensitive;
import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.enums.SettlementType;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 付款申请单（SP6 P0 轻量状态机）
 *
 * <p>状态流转：
 * <pre>
 * PENDING → (finance_review) → FINANCE_REVIEW → (approve) → APPROVED → (markPaid) → PAID
 *                                             ↘ (reject) → REJECTED
 *         ↘ (cancel by creator)              → CANCELLED
 * </pre>
 *
 * <p>幂等性：同一 purchaseOrderId 在 PENDING/FINANCE_REVIEW/APPROVED 状态下不允许重复创建（409）。
 *
 * <p>markPaid 三写原子：PaymentRequest.status=PAID + ArApTransaction(AP_PAYMENT) +
 * Supplier.currentBalance 在单一 {@code @Transactional} 中完成，任一失败全部回滚，
 * 禁止 fail-soft。
 *
 * @see PaymentRequestStatus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "payment_requests",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"factory_id", "request_number"})
        },
        indexes = {
                @Index(name = "idx_pr_factory_status", columnList = "factory_id,status"),
                @Index(name = "idx_pr_po", columnList = "purchase_order_id"),
                @Index(name = "idx_pr_supplier", columnList = "supplier_id"),
                @Index(name = "idx_pr_created_by", columnList = "created_by")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PaymentRequest extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Version
    @Column(name = "version")
    private Integer version;

    @NotBlank
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @NotBlank
    @Column(name = "request_number", nullable = false, length = 50)
    private String requestNumber;

    @NotBlank
    @Column(name = "purchase_order_id", nullable = false, length = 191)
    private String purchaseOrderId;

    @NotBlank
    @Column(name = "supplier_id", nullable = false, length = 191)
    private String supplierId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentRequestStatus status = PaymentRequestStatus.PENDING;

    @NotNull
    @PriceSensitive
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /**
     * 支付方式：BANK_TRANSFER（银行转账）/ CASH（现金）/ WECHAT / ALIPAY
     */
    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "bank_account", length = 100)
    private String bankAccount;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    @NotNull
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    // ─── 财务初审 ───────────────────────────────────────────────────────────
    @Column(name = "finance_reviewed_by")
    private Long financeReviewedBy;

    @Column(name = "finance_reviewed_at")
    private LocalDateTime financeReviewedAt;

    @Column(name = "finance_review_note", columnDefinition = "TEXT")
    private String financeReviewNote;

    // ─── 最终批准 ───────────────────────────────────────────────────────────
    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approve_note", columnDefinition = "TEXT")
    private String approveNote;

    // ─── 付款执行 ───────────────────────────────────────────────────────────
    @Column(name = "paid_by")
    private Long paidBy;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** markPaid 时生成的 ArApTransaction id（用于审计追踪） */
    @Column(name = "arap_transaction_id", length = 191)
    private String arapTransactionId;

    // ─── 拒绝 / 撤回 ────────────────────────────────────────────────────────
    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    // ─── SP12 §5.4 工作流字段 ──────────────────────────────────────────────────

    /**
     * SP12 §5.4: 工作流实例 ID，关联 ApprovalWorkflowInstance.id。
     * 通过 submitForApproval() 设置。
     */
    @Column(name = "workflow_instance_id", length = 191)
    private String workflowInstanceId;

    /**
     * SP12 §5.4: 付款用途分类（如 RAW_MATERIAL_PURCHASE / SERVICE_FEE / EQUIPMENT）。
     */
    @Column(name = "purpose_code", length = 50)
    private String purposeCode;

    /**
     * SP12 §5.4: 付款用途详细说明。
     */
    @Column(name = "purpose_description", columnDefinition = "TEXT")
    private String purposeDescription;

    /**
     * SP12 §5.4: 收款方姓名/公司名。
     */
    @Column(name = "payee_name", length = 200)
    private String payeeName;

    /**
     * SP12 §5.4: 预计付款截止日期。
     */
    @Column(name = "due_date")
    private LocalDateTime dueDate;

    /**
     * SP12 §5.4: 提交人 userId（提交至工作流时设置）。
     */
    @Column(name = "submitted_by")
    private Long submittedBy;

    // ─── D-9 G7 结算方式 ─────────────────────────────────────────────────────

    /**
     * D-9 G7: 创建时从 PurchaseOrder.settlementType 继承。
     * PREPAID / CREDIT_FIRST / NO_INVOICE / MONTHLY / CREDIT_PERIOD / IMMEDIATE。
     * nullable — 老数据无此字段，create() 查不到 PO 时降级为 null。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type", length = 32)
    private SettlementType settlementType;
}
