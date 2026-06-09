package com.cretas.aims.entity.rd;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 产品中报价 — 试制批次成本汇算结果 (SP10).
 *
 * <p>三价对比链路: 预报价 (QuotationTask.totalCost) → 中报价 (本实体) → 最终成本价 (实际生产均价).</p>
 *
 * <p>成本字段 ≥5 个 @PriceSensitive — 营销/销售角色自动脱敏 (PriceFieldResponseAdvice).</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_mid_quotes",
        indexes = {
                @Index(name = "idx_pmq_factory", columnList = "factory_id"),
                @Index(name = "idx_pmq_sample", columnList = "sample_id"),
                @Index(name = "idx_pmq_batch", columnList = "trial_batch_id")
        })
@Where(clause = "deleted_at IS NULL")
public class ProductMidQuote extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() { if (id == null) id = UUID.randomUUID().toString(); }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    /** 关联的研发试样 business key */
    @Column(name = "sample_id", length = 191)
    private String sampleId;

    /** 关联的试制批次 ID (production_batches.id) */
    @Column(name = "trial_batch_id")
    private Long trialBatchId;

    /** 关联的报价任务 ID */
    @Column(name = "quotation_task_id", length = 191)
    private String quotationTaskId;

    // ==================== 试制成本分项 ====================

    /**
     * 原材料成本 元/kg成品 — 来自试制批次领料折算.
     * 若试制批次领料记录不完整, 此字段为 null (诚实 null, 不填 0).
     */
    @PriceSensitive
    @Column(name = "material_cost_per_kg", precision = 12, scale = 4)
    private BigDecimal materialCostPerKg;

    /**
     * 人工成本 元/kg成品 — 来自试制批次报工或 R&D 经验值.
     */
    @PriceSensitive
    @Column(name = "labor_cost_per_kg", precision = 12, scale = 4)
    private BigDecimal laborCostPerKg;

    /**
     * 制造费用 (overhead) 元/kg成品.
     */
    @PriceSensitive
    @Column(name = "overhead_cost_per_kg", precision = 12, scale = 4)
    private BigDecimal overheadCostPerKg;

    /**
     * 中报价综合成本 元/kg成品 = 材料 + 人工 + 制造费用.
     * 任一分项为 null 时此字段为 null (诚实空值, 不做加法).
     */
    @PriceSensitive
    @Column(name = "total_cost_per_kg", precision = 12, scale = 4)
    private BigDecimal totalCostPerKg;

    /**
     * 试制产出量 kg — 用于核算整批成本.
     */
    @Column(name = "trial_output_kg", precision = 12, scale = 4)
    private BigDecimal trialOutputKg;

    // ==================== 方差与预警 ====================

    /**
     * 与预报价偏差率 % (中报价 vs 预报价).
     * null = 预报价数据缺失无法计算.
     */
    @PriceSensitive
    @Column(name = "cost_variance_pct", precision = 8, scale = 4)
    private BigDecimal costVariancePct;

    /**
     * 是否触发成本超限预警.
     * true = |偏差率| > threshold (由调用方传入).
     */
    @Column(name = "variance_alert", nullable = false)
    private boolean varianceAlert = false;

    /**
     * 触发预警时的阈值 %.
     */
    @Column(name = "variance_threshold_pct", precision = 8, scale = 4)
    private BigDecimal varianceThresholdPct;

    // ==================== 状态管理 ====================

    /**
     * PENDING=待汇算 | CALCULATED=已汇算 | CONFIRMED=已确认.
     */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
