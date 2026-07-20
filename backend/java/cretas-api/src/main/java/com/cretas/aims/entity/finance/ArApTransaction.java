package com.cretas.aims.entity.finance;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.enums.PayablePaymentStatus;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 应收应付交易记录（银行流水模式）
 *
 * 每笔交易记录对应一次余额变动：
 * - 销售出货 → AR_INVOICE（增加应收）
 * - 客户付款 → AR_PAYMENT（减少应收）
 * - 采购入库 → AP_INVOICE（增加应付）
 * - 供应商付款 → AP_PAYMENT（减少应付）
 *
 * balanceAfter 字段记录交易后的余额快照，便于对账和审计追溯。
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "ar_ap_transactions",
        indexes = {
                @Index(name = "idx_aat_factory", columnList = "factory_id"),
                @Index(name = "idx_aat_counterparty", columnList = "counterparty_type,counterparty_id"),
                @Index(name = "idx_aat_type", columnList = "transaction_type"),
                @Index(name = "idx_aat_due_date", columnList = "due_date"),
                @Index(name = "idx_aat_transaction_date", columnList = "transaction_date")
        }
)
@Where(clause = "deleted_at IS NULL")
public class ArApTransaction extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    /** 交易编号，格式: AR-YYYYMMDD-XXXX / AP-YYYYMMDD-XXXX */
    @Column(name = "transaction_number", nullable = false, length = 50)
    private String transactionNumber;

    /** 交易类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 32)
    private ArApTransactionType transactionType;

    /** 交易对手类型: CUSTOMER / SUPPLIER */
    @Enumerated(EnumType.STRING)
    @Column(name = "counterparty_type", nullable = false, length = 32)
    private CounterpartyType counterpartyType;

    /** 交易对手ID（Customer.id 或 Supplier.id） */
    @Column(name = "counterparty_id", nullable = false, length = 191)
    private String counterpartyId;

    /** 交易对手名称（冗余字段，便于查询展示） */
    @Column(name = "counterparty_name", length = 200)
    private String counterpartyName;

    /** 关联销售订单ID（AR交易时） */
    @Column(name = "sales_order_id", length = 191)
    private String salesOrderId;

    /** 关联采购订单ID（AP交易时） */
    @Column(name = "purchase_order_id", length = 191)
    private String purchaseOrderId;

    /** 关联POS订单同步ID（POS来源时） */
    @Column(name = "pos_order_sync_id")
    private Long posOrderSyncId;

    /** Business source type for non-SO/PO finance posting, e.g. SUPPLIER_DELIVERY_NOTE. */
    @Column(name = "source_type", length = 50)
    private String sourceType;

    /** Business source id for idempotent traceability when salesOrderId/purchaseOrderId is null. */
    @Column(name = "source_id", length = 191)
    private String sourceId;

    /** 交易金额（正数：挂账/增加应收应付；负数：付款/冲减应收应付） */
    @PriceSensitive
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** 交易后余额快照 */
    @PriceSensitive
    @Column(name = "balance_after", nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    /** Amount already settled against this AP invoice. Null for non-payable ledger rows. */
    @PriceSensitive
    @Column(name = "settled_amount", precision = 15, scale = 2)
    private BigDecimal settledAmount;

    /** Remaining amount of this AP invoice. Null for non-payable ledger rows. */
    @PriceSensitive
    @Column(name = "outstanding_amount", precision = 15, scale = 2)
    private BigDecimal outstandingAmount;

    /** Settlement state of this AP invoice. Null for non-payable ledger rows. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 32)
    private PayablePaymentStatus paymentStatus;

    /** ISO-4217 currency snapshot used for fail-closed settlement matching. */
    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    /** 付款方式（仅付款类交易填写） */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 32)
    private PaymentMethod paymentMethod;

    /** 付款凭证号（银行流水号、微信/支付宝交易号等） */
    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    /** 交易日期 */
    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    /** 到期日（应收/应付的预期收付日期） */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** 操作人 */
    @Column(name = "operated_by")
    private Long operatedBy;

    @Column(name = "remark", length = 500)
    private String remark;

    /**
     * R23 audit C2: approval state for AR_ADJUSTMENT / AP_ADJUSTMENT (other types stay APPROVED).
     * V20260426_01 backfills existing rows to APPROVED. New adjustments → PENDING (no balance mutation),
     * approveAdjustment() applies delta + flips to APPROVED.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 32)
    private ArApApprovalStatus approvalStatus = ArApApprovalStatus.APPROVED;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** Optimistic-lock fallback in addition to the settlement row lock. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
