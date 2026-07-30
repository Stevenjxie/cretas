package com.cretas.aims.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDate;
import org.hibernate.annotations.Where;

/**
 * AI配额使用实体 - 管理工厂每周AI分析配额
 *
 * 配额规则：
 * - 每个工厂每周100次配额
 * - 仅follow-up问题消耗配额
 * - 定时任务生成的周报/月报不消耗配额
 * - 历史综合报告消耗5倍配额（500次/次）
 * - 每周一0点自动重置配额
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-11-04
 */
@Entity
@Table(name = "ai_quota_usage",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_factory_week", columnNames = {"factory_id", "week_start"})
       },
       indexes = {
           @Index(name = "idx_factory_id", columnList = "factory_id"),
           @Index(name = "idx_week_start", columnList = "week_start")
       })
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class AIQuotaUsage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 周开始日期（周一日期，用于标识周）
     */
    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    /**
     * 已使用次数（仅计算follow-up问题）
     */
    @Builder.Default
    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    /**
     * 配额上限（默认100次/周）
     */
    @Builder.Default
    @Column(name = "quota_limit", nullable = false)
    private Integer quotaLimit = 100;

    /**
     * 是否已超额
     */
    @Transient
    public Boolean isExceeded() {
        return usedCount >= quotaLimit;
    }

    /**
     * 剩余配额
     */
    @Transient
    public Integer getRemainingQuota() {
        return Math.max(0, quotaLimit - usedCount);
    }

    /**
     * 使用率（百分比）
     */
    @Transient
    public Double getUsageRate() {
        return quotaLimit > 0 ? (usedCount * 100.0 / quotaLimit) : 0.0;
    }
}
