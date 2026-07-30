package com.cretas.aims.entity.intent;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跨工厂关键词采用实体
 * 追踪同一关键词在不同工厂的使用情况
 */
@Entity
@Table(name = "keyword_factory_adoption",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_intent_keyword_factory",
        columnNames = {"intent_code", "keyword", "factory_id"}
    ),
    indexes = {
        @Index(name = "idx_intent_keyword", columnList = "intent_code, keyword"),
        @Index(name = "idx_factory_id", columnList = "factory_id"),
        @Index(name = "idx_effectiveness", columnList = "effectiveness_score"),
        @Index(name = "idx_is_promoted", columnList = "is_promoted")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KeywordFactoryAdoption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "intent_code", nullable = false, length = 100)
    private String intentCode;

    @Column(name = "keyword", nullable = false, length = 255)
    private String keyword;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    // 效果统计
    @Column(name = "effectiveness_score", precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal effectivenessScore = BigDecimal.ONE;

    @Column(name = "usage_count")
    @Builder.Default
    private Integer usageCount = 0;

    // 状态
    @Column(name = "is_disabled")
    @Builder.Default
    private Boolean isDisabled = false;

    @Column(name = "disabled_at")
    private LocalDateTime disabledAt;

    @Column(name = "disabled_reason", length = 500)
    private String disabledReason;

    // 晋升相关
    @Column(name = "is_promoted")
    @Builder.Default
    private Boolean isPromoted = false;

    @Column(name = "promoted_at")
    private LocalDateTime promotedAt;

    // 审计字段
    @Column(name = "adopted_at", updatable = false)
    private LocalDateTime adoptedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        adoptedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 禁用关键词
     *
     * @param reason 禁用原因
     */
    public void disable(String reason) {
        this.isDisabled = true;
        this.disabledAt = LocalDateTime.now();
        this.disabledReason = reason;
    }

    /**
     * 启用关键词
     */
    public void enable() {
        this.isDisabled = false;
        this.disabledAt = null;
        this.disabledReason = null;
    }

    /**
     * 标记为已晋升
     */
    public void markPromoted() {
        this.isPromoted = true;
        this.promotedAt = LocalDateTime.now();
    }

    /**
     * 增加使用次数
     */
    public void incrementUsage() {
        this.usageCount++;
    }

    /**
     * 更新效果评分
     *
     * @param score 新的效果评分
     */
    public void updateEffectiveness(BigDecimal score) {
        this.effectivenessScore = score;
    }
}
