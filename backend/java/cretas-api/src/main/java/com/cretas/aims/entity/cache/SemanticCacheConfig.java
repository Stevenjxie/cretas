package com.cretas.aims.entity.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 语义缓存配置实体
 * 控制缓存行为和阈值
 *
 * 配置层级：
 * - factory_id = '*': 全局默认配置
 * - factory_id = 'F001': 工厂特定配置 (覆盖全局)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-05
 */
@Entity
@Table(name = "semantic_cache_config", indexes = {
    @Index(name = "uk_factory", columnList = "factory_id", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticCacheConfig {

    /**
     * 全局默认配置的工厂ID
     */
    public static final String GLOBAL_CONFIG = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID
     * '*' 表示全局默认配置
     */
    @Column(name = "factory_id", nullable = false, length = 36)
    @Builder.Default
    private String factoryId = GLOBAL_CONFIG;

    /**
     * 语义高置信度阈值 (≥0.85 直接匹配)
     */
    @Column(name = "similarity_threshold", precision = 4, scale = 3)
    @Builder.Default
    private BigDecimal similarityThreshold = new BigDecimal("0.85");

    /**
     * 语义中置信度阈值 (0.72-0.85 融合评分)
     * 低于此阈值的结果需要 LLM 确认
     */
    @Column(name = "medium_threshold", precision = 4, scale = 3)
    @Builder.Default
    private BigDecimal mediumThreshold = new BigDecimal("0.72");

    /**
     * 缓存有效期 (小时)
     * AI-Opt-3: 设置为 1 小时以平衡性能和数据新鲜度
     */
    @Column(name = "cache_ttl_hours")
    @Builder.Default
    private Integer cacheTtlHours = 1;

    /**
     * 最大缓存条目数
     */
    @Column(name = "max_cache_entries")
    @Builder.Default
    private Integer maxCacheEntries = 10000;

    /**
     * Embedding 模型名称
     */
    @Column(name = "embedding_model", length = 100)
    @Builder.Default
    private String embeddingModel = "gte-base-zh";

    /**
     * Embedding 向量维度
     */
    @Column(name = "embedding_dimension")
    @Builder.Default
    private Integer embeddingDimension = 768;

    /**
     * 是否启用语义缓存
     */
    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 仅使用精确匹配 (禁用语义匹配)
     */
    @Column(name = "exact_match_only")
    @Builder.Default
    private Boolean exactMatchOnly = false;

    /**
     * 创建时间
     */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 获取高置信度阈值的 double 值
     */
    public double getSimilarityThresholdAsDouble() {
        return similarityThreshold != null ? similarityThreshold.doubleValue() : 0.85;
    }

    /**
     * 获取中置信度阈值的 double 值
     */
    public double getMediumThresholdAsDouble() {
        return mediumThreshold != null ? mediumThreshold.doubleValue() : 0.72;
    }

    /**
     * 检查是否为全局配置
     */
    public boolean isGlobalConfig() {
        return GLOBAL_CONFIG.equals(factoryId);
    }

    /**
     * 检查是否启用
     */
    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    /**
     * 创建默认配置
     * AI-Opt-3: TTL 设置为 1 小时
     */
    public static SemanticCacheConfig defaultConfig() {
        return SemanticCacheConfig.builder()
            .factoryId(GLOBAL_CONFIG)
            .similarityThreshold(new BigDecimal("0.85"))
            .mediumThreshold(new BigDecimal("0.72"))
            .cacheTtlHours(1)
            .maxCacheEntries(10000)
            .embeddingModel("gte-base-zh")
            .embeddingDimension(768)
            .enabled(true)
            .exactMatchOnly(false)
            .build();
    }
}
