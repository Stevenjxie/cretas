package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.Where;

/**
 * 计件单价规则实体类
 * 支持阶梯计件定价，根据产量区间设置不同单价
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"workType", "factory"})
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "piece_rate_rules",
       indexes = {
           @Index(name = "idx_piece_rate_factory", columnList = "factory_id"),
           @Index(name = "idx_piece_rate_work_type", columnList = "work_type_id"),
           @Index(name = "idx_piece_rate_product_type", columnList = "product_type_id"),
           @Index(name = "idx_piece_rate_process_stage", columnList = "factory_id,process_stage_type"),
           @Index(name = "idx_piece_rate_active", columnList = "factory_id,is_active"),
           @Index(name = "idx_piece_rate_effective", columnList = "factory_id,effective_from,effective_to"),
           @Index(name = "idx_piece_rate_priority", columnList = "factory_id,priority")
       })
@Where(clause = "deleted_at IS NULL")
public class PieceRateRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "work_type_id", length = 191)
    private String workTypeId;

    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    @Column(name = "process_stage_type", length = 50)
    private String processStageType;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ============ 阶梯计件规则字段 ============

    /**
     * 第一阶梯数量阈值 (如 0-300件，此字段为300)
     */
    @Column(name = "tier1_threshold")
    @Builder.Default
    private Integer tier1Threshold = 0;

    /**
     * 第一阶梯单价 (元/件)
     */
    @Column(name = "tier1_rate", precision = 10, scale = 4)
    private BigDecimal tier1Rate;

    /**
     * 第二阶梯数量阈值 (如 301-400件，此字段为400)
     */
    @Column(name = "tier2_threshold")
    private Integer tier2Threshold;

    /**
     * 第二阶梯单价 (元/件)
     */
    @Column(name = "tier2_rate", precision = 10, scale = 4)
    private BigDecimal tier2Rate;

    /**
     * 第三阶梯数量阈值 (400件以上)
     */
    @Column(name = "tier3_threshold")
    private Integer tier3Threshold;

    /**
     * 第三阶梯单价 (元/件)
     */
    @Column(name = "tier3_rate", precision = 10, scale = 4)
    private BigDecimal tier3Rate;

    /**
     * 最大阶梯数 (默认3)
     */
    @Column(name = "max_tier_count")
    @Builder.Default
    private Integer maxTierCount = 3;

    // ============ 配置字段 ============

    /**
     * 生效日期
     */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /**
     * 失效日期
     */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /**
     * 是否启用
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 优先级 (用于多规则匹配时，数值越大优先级越高)
     */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

    // ============ 关联关系 ============

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_type_id", referencedColumnName = "id", insertable = false, updatable = false)
    private WorkType workType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    // ============ 辅助方法 ============

    /**
     * 根据件数计算阶梯工资
     *
     * 计算逻辑示例 (假设 tier1=300@0.5元, tier2=400@0.6元, tier3=无限@0.7元):
     * - 件数250: 250 * 0.5 = 125元
     * - 件数350: 300 * 0.5 + 50 * 0.6 = 150 + 30 = 180元
     * - 件数500: 300 * 0.5 + 100 * 0.6 + 100 * 0.7 = 150 + 60 + 70 = 280元
     *
     * @param pieceCount 完成件数
     * @return 计算后的工资金额
     */
    public BigDecimal calculateWage(int pieceCount) {
        if (pieceCount <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalWage = BigDecimal.ZERO;
        int remainingPieces = pieceCount;

        // 第一阶梯计算
        if (tier1Rate != null && remainingPieces > 0) {
            int tier1Limit = (tier1Threshold != null && tier1Threshold > 0) ? tier1Threshold : Integer.MAX_VALUE;
            int tier1Pieces = Math.min(remainingPieces, tier1Limit);
            totalWage = totalWage.add(tier1Rate.multiply(BigDecimal.valueOf(tier1Pieces)));
            remainingPieces -= tier1Pieces;
        }

        // 第二阶梯计算
        if (tier2Rate != null && remainingPieces > 0 && tier2Threshold != null) {
            int tier2Limit = tier2Threshold - (tier1Threshold != null ? tier1Threshold : 0);
            int tier2Pieces = Math.min(remainingPieces, tier2Limit);
            totalWage = totalWage.add(tier2Rate.multiply(BigDecimal.valueOf(tier2Pieces)));
            remainingPieces -= tier2Pieces;
        }

        // 第三阶梯计算 (超出部分)
        if (tier3Rate != null && remainingPieces > 0) {
            totalWage = totalWage.add(tier3Rate.multiply(BigDecimal.valueOf(remainingPieces)));
        }

        return totalWage;
    }

    /**
     * 检查规则是否在指定日期有效
     *
     * @param date 检查日期
     * @return 是否有效
     */
    public boolean isEffectiveOn(LocalDate date) {
        if (date == null || !Boolean.TRUE.equals(isActive)) {
            return false;
        }

        boolean afterStart = effectiveFrom == null || !date.isBefore(effectiveFrom);
        boolean beforeEnd = effectiveTo == null || !date.isAfter(effectiveTo);

        return afterStart && beforeEnd;
    }

    /**
     * 获取指定件数对应的单价阶梯
     *
     * @param pieceCount 件数
     * @return 对应的单价，如果无法匹配返回null
     */
    public BigDecimal getRateForPieceCount(int pieceCount) {
        if (pieceCount <= 0) {
            return null;
        }

        // 检查是否在第一阶梯
        if (tier1Threshold != null && pieceCount <= tier1Threshold) {
            return tier1Rate;
        }

        // 检查是否在第二阶梯
        if (tier2Threshold != null && pieceCount <= tier2Threshold) {
            return tier2Rate;
        }

        // 超出第二阶梯，返回第三阶梯单价
        return tier3Rate;
    }
}
