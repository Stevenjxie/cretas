package com.cretas.aims.entity.indicator;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 指标定义实体（Phase 1 Sprint 1 Day 1）
 *
 * <p>食品行业指标中心 — 工厂良品率 / 餐饮经营 / 质量管控 指标的定义层。
 * 每个指标承载: 唯一编码 (code) / 名称 / 分类 / 单位 / 缓存策略 / 上次计算值。
 * 配套 IndicatorTreeNode (父子) / IndicatorThreshold (阈值) / IndicatorComputation (计算) /
 * IndicatorVersion (历史快照)。
 *
 * @author Cretas Team
 * @since 2026-05-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "indicators",
        indexes = {
                @Index(name = "idx_ind_factory_code", columnList = "factory_id, code", unique = true),
                @Index(name = "idx_ind_category", columnList = "factory_id, category")
        }
)
@Where(clause = "deleted_at IS NULL")
public class Indicator extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 指标编码，例如 FACTORY_YIELD_RATE */
    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 分类: FACTORY / RESTAURANT / QUALITY */
    @Column(name = "category", nullable = false, length = 30)
    private String category;

    @Column(name = "unit", length = 30)
    private String unit;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** 计算策略: REALTIME / CACHED / PRECOMPUTED */
    @Column(name = "compute_strategy", nullable = false, length = 30)
    private String computeStrategy = "CACHED";

    @Column(name = "cache_ttl_seconds")
    private Integer cacheTtlSeconds = 3600;

    @Column(name = "last_value", precision = 18, scale = 4)
    private BigDecimal lastValue;

    @Column(name = "last_computed_at")
    private LocalDateTime lastComputedAt;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Type(JsonBinaryType.class)
    @Column(name = "config", columnDefinition = "jsonb")
    private Map<String, Object> config = new HashMap<>();
}
