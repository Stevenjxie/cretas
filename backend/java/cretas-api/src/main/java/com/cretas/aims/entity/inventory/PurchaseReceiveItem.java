package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * 采购入库行项目
 * 每行对应一种原料/食材的实际入库明细
 * 入库确认后关联到创建的 MaterialBatch
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"receiveRecord", "materialType", "materialBatch"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "purchase_receive_items",
        indexes = {
                @Index(name = "idx_pri_record", columnList = "receive_record_id"),
                @Index(name = "idx_pri_material", columnList = "material_type_id"),
                @Index(name = "idx_pri_batch", columnList = "material_batch_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PurchaseReceiveItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receive_record_id", nullable = false, length = 191)
    private String receiveRecordId;

    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;

    /** 原料/食材名称（冗余） */
    @Column(name = "material_name", length = 200)
    private String materialName;

    /** 实际到货数量 */
    @Column(name = "received_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal receivedQuantity;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @PriceSensitive
    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /** 入库后创建的 MaterialBatch ID */
    @Column(name = "material_batch_id", length = 191)
    private String materialBatchId;

    /** 质检结果 */
    @Column(name = "qc_result", length = 32)
    private String qcResult;

    @Column(name = "factory_number", length = 100)
    private String factoryNumber;

    @Column(name = "origin_place", length = 200)
    private String originPlace;

    @Column(name = "remark", length = 500)
    private String remark;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receive_record_id", referencedColumnName = "id", insertable = false, updatable = false)
    private PurchaseReceiveRecord receiveRecord;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_type_id", referencedColumnName = "id", insertable = false, updatable = false)
    private RawMaterialType materialType;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_batch_id", referencedColumnName = "id", insertable = false, updatable = false)
    private MaterialBatch materialBatch;
}
