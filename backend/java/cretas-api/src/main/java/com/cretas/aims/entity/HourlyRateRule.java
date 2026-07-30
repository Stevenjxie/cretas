package com.cretas.aims.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sprint 6 Track W4-B: 时薪规则 (HourlyRateRule).
 *
 * <p>用于 {@link com.cretas.aims.entity.enums.WageMode#HOURLY} 和 {@link com.cretas.aims.entity.enums.WageMode#MIXED}
 * mode 工资计算. 一员工可有多条规则 (历史变动), 通过 effectiveFrom/effectiveTo 区分.
 *
 * <p>计算公式 (HOURLY mode):
 * <pre>
 *   工时 × baseHourlyRate
 *   + 加班工时 × baseHourlyRate × overtimeMultiplier
 * </pre>
 *
 * <p>查询顺序: 同员工 effective 期间最新一条 (effectiveTo IS NULL OR effectiveTo &gt;= today).
 *
 * <p>防呆 (per fool-proof-design.md Rule 1):
 * baseHourlyRate ≤ ¥500/h (UI 端 :max="500" 防误输, 实际范围 ¥10-¥200 居多).
 *
 * @since 2026-05-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "hourly_rate_rule",
       indexes = {
           @Index(name = "idx_hourly_rate_factory", columnList = "factory_id"),
           @Index(name = "idx_hourly_rate_employee", columnList = "factory_id,employee_id"),
           @Index(name = "idx_hourly_rate_effective", columnList = "factory_id,employee_id,effective_from,effective_to")
       })
@Where(clause = "deleted_at IS NULL")
public class HourlyRateRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    /**
     * 基础时薪 (元/小时). 防呆: UI 端 :max="500".
     */
    @Column(name = "base_hourly_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal baseHourlyRate;

    /**
     * 加班倍率 (默认 1.5).
     */
    @Column(name = "overtime_multiplier", precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal overtimeMultiplier = new BigDecimal("1.5");

    /**
     * 生效日期 (规则启用起始日).
     */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /**
     * 失效日期 (null = 长期有效).
     */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /**
     * 是否启用 (临时禁用而不删除).
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 备注.
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ============ 辅助方法 ============

    /**
     * 检查规则是否在指定日期有效.
     */
    public boolean isEffectiveOn(LocalDate date) {
        if (date == null || !Boolean.TRUE.equals(isActive)) {
            return false;
        }
        boolean afterStart = effectiveFrom == null || !date.isBefore(effectiveFrom);
        boolean beforeEnd = effectiveTo == null || !date.isAfter(effectiveTo);
        return afterStart && beforeEnd;
    }
}
