package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.Where;

/**
 * 销售订单行项目
 * 每行对应一种产品/菜品，有正式 FK 到 ProductType
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"salesOrder", "productType"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "sales_order_items",
        indexes = {
                @Index(name = "idx_soi_order", columnList = "sales_order_id"),
                @Index(name = "idx_soi_product", columnList = "product_type_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class SalesOrderItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sales_order_id", nullable = false, length = 191)
    private String salesOrderId;

    @Column(name = "product_type_id", nullable = false, length = 191)
    private String productTypeId;

    /** 产品名称（冗余） */
    @Column(name = "product_name", length = 200)
    private String productName;

    @NotNull
    @Positive
    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @PriceSensitive
    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    @PriceSensitive
    @Column(name = "discount_rate", precision = 5, scale = 2)
    private BigDecimal discountRate = BigDecimal.ZERO;

    /** 已发货数量 */
    @Column(name = "delivered_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal deliveredQuantity = BigDecimal.ZERO;

    /** 已被生产计划/调拨锁住的数量. Sprint3-G S-LOCK-1.
     *  写回路径: SalesOrderShortageReportListener.onSalesOrderFinanceApproved (lockedQty MVP=0,
     *  production_plan reservation 接入留 Sprint3-G follow-up). */
    @Column(name = "locked_qty", nullable = false, precision = 15, scale = 4)
    private BigDecimal lockedQty = BigDecimal.ZERO;

    /** 已 BOM 展开 + 备货 (reserve 给生产) 的数量. Sprint3-G S-LOCK-1.
     *  写回路径: SalesOrderShortageReportListener 从 ShortageReport 派生
     *  reservedQty = quantity - shortfallQuantity (LineItemMatch 已可用部分). */
    @Column(name = "reserved_qty", nullable = false, precision = 15, scale = 4)
    private BigDecimal reservedQty = BigDecimal.ZERO;

    @Column(name = "remark", length = 500)
    private String remark;

    /**
     * 成本单价 (未税, 净价) — SP3 口径统一。
     *
     * <p>内部存储口径 = 未税净价, 与 unit_price (销售未税单价) / SalesOrder.total_amount (未税净额) 保持一致。
     * 毛利 = 未税收入 − 未税成本, 口径自洽。
     *
     * <p>写入约束:
     * <ul>
     *   <li>上游 (生产完工回填) 必须写入未税成本单价。若原始数据为含税, 应先调用
     *       {@link com.cretas.aims.entity.enums.TaxRate#preTaxPrice(java.math.BigDecimal)} 转换后再写入。
     *   <li>向后兼容: 历史无税率数据视为 0 税率 (含税=未税), 数值不变, 无需回填。
     * </ul>
     *
     * <p>注释历史: 2026-06-11 前注释误写 "含税", SP3 口径统一修正为 "未税"。
     * 对应 FinanceCostBreakdown.actualCost = Σ (costUnitPrice × qty), 口径与 totalAmount 对齐。
     */
    @PriceSensitive
    @Column(name = "cost_unit_price", precision = 15, scale = 4)
    private BigDecimal costUnitPrice;

    /** 税率 (%) */
    @PriceSensitive
    @Column(name = "tax_rate", precision = 5, scale = 2)
    private BigDecimal taxRate;

    /** 规格 (如 200g/盒, 310g*42袋/箱) */
    @Column(name = "specification", length = 200)
    private String specification;

    /** 箱数 = 数量/箱系数, 可手动修改 */
    @Column(name = "box_quantity", precision = 15, scale = 2)
    private BigDecimal boxQuantity;

    // ==================== P3 多仓订单字段 (V20260915_01) ====================

    /**
     * 目的仓全名 (如 叮咚-北仑总仓).
     *
     * <p>P3 多仓建模: 叮咚采购单 1 张 SalesOrder 含 24 行 (6仓×4品),
     * 每行通过此字段标记配送目的仓. 备货看板按 {@link #destWarehouseCode} 拆需求.
     *
     * <p>Nullable: 普通订单 (非多仓叮咚单) 无目的仓.
     * Migration: {@code V20260915_01__sales_order_item_dest_warehouse.sql}
     */
    @Column(name = "dest_warehouse_name", length = 100)
    private String destWarehouseName;

    /**
     * 目的仓 code — 备货看板按此 GROUP BY.
     *
     * <p>Nullable. COALESCE(null, '未分仓') 在聚合查询里保底.
     */
    @Column(name = "dest_warehouse_code", length = 32)
    private String destWarehouseCode;

    /**
     * 叮咚外部采购单ID — 跨行共享, 标识"1张叮咚单"的分组键.
     * 与 SalesOrder.externalOrderTitle 互补 (title 是人读; ID 是机器键).
     */
    @Column(name = "external_po_id", length = 64)
    private String externalPoId;

    /** 商品条码 (叮咚配送签收用). Nullable. */
    @Column(name = "barcode", length = 64)
    private String barcode;

    /**
     * 预约入场时段字符串 (如 "8:00-12:00"), 叮咚仓库签收预约. Nullable.
     * 有意设为 VARCHAR 不解析为 Time — 叮咚格式不固定, 存原文方便展示.
     */
    @Column(name = "appointment_time", length = 50)
    private String appointmentTime;

    /**
     * 行级要求到达日期 — 精细到行, 可与 SalesOrder.requiredDeliveryDate 不同.
     * 典型场景: 叮咚多温区混批, 不同产品到达日期不同. Nullable.
     */
    @Column(name = "required_arrival_date")
    private LocalDate requiredArrivalDate;

    /**
     * 来源仓库 code (T4-D1, issue #525): WH-LOG (总仓) / WH-WKS (线边仓).
     *
     * <p>F006 客户反馈 (第四次会议 702-732): 成品会调回总仓, 总仓再安排发货.
     * Sales order line items record which warehouse to ship from. UI uses
     * {@code utils/warehouse.ts:warehouseDisplayLabel} for the human label.
     *
     * <p>Nullable: legacy rows + drafts where user hasn't picked yet.
     * Migration: {@code V20260514_01__add_sales_order_item_source_warehouse_code.sql}
     *
     * <p>Downstream linkage (T4-D5, separate ticket): outbound shipment logic
     * to honor this field when deducting inventory from the chosen warehouse.
     */
    @Column(name = "source_warehouse_code", length = 20)
    private String sourceWarehouseCode;

    /** 成本小计 = 数量 × 成本单价. Price-sensitive: returns null when costUnitPrice stripped. */
    @Transient
    @PriceSensitive
    public BigDecimal getCostTotal() {
        // Defensive null guard — costUnitPrice is @PriceSensitive, stripped to null
        // for warehouse_manager. Original ZERO fallback would have leaked "0.00" cost
        // for stripped rows, misleading non-price users into thinking cost is free.
        if (costUnitPrice == null || quantity == null) return null;
        return quantity.multiply(costUnitPrice).setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_order_id", referencedColumnName = "id", insertable = false, updatable = false)
    private SalesOrder salesOrder;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_type_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ProductType productType;

    // ==================== 计算属性 ====================

    /** 行金额 = 数量 × 单价. Price-sensitive: returns null when unitPrice stripped. */
    @Transient
    @PriceSensitive
    public BigDecimal getLineAmount() {
        // Defensive null guard — unitPrice is @PriceSensitive, stripped to null
        // for warehouse_manager. Return null (not ZERO) to signal "not visible" vs "free".
        if (unitPrice == null || quantity == null) return null;
        BigDecimal amount = quantity.multiply(unitPrice).setScale(2, BigDecimal.ROUND_HALF_UP);
        if (discountRate != null && discountRate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal discountMultiplier = BigDecimal.ONE.subtract(
                    discountRate.divide(new BigDecimal("100"), 6, BigDecimal.ROUND_HALF_UP));
            amount = amount.multiply(discountMultiplier).setScale(2, BigDecimal.ROUND_HALF_UP);
        }
        return amount;
    }

    /**
     * 含税行金额 = lineAmount × (1 + taxRate/100).
     *
     * <p>Mirrors {@code PurchaseOrderItem.getLineAmountWithTax()} (D-11) exactly:
     * <ul>
     *   <li>{@code @PriceSensitive} — stripped to {@code null} for roles without
     *       {@code procurement:price:view}, consistent with PO side.
     *   <li>Null-propagation: returns {@code null} when {@code getLineAmount()} is
     *       {@code null} (i.e., {@code unitPrice} stripped or {@code quantity} null).
     *   <li>Zero/null-tax short-circuit: returns {@code lineAmount} unchanged.
     *   <li>Scale-6 HALF_UP in {@code divide} → scale-2 HALF_UP final, same precision
     *       as PO side.
     *   <li>SO-specific: {@code getLineAmount()} already applies {@code discountRate}
     *       so tax is levied on the discounted base (含税价 = 折后价 × (1 + tax%)).
     * </ul>
     *
     * <p>E-4 (六扇门需求矩阵): SO 行项目需在 API 响应中同时返回 {@code lineAmount}
     * (未税) 和 {@code lineAmountWithTax} (含税) 双值, 对标 PO D-11 已实现的功能.
     */
    @Transient
    @PriceSensitive
    public BigDecimal getLineAmountWithTax() {
        BigDecimal amount = getLineAmount();
        // Defensive: getLineAmount() returns null when unitPrice stripped.
        if (amount == null) return null;
        if (taxRate == null || taxRate.compareTo(BigDecimal.ZERO) == 0) return amount;
        BigDecimal taxMultiplier = BigDecimal.ONE.add(
                taxRate.divide(new BigDecimal("100"), 6, BigDecimal.ROUND_HALF_UP));
        return amount.multiply(taxMultiplier).setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    /** 未发货数量 */
    @Transient
    public BigDecimal getPendingQuantity() {
        if (quantity == null) return BigDecimal.ZERO;
        BigDecimal delivered = deliveredQuantity != null ? deliveredQuantity : BigDecimal.ZERO;
        return quantity.subtract(delivered);
    }

    /**
     * 缺料数量 = quantity - reservedQty (clamp ≥0). Sprint3-G S-LOCK-1.
     *
     * <p>非 @Column — Jackson 序列化走 getter, 客户端拿 shortageQty 直接显示红色 chip.
     * 不加 @JsonIgnore (Jackson 默认序列化 @Transient 公有 getter).
     */
    @Transient
    public BigDecimal getShortageQty() {
        BigDecimal demand = this.quantity != null ? this.quantity : BigDecimal.ZERO;
        BigDecimal reserved = this.reservedQty != null ? this.reservedQty : BigDecimal.ZERO;
        BigDecimal shortage = demand.subtract(reserved);
        return shortage.signum() < 0 ? BigDecimal.ZERO : shortage;
    }
}
