package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.dto.sales.ExtraFeeItem;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 销售订单
 * 通用：工厂销售出货 = 餐饮外卖/堂食/团购
 * 替代 ShipmentRecord 的无结构设计，支持多品出货
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "customer", "createdByUser", "items", "suppliedMaterials", "deliveryRecords"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "sales_orders",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"factory_id", "order_number"})
        },
        indexes = {
                @Index(name = "idx_so_factory", columnList = "factory_id"),
                @Index(name = "idx_so_customer", columnList = "customer_id"),
                @Index(name = "idx_so_status", columnList = "status"),
                @Index(name = "idx_so_order_date", columnList = "order_date")
        }
)
@Where(clause = "deleted_at IS NULL")
public class SalesOrder extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    /** Sprint3-E F-VFLAG-1: 凭证生成状态. UNCREATED → PENDING → CREATED / FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "vflag", nullable = false, length = 20)
    private com.cretas.aims.entity.enums.VoucherFlag vflag = com.cretas.aims.entity.enums.VoucherFlag.UNCREATED;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @NotBlank
    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    @NotBlank
    @Column(name = "order_number", nullable = false, length = 64)
    private String orderNumber;

    @NotBlank
    @Column(name = "customer_id", nullable = false, length = 191)
    private String customerId;

    @Formula("(SELECT c.name FROM customers c WHERE c.id = customer_id)")
    private String customerName;

    @NotNull
    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    /** 要求交货日期 */
    @Column(name = "required_delivery_date")
    private LocalDate requiredDeliveryDate;

    /** 收货地址 */
    @Column(name = "delivery_address")
    private String deliveryAddress;

    @PriceSensitive
    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @PriceSensitive
    @Column(name = "discount_amount", precision = 15, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @PriceSensitive
    @Column(name = "tax_amount", precision = 15, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SalesOrderStatus status = SalesOrderStatus.DRAFT;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** Optimistic lock version — prevents silent last-write-wins on concurrent edits */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    /**
     * U-MARKER-1 (Sprint 4 Wave 2 Chat L) — 行标颜色标记 (red/orange/yellow/green/blue/null).
     * 仅作 UI 视觉标记用途，与业务状态分离。
     */
    @Column(name = "marker_color", length = 16)
    private String markerColor;

    // ==================== 销售扩展字段 ====================

    /** 业务员 */
    @Column(name = "salesperson", length = 100)
    private String salesperson;

    /** 业务员 user_id (新数据). 老订单为 NULL, 用 salesperson 字符串字段兜底.
     *  R6 (V20260425_09): converted from VARCHAR to BIGINT + FK to users(id) ON DELETE SET NULL. */
    @Column(name = "salesperson_id")
    private Long salespersonId;

    /** 是否含运费 */
    @Column(name = "shipping_included")
    private Boolean shippingIncluded;

    /** 运费 */
    @PriceSensitive
    @Column(name = "shipping_fee", precision = 15, scale = 2)
    private BigDecimal shippingFee;

    /** 其他费用 (装卸费/包装费/...) — JSON 数组 [{name, amount, remark}].
     * Note: each item's {@code amount} is also {@code @PriceSensitive} (see {@link ExtraFeeItem}). */
    @Type(JsonBinaryType.class)
    @Column(name = "extra_fees", columnDefinition = "jsonb")
    private List<ExtraFeeItem> extraFees;

    /** 实际发货金额 */
    @PriceSensitive
    @Column(name = "actual_shipped_amount", precision = 15, scale = 2)
    private BigDecimal actualShippedAmount;

    // ==================== 财务审核 ====================

    /** 财务审核人ID */
    @Column(name = "finance_reviewed_by")
    private Long financeReviewedBy;

    /** 财务审核时间 */
    @Column(name = "finance_reviewed_at")
    private LocalDateTime financeReviewedAt;

    /** 财务审核意见 */
    @Column(name = "finance_review_notes", columnDefinition = "TEXT")
    private String financeReviewNotes;

    /** 预估BOM成本（基于BOM + 历史采购均价） */
    @PriceSensitive
    @Column(name = "estimated_cost", precision = 15, scale = 2)
    private BigDecimal estimatedCost;

    /** 预估利润（totalAmount - estimatedCost） */
    @PriceSensitive
    @Column(name = "estimated_profit", precision = 15, scale = 2)
    private BigDecimal estimatedProfit;

    // ==================== Sprint 4 W2 S-INVOICE-CLIENT-1: 订单级默认 (Option 3 三层 default 链第 2 层) ====================

    /** 单据级默认税率 (%) — SO 创建时 prefill 自 Customer.defaultTaxRate, 再下放到 Item.taxRate.
     *  Item 创建时若未显式指定 taxRate, 则继承此 SO default. 用户可改单据级 default 临时覆盖客户级. */
    @PriceSensitive
    @Column(name = "default_tax_rate", precision = 5, scale = 2)
    private BigDecimal defaultTaxRate;

    /** 单据级默认开票类型 — 同上 3 层链, 最终下放到 InvoiceRecord.invoiceType */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_invoice_type", length = 20)
    private com.cretas.aims.entity.enums.InvoiceType defaultInvoiceType;

    // ==================== 开票/回款/扩展 ====================

    /** 开票状态: NOT_INVOICED, PARTIAL_INVOICED, FULLY_INVOICED */
    @Column(name = "invoice_status", length = 32)
    private String invoiceStatus;

    /** 已开票金额 */
    @PriceSensitive
    @Column(name = "invoiced_amount", precision = 15, scale = 2)
    private BigDecimal invoicedAmount;

    /** 是否结清 */
    @Column(name = "settlement_flag")
    private Boolean settlementFlag;

    /** 已收款金额 */
    @PriceSensitive
    @Column(name = "paid_amount", precision = 15, scale = 2)
    private BigDecimal paidAmount;

    // ==================== SP5 提成预览字段 ====================

    /**
     * SP5: 提成预览金额 (按订单总额 × 适用提成费率预估).
     * 仅供展示 — 不替代 Commission.amount 的结算逻辑.
     * price-sensitive: 仅特权角色可见.
     */
    @PriceSensitive
    @Column(name = "commission_preview", precision = 12, scale = 2)
    private BigDecimal commissionPreview;

    /**
     * SP5: 套用的提成费率 (百分比, 0-100, scale=4).
     * price-sensitive: 仅特权角色可见.
     */
    @PriceSensitive
    @Column(name = "commission_rate_pct", precision = 6, scale = 4)
    private BigDecimal commissionRatePct;

    /** 关联报价单ID */
    @Column(name = "quote_id", length = 191)
    private String quoteId;

    /** 运输计划状态 */
    @Column(name = "transport_plan_status", length = 32)
    private String transportPlanStatus;

    /** 交付提醒日期 */
    @Column(name = "delivery_reminder_date")
    private java.time.LocalDate deliveryReminderDate;

    /** 下单箱数 */
    @Column(name = "box_quantity", precision = 15, scale = 2)
    private BigDecimal boxQuantity;

    /** P1-7 预订合同附件 URL (OSS path, v1 §2.4.3 客户会议 2257s 提及) */
    @Column(name = "contract_file_url", length = 500)
    private String contractFileUrl;

    /** P1-7 预订合同附件原文件名 */
    @Column(name = "contract_file_name", length = 255)
    private String contractFileName;

    /**
     * P3 多仓: 叮咚采购单标题 (如 "0601-熟食T+2").
     *
     * <p>客户视角的"1张采购单"分组键 — 同一个叮咚采购单对应系统里 1 张 SalesOrder,
     * 订单内含 N 行 (按仓 × 按品). 此字段在列表页展示方便追溯.
     *
     * <p>Nullable: 普通订单 (非叮咚多仓场景) 不传此字段.
     * Migration: {@code V20260915_01__sales_order_item_dest_warehouse.sql}
     */
    @Column(name = "external_order_title", length = 100)
    private String externalOrderTitle;

    /**
     * Processing and material-supply defaults for this order.
     * Nullable only for pre-migration history; every new or edited order is validated by the service.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_mode", length = 32)
    private SalesProcessingMode processingMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "material_supply_mode", length = 32)
    private MaterialSupplyMode materialSupplyMode;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Customer customer;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User createdByUser;

    @OneToMany(mappedBy = "salesOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SalesOrderItem> items = new ArrayList<>();

    /**
     * Customer-supplied raw-material requirements. Each child row is also the warehouse task
     * identity; this relation never creates a parallel receiving-task record.
     */
    @OneToMany(mappedBy = "salesOrder", fetch = FetchType.LAZY)
    @OrderBy("expectedArrivalAt ASC, id ASC")
    private List<SalesOrderSuppliedMaterialRequirement> suppliedMaterials = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "salesOrder", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<SalesDeliveryRecord> deliveryRecords = new ArrayList<>();

    /**
     * SP5 E-5 毛利红线预警 (非阻断, 2026-06-10 决策修正).
     *
     * <p>下单时若某行报价低于毛利红线, 后端不再 409 阻断 (客户明确"低于底线提示红色, 不是不允许"),
     * 而是把红线预警文案 (来自 {@code GrossMarginCheckResult.warningMessage}) 收集到此 @Transient
     * 列表, 随创建成功的订单返回. 前端展示 sticky warning, 不阻止提交 (fool-proof Rule 1: 预先显示边界).
     *
     * <p><strong>安全约束</strong>: 文案仅含"低于毛利红线"语义, 不含 minPrice / standardCost /
     * targetGrossMargin 等价格敏感数值 (由 {@code GrossMarginCheckResult} 保证)。
     *
     * <p>不持久化 (@Transient); 仅在 createSalesOrder 返回值上有意义。
     */
    @Transient
    @JsonProperty("marginWarnings")
    private List<String> marginWarnings = new ArrayList<>();

    /**
     * P1 #33 销售价 ≥ 研发预估价 跨流校验预警 (非阻断, 2026-06-11).
     *
     * <p>下单时若某行销售报价低于研发预估价 (SP10 QuotationTask 建议售价), 后端不 409 阻断
     * (决策对齐 #693 毛利红线: 低于阈值=警告放行, 防呆 Rule 1 提交前看到边界), 而是把预警文案
     * (来自 {@code EstimatePriceCheckResult.warningMessage}) 收集到此 @Transient 列表,
     * 随创建成功的订单返回。前端展示 sticky warning, 不阻止提交。
     *
     * <p><strong>安全约束</strong>: 文案仅含"低于研发预估价"语义, 不含 estimatePrice /
     * suggestedPrice / finalPrice 等价格敏感数值 (由 {@code EstimatePriceCheckResult} 保证)。
     *
     * <p>不持久化 (@Transient); 仅在 createSalesOrder 返回值上有意义。
     */
    @Transient
    @JsonProperty("priceWarnings")
    private List<String> priceWarnings = new ArrayList<>();

    // ==================== 计算属性 ====================

    @Transient
    public BigDecimal calculateTotalAmount() {
        if (items == null || items.isEmpty()) return BigDecimal.ZERO;
        // Defensive: items[].getLineAmount() may return null when unitPrice stripped
        // (@PriceSensitive). Filter nulls so reduce doesn't NPE on BigDecimal::add.
        return items.stream()
                .map(SalesOrderItem::getLineAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transient
    @PriceSensitive
    public BigDecimal getPayableAmount() {
        // PR #423 hardening: PriceFieldResponseAdvice strips totalAmount to null for
        // roles without procurement:price:view (e.g. warehouse_manager). Derived getter
        // must mirror that strip — otherwise Jackson NPEs on subtract(...) when
        // serializing for those roles → HTTP 500 (P0 fix 2026-05-12, PR #443).
        if (totalAmount == null) return null;
        BigDecimal discount = discountAmount != null ? discountAmount : BigDecimal.ZERO;
        BigDecimal tax = taxAmount != null ? taxAmount : BigDecimal.ZERO;
        return totalAmount.subtract(discount).add(tax);
    }

    /**
     * 收款状态 — v1 §2.4.4 "待收款/部分收款/已收款".
     * 从 paidAmount vs totalAmount 派生, 不存 DB.
     * @JsonProperty 确保 Jackson 将此 @Transient getter 序列化到 JSON 响应.
     */
    @Transient
    @JsonProperty("paymentStatus")
    public String getPaymentStatus() {
        BigDecimal paid = this.paidAmount != null ? this.paidAmount : BigDecimal.ZERO;
        BigDecimal total = this.totalAmount != null ? this.totalAmount : BigDecimal.ZERO;
        if (paid.compareTo(BigDecimal.ZERO) <= 0) return "UNPAID";
        if (paid.compareTo(total) >= 0) return "PAID";
        return "PARTIAL";
    }

    // ==================== Sprint3-G S-LOCK-1 行内库存状态聚合 ====================
    // 销售员看销售单列表时一眼看锁/备/缺. 行 chip 用 row.lockedQty / reservedQty /
    // shortageQty (聚合 items[]). NOT @PriceSensitive — inventory 数据非价格 (见
    // brief §risks point 5). items lazy-loaded via @OneToMany — 当 items 未加载时
    // 返回 0 而不是 NPE.

    @Transient
    @JsonProperty("lockedQty")
    public BigDecimal getTotalLockedQty() {
        if (items == null || items.isEmpty()) return BigDecimal.ZERO;
        return items.stream()
                .map(it -> it.getLockedQty() != null ? it.getLockedQty() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transient
    @JsonProperty("reservedQty")
    public BigDecimal getTotalReservedQty() {
        if (items == null || items.isEmpty()) return BigDecimal.ZERO;
        return items.stream()
                .map(it -> it.getReservedQty() != null ? it.getReservedQty() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transient
    @JsonProperty("shortageQty")
    public BigDecimal getTotalShortageQty() {
        if (items == null || items.isEmpty()) return BigDecimal.ZERO;
        return items.stream()
                .map(SalesOrderItem::getShortageQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
