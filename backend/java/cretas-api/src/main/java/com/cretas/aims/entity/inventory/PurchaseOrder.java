package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseType;
import com.cretas.aims.entity.enums.SettlementType;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.*;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 采购订单实体
 * 通用设计：工厂采购原料 = 餐饮进货食材
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "supplier", "createdByUser", "approvedByUser", "items", "receiveRecords"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "purchase_orders",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"factory_id", "order_number"})
        },
        indexes = {
                @Index(name = "idx_po_factory", columnList = "factory_id"),
                @Index(name = "idx_po_supplier", columnList = "supplier_id"),
                @Index(name = "idx_po_status", columnList = "status"),
                @Index(name = "idx_po_order_date", columnList = "order_date"),
                @Index(name = "idx_po_type", columnList = "purchase_type")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PurchaseOrder extends BaseEntity {

    /** Sprint3-E F-VFLAG-1: 凭证生成状态. UNCREATED → PENDING → CREATED / FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "vflag", nullable = false, length = 20)
    private com.cretas.aims.entity.enums.VoucherFlag vflag = com.cretas.aims.entity.enums.VoucherFlag.UNCREATED;

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @NotBlank(message = "工厂不能为空")
    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    @NotBlank(message = "订单号不能为空")
    @Column(name = "order_number", nullable = false, length = 50)
    private String orderNumber;

    @NotBlank(message = "供应商不能为空")
    @Column(name = "supplier_id", nullable = false, length = 191)
    private String supplierId;

    @Formula("(SELECT s.name FROM suppliers s WHERE s.id = supplier_id)")
    private String supplierName;

    @NotNull(message = "采购类型不能为空")
    @Enumerated(EnumType.STRING)
    @Column(name = "purchase_type", nullable = false, length = 32)
    private PurchaseType purchaseType = PurchaseType.DIRECT;

    /**
     * Sprint 4 W1 P-IMPORT-1: 是否进口采购. null=未指定 (老数据 / 暂未维护),
     * TRUE=进口 (需海关报关), FALSE=正常境内采购. 跟 purchaseType (业务流向, 直采/总部统采/紧急)
     * 正交, 两个维度独立维护.
     */
    @Column(name = "is_imported")
    private Boolean isImported;

    @NotNull(message = "下单日期不能为空")
    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @PriceSensitive
    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @PriceSensitive
    @Column(name = "tax_amount", precision = 15, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @NotNull(message = "订单状态不能为空")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PurchaseOrderStatus status = PurchaseOrderStatus.DRAFT;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** Optimistic lock version — prevents silent last-write-wins on concurrent edits */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private java.time.LocalDateTime approvedAt;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    /**
     * U-MARKER-1 (Sprint 4 Wave 2 Chat L) — 行标颜色标记 (red/orange/yellow/green/blue/null).
     */
    @Column(name = "marker_color", length = 16)
    private String markerColor;

    /**
     * W-12 fix (Round 15): optional FK to sales_orders.id — when PO is created
     * to fulfill a specific SO ("定点追踪主原料防止多采"). Nullable for 无单采购.
     * SO detail page's "关联采购" tab depends on this column.
     */
    @Column(name = "sales_order_id", length = 191)
    private String salesOrderId;

    /**
     * P-NUCLEAR-1 (28-Backlog #30): optional FK to inquiry_quotes.id — when PO is
     * created via 询价 → 核价 → 采购 pipeline. Nullable for direct PO (无核价单).
     * Used by inquiry detail page's "关联采购单" link + idempotent convert guard.
     */
    @Column(name = "inquiry_quote_id", length = 191)
    private String inquiryQuoteId;

    /**
     * Rule 2 hydration (2026-04-24): 前端"关联销售订单"展示需 orderNumber,
     * 但 entity 只存 salesOrderId. 以前 UI 要为每行 PO 再 fetch SO detail —
     * 1+N 查询. 这里用 @Transient 字段让 service 层 hydrate 一次, 前端直接读.
     * 不持久化, 不走 @Column.
     */
    @Transient
    private String salesOrderNumber;

    // ==================== 财务审核 ====================

    /** 财务审核人ID */
    @Column(name = "finance_reviewed_by")
    private Long financeReviewedBy;

    /** 财务审核时间 */
    @Column(name = "finance_reviewed_at")
    private java.time.LocalDateTime financeReviewedAt;

    /** 财务审核意见 */
    @Column(name = "finance_review_notes", columnDefinition = "TEXT")
    private String financeReviewNotes;

    // ==================== SP6: 结算 & 发票提醒 ====================

    /**
     * SP6 — 结算方式（预付/月结/账期等）。null = 历史数据未填。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type", length = 32)
    private SettlementType settlementType;

    /** SP6 — 合同编号（对应纸质合同/框架合同号）。 */
    @Column(name = "contract_number", length = 100)
    private String contractNumber;

    /**
     * SP6 — 开票提醒天数（收货后 N 天未收到发票则提醒）。
     * null = 使用工厂默认；0 = 不提醒。
     */
    @Column(name = "invoice_reminder_days")
    private Integer invoiceReminderDays;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Supplier supplier;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User createdByUser;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User approvedByUser;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PurchaseOrderItem> items = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<PurchaseReceiveRecord> receiveRecords = new ArrayList<>();

    // ==================== 计算属性 ====================

    /**
     * 计算总金额（从行项目汇总）
     */
    @Transient
    public BigDecimal calculateTotalAmount() {
        if (items == null || items.isEmpty()) return BigDecimal.ZERO;
        return items.stream()
                .map(PurchaseOrderItem::getLineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 是否全部到货
     */
    @Transient
    public boolean isFullyReceived() {
        if (items == null || items.isEmpty()) return false;
        return items.stream().allMatch(item ->
                item.getReceivedQuantity() != null &&
                        item.getReceivedQuantity().compareTo(item.getQuantity()) >= 0);
    }

    /**
     * Sprint 10 AI invocation source tag — set when Workdesk AI 1-click create PO draft.
     * Schema: {source: "sprint-10-loop-3", testRun: bool, createdAt: iso}.
     * NULL = manual UI create.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "ai_invocation_metadata", columnDefinition = "jsonb")
    private Map<String, Object> aiInvocationMetadata;
}
