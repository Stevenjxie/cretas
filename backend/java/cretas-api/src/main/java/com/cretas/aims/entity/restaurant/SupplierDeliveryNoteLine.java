package com.cretas.aims.entity.restaurant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 供应商送货单行项 (G7 Tier A)。
 *
 * <p>每行 = 一种食材的进货明细 (OCR 提取或人工录入)。{@code rawMaterialTypeId} 由
 * 软匹配 raw_material_types 填入 (可能为 null, 待人工指认)。</p>
 *
 * <p>不继承 BaseEntity — 表只有 created_at (无 updated_at / deleted_at), 行项随 note 级联删除。</p>
 *
 * @since 2026-06-03 (G7)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "supplier_delivery_note_lines",
        indexes = {
                @Index(name = "idx_sdnl_note_id", columnList = "note_id"),
                @Index(name = "idx_sdnl_factory_material", columnList = "factory_id,raw_material_type_id")
        })
public class SupplierDeliveryNoteLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id", nullable = false)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private SupplierDeliveryNote note;

    @Column(name = "factory_id", nullable = false, length = 100)
    private String factoryId;

    @Column(name = "ingredient_name", nullable = false, length = 200)
    private String ingredientName;

    /** 软匹配到的 raw_material_types.id (可能 null, 待人工指认)。 */
    @Column(name = "raw_material_type_id", length = 191)
    private String rawMaterialTypeId;

    @Column(name = "quantity", precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "unit_price", precision = 12, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "line_amount", precision = 15, scale = 2)
    private BigDecimal lineAmount;

    @Column(name = "qc_result", length = 50)
    private String qcResult;

    @Column(name = "material_batch_id", length = 191)
    private String materialBatchId;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    /** 行级置信度 (OCR)。 */
    @Column(name = "ocr_confidence", precision = 4, scale = 3)
    private BigDecimal ocrConfidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
