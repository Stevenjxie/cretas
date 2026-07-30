package com.cretas.aims.entity.factory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 工厂盘点任务明细行 (SP7 §3.2).
 *
 * <p>每行对应一个 MaterialBatch 的盘点快照。
 * differenceQty = actualQty - systemQty，应用层计算后持久化。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"stocktake"})
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "factory_stocktake_items",
        indexes = {
                @Index(name = "idx_stocktake_item_stocktake", columnList = "stocktake_id"),
                @Index(name = "idx_stocktake_item_batch",     columnList = "material_batch_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class FactoryStocktakeItem extends BaseEntity {

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
    private FactoryStocktake stocktake;

    /** FK → material_batches.id */
    @Column(name = "material_batch_id", nullable = false, length = 191)
    private String materialBatchId;

    /** 冗余品名 ID，用于展示，FK → raw_material_types.id */
    @Column(name = "raw_material_type_id", length = 191)
    private String rawMaterialTypeId;

    /** 账面库存快照（发起时记录，不随 MaterialBatch 变动而更新）*/
    @Column(name = "system_qty", nullable = false, precision = 12, scale = 4)
    private BigDecimal systemQty;

    /** 仓管填写的实盘数量 */
    @Column(name = "actual_qty", precision = 12, scale = 4)
    private BigDecimal actualQty;

    /** 差异数量 = actualQty - systemQty，应用层计算 */
    @Column(name = "difference_qty", precision = 12, scale = 4)
    private BigDecimal differenceQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "difference_type", length = 20)
    private DifferenceType differenceType;

    /** JSON 数组字符串，差异照片 URL */
    @Column(name = "photo_urls", columnDefinition = "TEXT")
    private String photoUrls;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
