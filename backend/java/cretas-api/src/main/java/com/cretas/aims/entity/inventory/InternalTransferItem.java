package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * 调拨行项目
 * 支持原料和成品两种类型调拨
 * item_type = RAW_MATERIAL → material_type_id (关联 raw_material_types)
 * item_type = FINISHED_GOODS → product_type_id (关联 product_types)
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"transfer"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "internal_transfer_items",
        indexes = {
                @Index(name = "idx_iti_transfer", columnList = "transfer_id"),
                @Index(name = "idx_iti_material", columnList = "material_type_id"),
                @Index(name = "idx_iti_product", columnList = "product_type_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class InternalTransferItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false, length = 191)
    private String transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 32)
    private TransferItemType itemType;

    /** 原料类型ID（item_type=RAW_MATERIAL时使用） */
    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    /** 产品类型ID（item_type=FINISHED_GOODS时使用） */
    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    /** 物品名称（冗余） */
    @Column(name = "item_name", length = 200)
    private String itemName;

    /** 调拨数量 */
    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    /** 实际到货数量（签收时填） */
    @Column(name = "received_quantity", precision = 15, scale = 4)
    private BigDecimal receivedQuantity;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    /** Optional transaction packaging; quantity/unit above remain canonical inventory values. */
    @Column(name = "material_packaging_spec_id", length = 36)
    private String materialPackagingSpecId;

    @Column(name = "package_quantity_snapshot", precision = 24, scale = 12)
    private BigDecimal packageQuantitySnapshot;

    @Column(name = "package_unit_snapshot", length = 20)
    private String packageUnitSnapshot;

    @Column(name = "inventory_base_unit_snapshot", length = 20)
    private String inventoryBaseUnitSnapshot;

    @Column(name = "package_to_base_factor_snapshot", precision = 24, scale = 12)
    private BigDecimal packageToBaseFactorSnapshot;

    /** 调拨单价（按总部定价或协议价） */
    @PriceSensitive
    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /**
     * 调出方批次ID — 两阶段语义 (PR #309 B1=C, 2026-05-11):
     * <ul>
     *   <li><b>SHIP 前 (status=APPROVED)</b>: 用户可指定具体批次. null = 默认 FEFO (最早过期).</li>
     *   <li><b>SHIP 后 (status=SHIPPED+)</b>: 记录实际扣减的首个批次 (用户指定的或 FEFO 选中的).</li>
     * </ul>
     * 卤味业务需求: CREATE 默认 FEFO, SHIP 可选新货. 详见 PR #309 B1=C.
     */
    @Column(name = "source_batch_id", length = 191)
    private String sourceBatchId;

    /** 调入方批次ID（签收确认后创建） */
    @Column(name = "target_batch_id", length = 191)
    private String targetBatchId;

    @Column(name = "remark", length = 500)
    private String remark;

    /**
     * 调出方现有库存 (调拨创建时刻的快照, 用 @Transient 不持久化).
     * 由 TransferServiceImpl 在 fetch 时按 sourceFactoryId+materialTypeId/productTypeId
     * 汇总 MaterialBatch / FinishedGoodsBatch 可用库存填充.
     * PR #289 §B4 客户对接 2026-05-10 — 调拨单 detail 页"现有库存"列.
     */
    @Transient
    private BigDecimal currentStock;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id", referencedColumnName = "id", insertable = false, updatable = false)
    private InternalTransfer transfer;

    // ==================== 计算属性 ====================

    /** 行金额 = 数量 × 单价. Price-sensitive: returns null when unitPrice stripped. */
    @Transient
    @PriceSensitive
    public BigDecimal getLineAmount() {
        // Defensive null guard — unitPrice may be @PriceSensitive-stripped for warehouse_manager.
        if (unitPrice == null || quantity == null) return null;
        return quantity.multiply(unitPrice).setScale(2, BigDecimal.ROUND_HALF_UP);
    }
}
