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

    /** 生产/入库数量 */
    @NotNull
    @Positive
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

        private Status() {}
    }

    /** 状态: AVAILABLE / DEPLETED / EXPIRED / FROZEN / REVERSED(SP2撤回) */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "AVAILABLE";

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

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
