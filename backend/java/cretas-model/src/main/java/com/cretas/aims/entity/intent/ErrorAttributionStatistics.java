package com.cretas.aims.entity.intent;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.Where;

/**
 * 错误归因统计实体（按日汇总）
 *
 * 汇总每日的意图识别统计数据，包括:
 * - 请求总数和成功率
 * - 信号分布（强/弱信号）
 * - 用户确认统计
 * - 执行结果统计
 * - 错误归因分布
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Entity
@Table(name = "error_attribution_statistics",
       indexes = {
           @Index(name = "idx_stat_date", columnList = "stat_date")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_factory_date", columnNames = {"factory_id", "stat_date"})
       })
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class ErrorAttributionStatistics extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(name = "id", length = 36)
    private String id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 20)
    private String factoryId;

    /**
     * 统计日期
     */
    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    // ==================== 总体统计 ====================

    /**
     * 总请求数
     */
    @Builder.Default
    @Column(name = "total_requests", nullable = false)
    private Integer totalRequests = 0;

    /**
     * 成功匹配数
     */
    @Builder.Default
    @Column(name = "matched_count", nullable = false)
    private Integer matchedCount = 0;

    /**
     * 未匹配数
     */
    @Builder.Default
    @Column(name = "unmatched_count", nullable = false)
    private Integer unmatchedCount = 0;

    /**
     * LLM Fallback次数
     */
    @Builder.Default
    @Column(name = "llm_fallback_count", nullable = false)
    private Integer llmFallbackCount = 0;

    // ==================== 信号分布 ====================

    /**
     * 强信号数
     */
    @Builder.Default
    @Column(name = "strong_signal_count", nullable = false)
    private Integer strongSignalCount = 0;

    /**
     * 弱信号数
     */
    @Builder.Default
    @Column(name = "weak_signal_count", nullable = false)
    private Integer weakSignalCount = 0;

    // ==================== 确认统计 ====================

    /**
     * 请求确认数
     */
    @Builder.Default
    @Column(name = "confirmation_requested", nullable = false)
    private Integer confirmationRequested = 0;

    /**
     * 用户确认数
     */
    @Builder.Default
    @Column(name = "user_confirmed_count", nullable = false)
    private Integer userConfirmedCount = 0;

    /**
     * 用户拒绝数
     */
    @Builder.Default
    @Column(name = "user_rejected_count", nullable = false)
    private Integer userRejectedCount = 0;

    // ==================== 执行统计 ====================

    /**
     * 成功执行数
     */
    @Builder.Default
    @Column(name = "executed_count", nullable = false)
    private Integer executedCount = 0;

    /**
     * 执行失败数
     */
    @Builder.Default
    @Column(name = "failed_count", nullable = false)
    private Integer failedCount = 0;

    /**
     * 取消数
     */
    @Builder.Default
    @Column(name = "cancelled_count", nullable = false)
    private Integer cancelledCount = 0;

    // ==================== 错误归因分布 ====================

    /**
     * 规则缺失数
     */
    @Builder.Default
    @Column(name = "rule_miss_count", nullable = false)
    private Integer ruleMissCount = 0;

    /**
     * 歧义匹配数
     */
    @Builder.Default
    @Column(name = "ambiguous_count", nullable = false)
    private Integer ambiguousCount = 0;

    /**
     * 误匹配数
     */
    @Builder.Default
    @Column(name = "false_positive_count", nullable = false)
    private Integer falsePositiveCount = 0;

    /**
     * 用户取消数
     */
    @Builder.Default
    @Column(name = "user_cancel_count", nullable = false)
    private Integer userCancelCount = 0;

    /**
     * 系统错误数
     */
    @Builder.Default
    @Column(name = "system_error_count", nullable = false)
    private Integer systemErrorCount = 0;

    // ==================== 按分类统计 (JSON) ====================

    /**
     * 按意图分类的统计 {category: {count, successRate}}
     */
    @Column(name = "intent_category_stats", columnDefinition = "JSON")
    private String intentCategoryStats;

    /**
     * 按匹配方法的统计 {method: {count, avgConfidence}}
     */
    @Column(name = "match_method_stats", columnDefinition = "JSON")
    private String matchMethodStats;

    // ==================== 置信度分布 ====================

    /**
     * 平均置信度
     */
    @Column(name = "avg_confidence", precision = 5, scale = 4)
    private BigDecimal avgConfidence;

    /**
     * 置信度分布 {range: count}
     */
    @Column(name = "confidence_distribution", columnDefinition = "JSON")
    private String confidenceDistribution;

    // ==================== 便捷方法 ====================

    /**
     * 计算匹配成功率
     */
    public double getMatchSuccessRate() {
        if (totalRequests == 0) return 0.0;
        return (double) matchedCount / totalRequests;
    }

    /**
     * 计算执行成功率
     */
    public double getExecutionSuccessRate() {
        int totalExecutions = executedCount + failedCount;
        if (totalExecutions == 0) return 0.0;
        return (double) executedCount / totalExecutions;
    }

    /**
     * 计算用户确认率
     */
    public double getUserConfirmationRate() {
        int totalConfirmations = userConfirmedCount + userRejectedCount;
        if (totalConfirmations == 0) return 0.0;
        return (double) userConfirmedCount / totalConfirmations;
    }
}
