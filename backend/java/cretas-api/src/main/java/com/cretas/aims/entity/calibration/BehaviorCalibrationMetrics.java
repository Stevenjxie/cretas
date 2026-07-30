package com.cretas.aims.entity.calibration;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.Where;

/**
 * 行为校准指标实体
 * 按时间段聚合的AI工具调用指标数据
 *
 * 核心指标:
 * - 简洁性 (Conciseness): 工具调用的精准度
 * - 执行成功率 (Success Rate): 工具执行的可靠性
 * - 推理效率 (Reasoning Efficiency): 推理过程的紧凑度
 * - 综合得分 (Composite Score): 整体表现评估
 */
@Entity
@Table(name = "behavior_calibration_metrics",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_factory_date_period",
        columnNames = {"factory_id", "metric_date", "period_type"}
    ),
    indexes = {
        @Index(name = "idx_metric_date", columnList = "metric_date"),
        @Index(name = "idx_period_type", columnList = "period_type"),
        @Index(name = "idx_composite_score", columnList = "composite_score")
    }
)
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class BehaviorCalibrationMetrics extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 64)
    private String factoryId;

    @Column(name = "metric_date", nullable = false)
    private LocalDate metricDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false)
    @Builder.Default
    private PeriodType periodType = PeriodType.DAILY;

    @Column(name = "total_calls")
    @Builder.Default
    private Integer totalCalls = 0;

    @Column(name = "successful_calls")
    @Builder.Default
    private Integer successfulCalls = 0;

    @Column(name = "failed_calls")
    @Builder.Default
    private Integer failedCalls = 0;

    @Column(name = "redundant_calls")
    @Builder.Default
    private Integer redundantCalls = 0;

    @Column(name = "recovered_calls")
    @Builder.Default
    private Integer recoveredCalls = 0;

    @Column(name = "conciseness_score", precision = 5, scale = 2)
    private BigDecimal concisenessScore;

    @Column(name = "success_rate", precision = 5, scale = 2)
    private BigDecimal successRate;

    @Column(name = "reasoning_efficiency", precision = 5, scale = 2)
    private BigDecimal reasoningEfficiency;

    @Column(name = "composite_score", precision = 5, scale = 2)
    private BigDecimal compositeScore;

    @Column(name = "total_input_tokens")
    @Builder.Default
    private Long totalInputTokens = 0L;

    @Column(name = "total_output_tokens")
    @Builder.Default
    private Long totalOutputTokens = 0L;

    @Column(name = "avg_execution_time_ms")
    private Integer avgExecutionTimeMs;

    @Column(name = "tool_distribution", columnDefinition = "JSON")
    private String toolDistribution;

    @Column(name = "error_distribution", columnDefinition = "JSON")
    private String errorDistribution;

    /**
     * 统计周期类型
     */
    public enum PeriodType {
        DAILY,      // 日统计
        WEEKLY,     // 周统计
        MONTHLY     // 月统计
    }

    /**
     * 计算所有指标得分
     * 简洁性 = (总调用 - 冗余调用) / 总调用 * 100
     * 成功率 = 成功调用 / 总调用 * 100
     * 综合得分 = 简洁性 * 0.3 + 成功率 * 0.5 + 效率 * 0.2
     */
    public void calculateScores() {
        if (totalCalls == null || totalCalls == 0) {
            this.concisenessScore = BigDecimal.ZERO;
            this.successRate = BigDecimal.ZERO;
            this.compositeScore = BigDecimal.ZERO;
            return;
        }

        // 计算简洁性得分
        int effectiveCalls = totalCalls - (redundantCalls != null ? redundantCalls : 0);
        this.concisenessScore = BigDecimal.valueOf(effectiveCalls)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(totalCalls), 2, BigDecimal.ROUND_HALF_UP);

        // 计算成功率
        int success = successfulCalls != null ? successfulCalls : 0;
        this.successRate = BigDecimal.valueOf(success)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(totalCalls), 2, BigDecimal.ROUND_HALF_UP);

        // 计算推理效率（基于token消耗的相对值，默认基准为1000 tokens/call）
        if (totalInputTokens != null && totalOutputTokens != null && totalCalls > 0) {
            long totalTokens = totalInputTokens + totalOutputTokens;
            double avgTokensPerCall = (double) totalTokens / totalCalls;
            double efficiencyRatio = 1000.0 / Math.max(avgTokensPerCall, 1);
            this.reasoningEfficiency = BigDecimal.valueOf(Math.min(efficiencyRatio * 100, 100))
                .setScale(2, BigDecimal.ROUND_HALF_UP);
        } else {
            this.reasoningEfficiency = BigDecimal.valueOf(50); // 默认中等效率
        }

        // 计算综合得分: 简洁性*0.3 + 成功率*0.5 + 效率*0.2
        this.compositeScore = this.concisenessScore.multiply(BigDecimal.valueOf(0.3))
            .add(this.successRate.multiply(BigDecimal.valueOf(0.5)))
            .add(this.reasoningEfficiency.multiply(BigDecimal.valueOf(0.2)))
            .setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * 增加调用计数
     */
    public void incrementTotalCalls() {
        this.totalCalls = (this.totalCalls == null ? 0 : this.totalCalls) + 1;
    }

    public void incrementSuccessfulCalls() {
        this.successfulCalls = (this.successfulCalls == null ? 0 : this.successfulCalls) + 1;
    }

    public void incrementFailedCalls() {
        this.failedCalls = (this.failedCalls == null ? 0 : this.failedCalls) + 1;
    }

    public void incrementRedundantCalls() {
        this.redundantCalls = (this.redundantCalls == null ? 0 : this.redundantCalls) + 1;
    }

    public void incrementRecoveredCalls() {
        this.recoveredCalls = (this.recoveredCalls == null ? 0 : this.recoveredCalls) + 1;
    }

    public void addTokens(int inputTokens, int outputTokens) {
        this.totalInputTokens = (this.totalInputTokens == null ? 0L : this.totalInputTokens) + inputTokens;
        this.totalOutputTokens = (this.totalOutputTokens == null ? 0L : this.totalOutputTokens) + outputTokens;
    }
}
