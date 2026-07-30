package com.cretas.aims.entity.indicator;

import com.cretas.aims.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 指标历史快照 — 每次重算结果落库一行，构成时间序列。
 *
 * <p><b>Append-only 审计实体</b>: 没有 {@code @Where(clause="deleted_at IS NULL")}，
 * 不参与软删除过滤。历史快照一旦写入永不变更，用于趋势分析、月报、合规审计。
 *
 * <p>period_start / period_end 表示该快照对应的业务时间窗，
 * computed_at 表示快照真正落库的物理时刻。
 *
 * @author Cretas Team
 * @since 2026-05-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "indicator_versions",
        indexes = {
                @Index(name = "idx_iv_factory_indicator", columnList = "factory_id, indicator_id"),
                @Index(name = "idx_iv_period", columnList = "indicator_id, period_start, period_end"),
                @Index(name = "idx_iv_computed_at", columnList = "indicator_id, computed_at")
        }
)
// Intentionally NO @Where — append-only audit log.
public class IndicatorVersion extends BaseEntity {

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

    /** FK to indicators.id */
    @Column(name = "indicator_id", nullable = false, length = 191)
    private String indicatorId;

    /** 业务时间窗起 */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    /** 业务时间窗止 */
    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** 快照取值 */
    @Column(name = "value", nullable = false, precision = 18, scale = 4)
    private BigDecimal value;

    /** 物理计算时刻 */
    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    /** 告警级别命中 (GREEN / YELLOW / RED / null) */
    @Column(name = "alert_level", length = 20)
    private String alertLevel;

    /** 计算源标识 (例如 PYTHON_ENDPOINT:/api/smartbi/analysis/factory/yield-rate) */
    @Column(name = "compute_source", length = 500)
    private String computeSource;

    /**
     * 防止物理删除 — append-only 审计实体。
     *
     * <p>本实体无 {@code @Where(deleted_at IS NULL)}, 故 {@code repo.delete(entity)}
     * 会触发 {@link BaseEntity#softDelete()} 默认 {@code @SQLDelete} 软删除,
     * 但快照已 emit (业务时间窗 + 计算时刻) 不应被任何方式遮盖。
     * {@code @PreRemove} 在物理 DELETE / 软删 DELETE 触发前抛错, 显式阻止。
     */
    @PreRemove
    protected void preventRemove() {
        throw new UnsupportedOperationException(
                "IndicatorVersion is append-only audit; physical removal not allowed");
    }
}
