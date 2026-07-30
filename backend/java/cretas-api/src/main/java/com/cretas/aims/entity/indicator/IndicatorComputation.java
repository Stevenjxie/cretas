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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 指标计算策略 — 把指标连接到实际的计算源 (SpEL 公式 / Python 端点 / JPQL 查询)。
 *
 * <p>同一指标允许多条计算配置 (按 priority 排序，主备策略)。
 * compute_type 决定 compute_source 的语义:
 * <ul>
 *   <li>FORMULA: compute_source 是 SpEL 表达式 (需走 sandbox)</li>
 *   <li>PYTHON_ENDPOINT: compute_source 是 HTTP 路径，例 /api/smartbi/analysis/factory/yield-rate</li>
 *   <li>JPA_QUERY: compute_source 是 named query 名称</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "indicator_computations",
        indexes = {
                @Index(name = "idx_ic_indicator", columnList = "indicator_id"),
                @Index(name = "idx_ic_type", columnList = "compute_type")
        }
)
@Where(clause = "deleted_at IS NULL")
public class IndicatorComputation extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** FK to indicators.id */
    @Column(name = "indicator_id", nullable = false, length = 191)
    private String indicatorId;

    /** 计算类型: FORMULA / PYTHON_ENDPOINT / JPA_QUERY */
    @Column(name = "compute_type", nullable = false, length = 30)
    private String computeType;

    /** SpEL 表达式 / Python 端点路径 / JPQL named query */
    @Column(name = "compute_source", nullable = false, columnDefinition = "TEXT")
    private String computeSource;

    /** 计算参数 (例如 {"unit":"%","scale":4}) */
    @Type(JsonBinaryType.class)
    @Column(name = "params", columnDefinition = "jsonb")
    private Map<String, Object> params = new HashMap<>();

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** 主备策略优先级，数字越小越优先 */
    @Column(name = "priority", nullable = false)
    private Integer priority = 1;
}
