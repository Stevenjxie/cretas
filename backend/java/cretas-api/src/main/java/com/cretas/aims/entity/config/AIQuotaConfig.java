package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;

/**
 * AI配额规则配置实体
 *
 * 用于配置化不同问题类型的AI配额消耗规则：
 * - 问题类型的配额消耗次数
 * - 工厂级别的配额限制覆盖
 * - 全局默认配置 (factory_id = '*')
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Entity
@Table(name = "ai_quota_configs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id", "question_type"}))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIQuotaConfig extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /**
     * 工厂ID
     * "*" 表示全局默认配置
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 问题类型
     * historical - 历史数据分析
     * comparison - 批次对比分析
     * time_range - 时间范围查询
     * followup - Follow-up问题
     * default - 简单查询/默认
     */
    @Column(name = "question_type", nullable = false, length = 50)
    private String questionType;

    /**
     * 配额消耗次数
     */
    @Column(name = "quota_cost", nullable = false)
    @Builder.Default
    private Integer quotaCost = 1;

    /**
     * 每周配额限制
     * null 表示使用全局默认限制 (100次)
     */
    @Column(name = "weekly_limit")
    private Integer weeklyLimit;

    /**
     * 规则描述
     */
    @Column(name = "description", length = 200)
    private String description;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 优先级 (数值越大优先级越高)
     * 用于匹配规则时的优先级排序
     */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

    /**
     * 获取有效的每周配额限制
     * 如果工厂级别配置了 weeklyLimit，使用工厂配置
     * 否则使用全局默认 100
     */
    public int getEffectiveWeeklyLimit() {
        return weeklyLimit != null ? weeklyLimit : 100;
    }

    /**
     * 判断是否为全局配置
     */
    public boolean isGlobalConfig() {
        return "*".equals(factoryId);
    }
}
