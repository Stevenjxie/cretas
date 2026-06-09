package com.cretas.aims.entity.inventory;

import com.cretas.aims.security.PriceSensitive;
import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 采购发票（SP6）
 *
 * <p>记录从供应商收取的发票信息，支持 OCR 自动识别 + 人工核对。
 * 通过 reconcileStatus 标记是否与 PO 金额一致。
 *
 * <p>提醒规则：{@code invoiceReminderDays} 在 PurchaseOrder 上配置，
 * listPendingReminder 端点查询 PO.orderDate + reminderDays 过期但
 * 未收到发票（invoice_count=0）的 PO。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "purchase_invoices",
        indexes = {
                @Index(name = "idx_pi_factory", columnList = "factory_id"),
                @Index(name = "idx_pi_po", columnList = "purchase_order_id"),
                @Index(name = "idx_pi_supplier", columnList = "supplier_id"),
                @Index(name = "idx_pi_reconcile", columnList = "reconcile_status")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PurchaseInvoice extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
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

    @NotBlank
    @Column(name = "purchase_order_id", nullable = false, length = 191)
    private String purchaseOrderId;

    @Column(name = "supplier_id", length = 191)
    private String supplierId;

    /** 发票号码（供应商开具） */
    @Column(name = "invoice_number", length = 100)
    private String invoiceNumber;

    /** 发票代码（增值税专票） */
    @Column(name = "invoice_code", length = 50)
    private String invoiceCode;

    /** 发票类型：VAT_SPECIAL / VAT_ORDINARY / RECEIPT / OTHER */
    @Column(name = "invoice_type", length = 32)
    private String invoiceType;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @NotNull
    @PriceSensitive
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @PriceSensitive
    @Column(name = "tax_amount", precision = 12, scale = 2)
    private BigDecimal taxAmount;

    @PriceSensitive
    @Column(name = "amount_without_tax", precision = 12, scale = 2)
    private BigDecimal amountWithoutTax;

    /** 对账状态：PENDING / MATCHED / MISMATCHED */
    @Column(name = "reconcile_status", nullable = false, length = 32)
    private String reconcileStatus = "PENDING";

    @Column(name = "reconcile_note", columnDefinition = "TEXT")
    private String reconcileNote;

    @Column(name = "reconciled_by")
    private Long reconciledBy;

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    // ─── OCR 自动识别字段 ──────────────────────────────────────────────────
    /** OSS key of the uploaded invoice image/PDF */
    @Column(name = "attachment_key", length = 500)
    private String attachmentKey;

    /** OCR 识别置信度 (0.0-1.0) */
    @Column(name = "ocr_confidence", precision = 5, scale = 4)
    private BigDecimal ocrConfidence;

    /**
     * OCR 原始识别结果（JSON，供审计追踪）
     */
    @Column(name = "ocr_raw_result", columnDefinition = "TEXT")
    private String ocrRawResult;

    /** OCR 处理时间 */
    @Column(name = "ocr_processed_at")
    private LocalDateTime ocrProcessedAt;

    // ─── 上传 ─────────────────────────────────────────────────────────────
    @NotNull
    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;
}
