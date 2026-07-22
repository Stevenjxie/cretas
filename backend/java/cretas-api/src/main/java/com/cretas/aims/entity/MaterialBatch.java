package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.InboundType;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.PriceSensitive;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * 原材料批次实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "materialType", "supplier", "createdBy", "consumptions", "adjustments", "planBatchUsages"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "material_batches",
       indexes = {
           @Index(name = "idx_batch_factory", columnList = "factory_id"),
           @Index(name = "idx_batch_status", columnList = "status"),
           @Index(name = "idx_batch_expire", columnList = "expire_date"),
           @Index(name = "idx_batch_material", columnList = "material_type_id"),
           @Index(name = "idx_material_batch_warehouse", columnList = "factory_id, warehouse_id")
       }
)
@Where(clause = "deleted_at IS NULL")
public class MaterialBatch extends BaseEntity {
    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;
    @Column(name = "factory_id", nullable = false)
    private String factoryId;
    @Column(name = "batch_number", nullable = false, unique = true, length = 64)
    private String batchNumber;
    @Column(name = "material_type_id", nullable = false)
    private String materialTypeId;  // 修改为String类型以匹配MaterialType的UUID
    @Column(name = "supplier_id")
    private String supplierId;  // 修改为String类型以匹配Supplier的UUID
    @Column(name = "inbound_date", nullable = false)
    private LocalDate receiptDate;  // 映射到inbound_date (入库日期)

    @Column(name = "production_date")
    private LocalDate productionDate;  // 生产日期

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;
    @Column(name = "expire_date")
    private LocalDate expireDate;

    /**
     * D1 双仓流转 (2026-05-10 spec, PR #309 A1=A).
     * FK to factory_warehouses.id. 默认 WH-LOG (物流仓) — 原料默认在物流仓持久库存,
     * 调拨进车间才到 WH-WKS.
     */
    @Column(name = "warehouse_id", nullable = false, length = 64)
    private String warehouseId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    // Hibernate version generation NPEs when legacy SQL-seeded rows load a null version.
    // DB backfill/default covers persisted rows; this keeps already-loaded entities safe.
    public Long getVersion() {
        return version != null ? version : 0L;
    }

    // ===================================================================
    // 核心字段 - 存储在数据库
    // ===================================================================

    /**
     * Apr 24 2026: @Positive 改为 @PositiveOrZero — adjustBatchQuantity 消耗完批次
     * 时 receiptQuantity 会减到 0 (实现把消耗写回 receiptQuantity 而非 usedQuantity,
     * 见 MaterialBatchServiceImpl.adjustBatchQuantity:515-519), validation 原本严格
     * >0 导致整事务 rollback. 消耗完批次 receiptQuantity=0 + status=USED_UP 是合法态.
     * 创建校验走 DTO 层 (CreateMaterialBatchRequest 仍 @Positive), entity 层放宽为 ≥0.
     */
    @NotNull(message = "入库数量不能为空")
    @PositiveOrZero(message = "入库数量不能为负数")
    @Column(name = "receipt_quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal receiptQuantity;

    @NotBlank(message = "数量单位不能为空")
    @Column(name = "quantity_unit", nullable = false, length = 20)
    private String quantityUnit;

    /**
     * T145: 粗略统计用箱数, 与称重 kg 并存。
     * 仅用于展示/粗略统计, 不参与任何库存充足性/可用量/成本/换算计算
     * (kg 库存校验由 receiptQuantity + quantity_unit=kg 负责, 见 validateMaterialStockSufficient)。
     * 可空 — 未填写时为 null, 不从 kg 推导。
     */
    @PositiveOrZero(message = "箱数不能为负数")
    @Column(name = "box_count")
    private Integer boxCount;

    @PositiveOrZero(message = "每单位重量不能为负数")
    @Column(name = "weight_per_unit", precision = 10, scale = 3)
    private BigDecimal weightPerUnit;

    @PositiveOrZero(message = "已用数量不能为负数")
    @Column(name = "used_quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal usedQuantity = BigDecimal.ZERO;

    @PositiveOrZero(message = "预留数量不能为负数")
    @Column(name = "reserved_quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal reservedQuantity = BigDecimal.ZERO;

    @PriceSensitive
    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MaterialBatchStatus status = MaterialBatchStatus.AVAILABLE;
    @Column(name = "storage_location", length = 100)
    private String storageLocation;
    @Column(name = "quality_certificate", length = 100)
    private String qualityCertificate;
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * SP4-A4: 生产厂家编号 (factory_number) — 用于一物一码标签.
     * Nullable; 六扇门仓管入库时填写供应商厂家批号.
     */
    @Column(name = "factory_number", length = 100)
    private String factoryNumber;

    /**
     * SP4-A4: 产地 (origin_place) — 用于一物一码标签.
     * Nullable; 例如"山东省青岛市". 最长 200 字符.
     */
    @Column(name = "origin_place", length = 200)
    private String originPlace;

    /** 入库类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "inbound_type", length = 30)
    private InboundType inboundType;

    /** 发起单类型 (P0-17): PURCHASE_RECEIVE / MATERIAL_REQUISITION_RETURN / SALES_RETURN / MANUAL_ADJUST */
    @Column(name = "source_doc_type", length = 32)
    private String sourceDocType;

    /** 发起单ID (P0-17) */
    @Column(name = "source_doc_id", length = 64)
    private String sourceDocId;

    /** Idempotency identity of one inbound event; multiple partial receipts use distinct keys. */
    @Column(name = "source_event_key", length = 64)
    private String sourceEventKey;

    /**
     * Legal ownership snapshot for this inventory batch.
     *
     * <p>New batches are company-owned unless an authoritative upstream
     * document explicitly supplies customer ownership. Persisted legacy rows
     * remain nullable and are not inferred.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ownership", length = 32)
    private InventoryOwnership ownership = InventoryOwnership.COMPANY_OWNED;

    /** Customer that legally owns the batch when {@link #ownership} is customer-owned. */
    @Column(name = "owner_customer_id", length = 191)
    private String ownerCustomerId;

    /** Sales-order lineage snapshot; opaque across factories, so intentionally no FK. */
    @Column(name = "source_sales_order_id", length = 191)
    private String sourceSalesOrderId;

    /** Sales-order-line lineage snapshot; opaque across factories, so intentionally no FK. */
    @Column(name = "source_sales_order_item_id", length = 191)
    private String sourceSalesOrderItemId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    @org.hibernate.annotations.BatchSize(size = 10)
    private Factory factory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_type_id", referencedColumnName = "id", insertable = false, updatable = false)
    @org.hibernate.annotations.BatchSize(size = 20)
    private RawMaterialType materialType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", referencedColumnName = "id", insertable = false, updatable = false)
    @org.hibernate.annotations.BatchSize(size = 10)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User createdByUser;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<MaterialConsumption> consumptions = new ArrayList<>();

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<MaterialBatchAdjustment> adjustments = new ArrayList<>();

    @OneToMany(mappedBy = "materialBatch", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<ProductionPlanBatchUsage> planBatchUsages = new ArrayList<>();

    // ===================================================================
    // 计算属性 - 不存储在数据库，动态计算
    // ===================================================================

    /**
     * 获取当前可用数量
     * 计算公式: receiptQuantity - usedQuantity - reservedQuantity
     */
    @Transient
    public BigDecimal getCurrentQuantity() {
        if (receiptQuantity == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal used = usedQuantity != null ? usedQuantity : BigDecimal.ZERO;
        BigDecimal reserved = reservedQuantity != null ? reservedQuantity : BigDecimal.ZERO;
        return receiptQuantity.subtract(used).subtract(reserved);
    }

    /**
     * 🔒 库存完整性不变式守卫 (全局兜底): {@code usedQuantity + reservedQuantity ≤ receiptQuantity}。
     *
     * <p><b>用法</b>: 在<b>任何</b>增加 {@code usedQuantity} 或 {@code reservedQuantity} 的写入点,
     * 在 mutation <b>之后、save 之前</b>调用。违反即 loud-fail (409 {@code BATCH_OVER_CONSUMED}),
     * 回滚事务, 拒绝把批次扣成"负库存"(凭空多消耗)。防呆 Rule 1: 预先拦截而非事后腐蚀。
     *
     * <p><b>为什么用后置校验 (post-mutation) 而非"预检 used+reserved+newQty≤receipt"</b>:
     * 后置校验对已 mutation 的实体断言全局不变式, 天然<b>误伤-proof</b> —— 任何合法态 (含
     * 预留转消耗 {@code reserved−=q; used+=q} 净额不变) 必满足此式; 唯有真超扣才触发。
     * 无需 caller 知道增量, 对 used / reserved 两侧增量统一生效。
     *
     * <p>这是应用层的友好 loud-fail; 数据库层另有 CHECK 约束
     * {@code ck_material_batch_no_overconsume} 作为任何漏接写入点 / 未来回归的最硬兜底
     * (见 Flyway {@code V20261027_42})。两层互补: 应用层给具体批次/数量的防呆文案,
     * DB 层保证物理不可能落库超扣。
     */
    public void assertConsumptionInvariant() {
        BigDecimal receipt = receiptQuantity != null ? receiptQuantity : BigDecimal.ZERO;
        BigDecimal used = usedQuantity != null ? usedQuantity : BigDecimal.ZERO;
        BigDecimal reserved = reservedQuantity != null ? reservedQuantity : BigDecimal.ZERO;
        BigDecimal committed = used.add(reserved);
        if (committed.compareTo(receipt) > 0) {
            throw new BusinessException(409, String.format(
                    "批次 %s 库存超扣: 已用 %s + 预留 %s = %s 超过收货量 %s (超 %s)",
                    batchNumber,
                    used.stripTrailingZeros().toPlainString(),
                    reserved.stripTrailingZeros().toPlainString(),
                    committed.stripTrailingZeros().toPlainString(),
                    receipt.stripTrailingZeros().toPlainString(),
                    committed.subtract(receipt).stripTrailingZeros().toPlainString()))
                    .withCode("BATCH_OVER_CONSUMED")
                    .withHint("请核对消耗/预留数量, 或先补充该原料批次库存")
                    .withSeverity("BLOCKING")
                    .withHintTarget(batchNumber);
        }
    }

    /**
     * 获取货架实物数量 (physical shelf quantity)。
     *
     * <p>计算公式: {@code receiptQuantity − usedQuantity} —— <b>不</b>扣减 reservedQuantity。
     *
     * <p>与 {@link #getCurrentQuantity()} 的关键区别: 预留 (reserved) 是<b>逻辑占用</b>
     * (下游订单/生产计划挂占), 货物<b>物理上仍在货架上</b>, 没有离开仓库; 而已领用
     * (used) 的货物是<b>物理离开</b>了。因此:
     * <ul>
     *   <li>可用量 {@code getCurrentQuantity()} = receipt − used − reserved (可再分配给新需求)</li>
     *   <li>货架实物 {@code getPhysicalQuantity()} = receipt − used (仓管盘点时肉眼看到的量)</li>
     * </ul>
     *
     * <p>盘点 (stocktake) 快照/差异对账<b>必须</b>用本方法 (货架实物), 不能用可用量 ——
     * 否则每个有 reserved 的批次会凭空产生 {@code reserved} 数量的假盘盈 (仓管数到货架实物,
     * 系统账面却已扣掉预留, 实物 > 账面 → 假盘盈 → 虚假 借1403/贷6301 营业外收入凭证 +
     * 后续预留转消耗时同一批货二次扣减, 库存与损益永久虚增 reserved)。
     */
    @Transient
    public BigDecimal getPhysicalQuantity() {
        if (receiptQuantity == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal used = usedQuantity != null ? usedQuantity : BigDecimal.ZERO;
        return receiptQuantity.subtract(used);
    }

    /**
     * 获取剩余数量（与getCurrentQuantity相同）
     */
    @Transient
    public BigDecimal getRemainingQuantity() {
        return getCurrentQuantity();
    }

    /**
     * 获取总数量（与receiptQuantity相同）
     */
    @Transient
    public BigDecimal getTotalQuantity() {
        return receiptQuantity;
    }

    /**
     * 获取初始数量（与receiptQuantity相同）
     */
    @Transient
    public BigDecimal getInitialQuantity() {
        return receiptQuantity;
    }

    /**
     * 获取总价
     * 计算公式: unitPrice × receiptQuantity
     * Price-sensitive: returns null when unitPrice stripped for warehouse_manager.
     */
    @Transient
    @PriceSensitive
    public BigDecimal getTotalPrice() {
        // Defensive null guard — unitPrice is @PriceSensitive, stripped to null
        // for warehouse_manager. Return null (not ZERO) to avoid leaking "free".
        if (unitPrice == null || receiptQuantity == null) {
            return null;
        }
        return unitPrice.multiply(receiptQuantity);
    }

    /**
     * 获取总价值（与getTotalPrice相同）. Price-sensitive — see {@link #getTotalPrice()}.
     */
    @Transient
    @PriceSensitive
    public BigDecimal getTotalValue() {
        return getTotalPrice();
    }

    /**
     * 获取总重量
     * 计算公式: weightPerUnit × receiptQuantity
     */
    @Transient
    public BigDecimal getTotalWeight() {
        if (weightPerUnit == null || receiptQuantity == null) {
            return BigDecimal.ZERO;
        }
        return weightPerUnit.multiply(receiptQuantity);
    }

    // ===================================================================
    // 前端兼容别名 - 统一字段命名
    // ===================================================================

    /**
     * 获取入库数量 (前端使用 inboundQuantity)
     */
    @Transient
    public BigDecimal getInboundQuantity() {
        return receiptQuantity;
    }

    /**
     * 获取过期日期 (前端使用 expiryDate)
     */
    @Transient
    public LocalDate getExpiryDate() {
        return expireDate;
    }

    /**
     * 获取入库日期 (前端使用 inboundDate)
     */
    @Transient
    public LocalDate getInboundDate() {
        return receiptDate;
    }

    // AI-configured custom fields stored as JSONB
    @Type(JsonBinaryType.class)
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    private Map<String, Object> customFields = new HashMap<>();

    /**
     * 获取质量等级 (前端使用 qualityGrade)
     */
    @Transient
    public String getQualityGrade() {
        return qualityCertificate;
    }

    /**
     * 获取总成本 (前端使用 totalCost). Price-sensitive — delegates to
     * {@link #getTotalPrice()} which already returns {@code null} when
     * {@code unitPrice} has been stripped. Marked {@code @PriceSensitive}
     * so Jackson's {@code PriceSensitiveSerializerModifier} short-circuits
     * the getter at serialization time for users lacking
     * {@code procurement:price:view} — defense-in-depth alongside the
     * delegate's defensive null guard. See PR #443 follow-up F3.
     */
    @Transient
    @PriceSensitive
    public BigDecimal getTotalCost() {
        return getTotalPrice();
    }
}
