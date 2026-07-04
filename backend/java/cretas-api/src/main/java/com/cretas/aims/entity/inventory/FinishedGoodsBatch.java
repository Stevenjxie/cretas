package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 成品库存批次
 * 对标 MaterialBatch（原料库存），填补 生产→[成品库存]→销售发货 的缺失环节
 * 通用：工厂成品 = 餐饮菜品半成品/预制品
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "productType"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "finished_goods_batches",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"factory_id", "batch_number"})
        },
        indexes = {
                @Index(name = "idx_fgb_factory", columnList = "factory_id"),
                @Index(name = "idx_fgb_product", columnList = "product_type_id"),
                @Index(name = "idx_fgb_status", columnList = "status"),
                @Index(name = "idx_fgb_production_date", columnList = "production_date"),
                @Index(name = "idx_fgb_expire_date", columnList = "expire_date"),
                @Index(name = "idx_finished_batch_warehouse", columnList = "factory_id, warehouse_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class FinishedGoodsBatch extends BaseEntity {

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
    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    @NotBlank
    @Column(name = "batch_number", nullable = false, length = 64)
    private String batchNumber;

    @NotBlank
    @Column(name = "product_type_id", nullable = false, length = 191)
    private String productTypeId;

    /** 产品名称（冗余） */
    @Column(name = "product_name", length = 200)
    private String productName;

    /** 生产/入库数量。
     * H1 (B2): @Positive→@PositiveOrZero —— 成品报损(SCRAP)减 producedQuantity, 全量报损会到 0;
     * @Positive 会在 flush 触发 ConstraintViolation→500 (spring-boot-starter-validation 在 update 校验)。
     * 0 是合法状态(批次已耗尽 DEPLETED, 历史由 FinishedGoodsAdjustmentLog 保留)。创建路径始终传 >0, 无回归。 */
    @NotNull
    @PositiveOrZero
    @Column(name = "produced_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal producedQuantity;

    /** 已发货数量 */
    @Column(name = "shipped_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal shippedQuantity = BigDecimal.ZERO;

    /** 预留数量（已下单未发货） */
    @Column(name = "reserved_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal reservedQuantity = BigDecimal.ZERO;

    @NotBlank
    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /**
     * G3 成本传导 — 成品单位成本 (库存生产小结写入)。
     *
     * <p>= (本道 ProductionBatch.totalCost[原料+调料+人工] + Σ SFI 投料 feedKg × 输入 SFI.unitCost) / 入库量。
     * 🔴 诚实 null: 任一投入成本未知 (SFI 投料 unitCost 为 null / 无批次成本) → null (不伪造 ¥0)。
     * 区别于 {@link #unitPrice} (售价, 来自 product_types 主数据)。
     */
    @Column(name = "unit_cost", precision = 15, scale = 4)
    private BigDecimal unitCost;

    /** 生产日期 */
    @Column(name = "production_date")
    private LocalDate productionDate;

    /** 过期日期 */
    @Column(name = "expire_date")
    private LocalDate expireDate;

    /** 存放位置 */
    @Column(name = "storage_location", length = 100)
    private String storageLocation;

    /** 关联生产计划ID（可选） */
    @Column(name = "production_plan_id", length = 191)
    private String productionPlanId;

    /**
     * D1 双仓流转 (2026-05-10 spec, PR #309 A1=A).
     * FK to factory_warehouses.id. 默认 WH-WKS (车间仓) — 成品诞生于生产, 反向调拨前在车间仓.
     * 反向调拨后才到 WH-LOG, 销售从 WH-LOG 出.
     */
    @NotBlank
    @Column(name = "warehouse_id", nullable = false, length = 64)
    private String warehouseId;

    /** 成品批次状态常量 */
    public static final class Status {
        public static final String AVAILABLE = "AVAILABLE";
        public static final String DEPLETED   = "DEPLETED";
        public static final String EXPIRED    = "EXPIRED";
        public static final String FROZEN     = "FROZEN";
        /** SP2 整单撤回完成后置为此状态 */
        public static final String REVERSED   = "REVERSED";
        /**
         * 不良品 — 销售退货入库 (ReturnOrderServiceImpl) 及 QC 失败隔离 (生产质检判 FAILED) 共用。
         * 现有可售/FEFO 查询均过滤 status='AVAILABLE', 自动排除 DEFECTIVE, 无需 repository 重构。
         * 质检经理复核后可改回 AVAILABLE。
         */
        public static final String DEFECTIVE  = "DEFECTIVE";

        private Status() {}
    }

    /** 状态: AVAILABLE / DEPLETED / EXPIRED / FROZEN / REVERSED(SP2撤回) / DEFECTIVE(不良品/QC隔离) */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "AVAILABLE";

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    /**
     * 气调货标称数 (V20261024_01). 一托标 36 但实收 37 时, nominal_quantity=36,
     * producedQuantity=37 (库存按实收计量). null = 非气调/无差异入库.
     * 差异 = producedQuantity - nominalQuantity (业务层派生).
     */
    @Column(name = "nominal_quantity", precision = 15, scale = 4)
    private BigDecimal nominalQuantity;

    /**
     * 对方划单确认 (V20261024_01). 气调货标称≠实收时需对方签字确认. null = 不适用.
     */
    @Column(name = "counterparty_confirmed")
    private Boolean counterpartyConfirmed;

    /**
     * 入库差异/划单备注 (V20261024_01). 留痕气调差异原因 + 对方划单情况.
     */
    @Column(name = "inbound_remark", columnDefinition = "TEXT")
    private String inboundRemark;

    /**
     * 入库差异 (实收 − 标称). 派生字段, 仅序列化不持久化.
     * nominalQuantity 为 null 时返 null (非气调入库无差异概念).
     */
    @Transient
    @com.fasterxml.jackson.annotation.JsonProperty("inboundDiscrepancy")
    public BigDecimal getInboundDiscrepancy() {
        if (nominalQuantity == null || producedQuantity == null) {
            return null;
        }
        return producedQuantity.subtract(nominalQuantity);
    }

    @Version
    @Column(name = "version")
    private Long version;

    /**
     * SP2 整单撤回: 关联 report_reversal_logs.id.
     * 撤回完成后写入, 未撤回为 null. V20260910_14 新增列.
     */
    @Column(name = "reversal_log_id")
    private Long reversalLogId;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_type_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ProductType productType;

    // ==================== 计算属性 ====================

    /** 可用库存 = 生产入库 - 已发货 - 预留 */
    @Transient
    public BigDecimal getAvailableQuantity() {
        BigDecimal shipped = shippedQuantity != null ? shippedQuantity : BigDecimal.ZERO;
        BigDecimal reserved = reservedQuantity != null ? reservedQuantity : BigDecimal.ZERO;
        return producedQuantity.subtract(shipped).subtract(reserved);
    }

    /** 是否已耗尽 */
    @Transient
    public boolean isDepleted() {
        return getAvailableQuantity().compareTo(BigDecimal.ZERO) <= 0;
    }

    /** 是否过期 */
    @Transient
    public boolean isExpired() {
        return expireDate != null && LocalDate.now().isAfter(expireDate);
    }
}
