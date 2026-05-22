package com.cretas.aims.entity.indicator;

import com.cretas.aims.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * 指标阈值 — GREEN/YELLOW/RED 三色预警，比较运算符 GT/GTE/LT/LTE。
 *
 * <p>例: FACTORY_YIELD_RATE 配置三条记录:
 * GREEN GTE 95 / YELLOW GTE 90 / RED LT 90 — 实际值落在对应区间触发对应告警级别。
 *
 * @author Cretas Team
 * @since 2026-05-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "indicator_thresholds",
        indexes = {
                @Index(name = "idx_ith_factory", columnList = "factory_id"),
                @Index(name = "idx_ith_indicator", columnList = "indicator_id"),
                @Index(name = "idx_ith_level", columnList = "indicator_id, alert_level")
        }
)
@Where(clause = "deleted_at IS NULL")
public class IndicatorThreshold extends BaseEntity {

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

    /** 告警级别: GREEN / YELLOW / RED */
    @Column(name = "alert_level", nullable = false, length = 20)
    private String alertLevel;

    /** 运算符: GT / GTE / LT / LTE / EQ / BETWEEN */
    @Column(name = "operator", nullable = false, length = 10)
    private String operator;

    /** 阈值数值 (BETWEEN 时用 threshold_value 作下限) */
    @Column(name = "threshold_value", nullable = false, precision = 18, scale = 4)
    private BigDecimal thresholdValue;

    /** BETWEEN 上限 (其它运算符忽略) */
    @Column(name = "threshold_value_upper", precision = 18, scale = 4)
    private BigDecimal thresholdValueUpper;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
