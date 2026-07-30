package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;
import java.math.BigDecimal;
/**
 * 工作类型实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory"})
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "work_types",
       indexes = {
           @Index(name = "idx_work_type_factory", columnList = "factory_id"),
           @Index(name = "idx_work_type_code", columnList = "factory_id,code"),
           @Index(name = "idx_work_type_active", columnList = "factory_id,is_active")
       })
@Where(clause = "deleted_at IS NULL")
public class WorkType extends BaseEntity {
    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;
    @Column(name = "factory_id", nullable = false)
    private String factoryId;
    @Column(name = "name", nullable = false, length = 50)
    private String name;
    @Column(name = "code", length = 20)
    private String code;
    @Column(name = "type_code", nullable = false, length = 191)
    private String typeCode;
    @Column(name = "type_name", nullable = false, length = 191)
    private String typeName;
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    @Column(name = "department", length = 50)
    private String department;
    @Column(name = "hourly_rate", precision = 8, scale = 2)
    private BigDecimal hourlyRate;
    @Column(name = "billing_type", length = 20)
    private String billingType; // HOURLY, PIECE, DAILY, MONTHLY
    @Column(name = "base_rate", precision = 10, scale = 2)
    private BigDecimal baseRate;
    @Column(name = "overtime_rate_multiplier", precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal overtimeRateMultiplier = new BigDecimal("1.5");
    @Column(name = "holiday_rate_multiplier", precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal holidayRateMultiplier = new BigDecimal("2.0");
    @Column(name = "night_shift_rate_multiplier", precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal nightShiftRateMultiplier = new BigDecimal("1.3");
    @Column(name = "hazard_level", nullable = false)
    @Builder.Default
    private Integer hazardLevel = 0; // 0-5 危险等级
    @Column(name = "certification_required")
    @Builder.Default
    private Boolean certificationRequired = false;
    @Column(name = "required_skills", columnDefinition = "TEXT")
    private String requiredSkills; // JSON array of required skills
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;
    @Column(name = "color", length = 20)
    private String color;
    @Column(name = "icon", length = 50)
    private String icon;
    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;
    // 辅助方法
    /**
     * 计算加班工资
     */
    public BigDecimal calculateOvertimePay(BigDecimal hours) {
        if (baseRate == null || hours == null) {
            return BigDecimal.ZERO;
        }
        return baseRate.multiply(overtimeRateMultiplier).multiply(hours);
    }
    /**
     * 计算假期工资
     */
    public BigDecimal calculateHolidayPay(BigDecimal hours) {
        if (baseRate == null || hours == null) {
            return BigDecimal.ZERO;
        }
        return baseRate.multiply(holidayRateMultiplier).multiply(hours);
    }

    /**
     * 计算夜班工资
     */
    public BigDecimal calculateNightShiftPay(BigDecimal hours) {
        if (baseRate == null || hours == null) {
            return BigDecimal.ZERO;
        }
        return baseRate.multiply(nightShiftRateMultiplier).multiply(hours);
    }
}
