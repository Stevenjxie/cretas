package com.cretas.aims.entity.rd;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 报价任务 — 样品审核通过后自动创建，报价人员完成成本测算
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "quotation_tasks",
        indexes = {
                @Index(name = "idx_qt_factory", columnList = "factory_id"),
                @Index(name = "idx_qt_sample", columnList = "sample_id"),
                @Index(name = "idx_qt_status", columnList = "status")
        })
@Where(clause = "deleted_at IS NULL")
public class QuotationTask extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() { if (id == null) id = UUID.randomUUID().toString(); }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "task_number", nullable = false, length = 50)
    private String taskNumber;

    @Column(name = "sample_id", length = 191)
    private String sampleId;

    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    @Column(name = "assigned_to")
    private Long assignedTo;

    /** PENDING / IN_PROGRESS / QUOTED / CONFIRMED */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @PriceSensitive
    @Column(name = "material_cost", precision = 15, scale = 2)
    private BigDecimal materialCost;

    @PriceSensitive
    @Column(name = "labor_cost", precision = 15, scale = 2)
    private BigDecimal laborCost;

    @PriceSensitive
    @Column(name = "overhead_cost", precision = 15, scale = 2)
    private BigDecimal overheadCost;

    @PriceSensitive
    @Column(name = "total_cost", precision = 15, scale = 2)
    private BigDecimal totalCost;

    @PriceSensitive
    @Column(name = "suggested_price", precision = 15, scale = 2)
    private BigDecimal suggestedPrice;

    @PriceSensitive
    @Column(name = "final_price", precision = 15, scale = 2)
    private BigDecimal finalPrice;

    @PriceSensitive
    @Column(name = "profit_margin", precision = 5, scale = 2)
    private BigDecimal profitMargin;

    @Column(name = "quoted_by")
    private Long quotedBy;

    @Column(name = "quoted_at")
    private LocalDateTime quotedAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    // ==================== SP10 扩字段 ====================

    /** 人工成本 元/kg成品 — R&D经验值填写, 非BOM来源 */
    @PriceSensitive
    @Column(name = "labor_per_kg", precision = 12, scale = 4)
    private BigDecimal laborPerKg;

    /**
     * 报价阶段: PRE=预报价 | MID_PENDING=等待中试 | MID=中报价已汇算 | FINAL=最终确认.
     * SampleApprovedEventListener 创建时设 PRE.
     */
    @Column(name = "quote_stage", nullable = false, length = 20)
    private String quoteStage = "PRE";

    /** 自动从 BomRecipe.totalCost 带入, 展示用 */
    @PriceSensitive
    @Column(name = "bom_material_cost", precision = 15, scale = 2)
    private BigDecimal bomMaterialCost;

    /** FK → product_mid_quotes.id, 中报价关联 */
    @Column(name = "mid_quote_id", length = 191)
    private String midQuoteId;
}
