package com.cretas.aims.entity.factory;

import com.cretas.aims.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 工厂物料需求单明细行 — 从 BOM 展开而来, 每行对应一种原/辅/包装材料.
 *
 * @since 2026-04-08 (W2-3)
 */
@Data
@EqualsAndHashCode(callSuper = true, exclude = "requisition")
@ToString(callSuper = true, exclude = "requisition")
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "requisition"})
@Entity
@Table(name = "factory_material_requisition_items",
        indexes = {
                @Index(name = "idx_fmri_req", columnList = "requisition_id"),
                @Index(name = "idx_fmri_material", columnList = "material_type_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class FactoryMaterialRequisitionItem extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requisition_id", nullable = false)
    private FactoryMaterialRequisition requisition;

    @Column(name = "material_type_id", nullable = false, length = 64)
    private String materialTypeId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    @Enumerated(EnumType.STRING)
    @Column(name = "material_category", length = 20)
    private MaterialCategory materialCategory = MaterialCategory.RAW;

    @Column(name = "bom_item_id")
    private Long bomItemId;

    @Column(name = "required_qty", precision = 15, scale = 3)
    private BigDecimal requiredQty;

    @Column(name = "picked_qty", precision = 15, scale = 3)
    private BigDecimal pickedQty;

    @Column(name = "issued_qty", precision = 15, scale = 3)
    private BigDecimal issuedQty;

    @Column(name = "consumed_qty", precision = 15, scale = 3)
    private BigDecimal consumedQty;

    @Column(name = "returned_qty", precision = 15, scale = 3)
    private BigDecimal returnedQty;

    @Column(name = "unit", length = 20)
    private String unit;

    /** FEFO 锁定的批次列表 [{batchNo, qty}]. */
    @Type(JsonBinaryType.class)
    @Column(name = "batch_numbers", columnDefinition = "jsonb")
    private List<Map<String, Object>> batchNumbers;

    public enum MaterialCategory {
        RAW,        // 原料
        AUXILIARY,  // 辅料
        PACKAGING,  // 包装
        SEMI_FINISHED // 半成品
    }
}
