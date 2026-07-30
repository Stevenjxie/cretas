package com.cretas.aims.entity.factory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 半成品盘点任务明细行 (镜像 SP7 {@link FactoryStocktakeItem})。
 *
 * <p>每行对应一条 {@link com.cretas.aims.entity.SemiFinishedInventory} 的盘点快照。
 * differenceQty = actualQty - systemQty, 应用层计算后持久化。
 * systemQty = 发起时 SFI 行的 availableQuantity 快照 (不随后续变动更新)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"stocktake"})
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "semi_finished_stocktake_items",
        indexes = {
                @Index(name = "idx_sf_stocktake_item_stocktake", columnList = "stocktake_id"),
                @Index(name = "idx_sf_stocktake_item_semi",      columnList = "semi_finished_id"),
                @Index(name = "idx_sf_stocktake_item_batch_no",  columnList = "intermediate_batch_no")
        }
)
@Where(clause = "deleted_at IS NULL")
public class SemiFinishedStocktakeItem extends BaseEntity {

    public enum DifferenceType {
        SURPLUS, SHORTAGE, MATCH
    }

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stocktake_id", nullable = false)
    private SemiFinishedStocktake stocktake;

    /** FK → semi_finished_inventory.id (盘点目标 WIP 行) */
    @Column(name = "semi_finished_id", nullable = false)
    private Long semiFinishedId;

    /** 工序批次号 (SFI 幂等键; apply 时按 factory + 此号加锁定位目标行) */
    @Column(name = "intermediate_batch_no", nullable = false, length = 64)
    private String intermediateBatchNo;

    /** 冗余产品类型 ID, 用于展示 */
    @Column(name = "product_type_id", length = 100)
    private String productTypeId;

    /** 账面库存快照 (发起时 = SFI.availableQuantity, 不随 SFI 变动更新) */
    @Column(name = "system_qty", nullable = false, precision = 12, scale = 4)
    private BigDecimal systemQty;

    /** 仓管填写的实盘数量 */
    @Column(name = "actual_qty", precision = 12, scale = 4)
    private BigDecimal actualQty;

    /** 差异数量 = actualQty - systemQty, 应用层计算 */
    @Column(name = "difference_qty", precision = 12, scale = 4)
    private BigDecimal differenceQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "difference_type", length = 20)
    private DifferenceType differenceType;

    /** 单位 (冗余自 SFI, 便于展示) */
    @Column(name = "unit", length = 16)
    private String unit;

    /** JSON 数组字符串, 差异照片 URL */
    @Column(name = "photo_urls", columnDefinition = "TEXT")
    private String photoUrls;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
