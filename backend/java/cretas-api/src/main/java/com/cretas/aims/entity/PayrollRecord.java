package com.cretas.aims.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 工资记录实体
 * 用于记录工人的工资结算数据，支持计件工资和综合工资计算
 *
 * 结算周期类型:
 * - DAILY: 日结
 * - WEEKLY: 周结
 * - MONTHLY: 月结
 *
 * 工资状态:
 * - PENDING: 待审核
 * - APPROVED: 已审核
 * - PAID: 已发放
 *
 * 效率评级:
 * - A: 优秀 (效率 >= 120%)
 * - B: 良好 (效率 >= 100%)
 * - C: 合格 (效率 >= 80%)
 * - D: 待提升 (效率 < 80%)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "payroll_records",
       indexes = {
           @Index(name = "idx_payroll_factory", columnList = "factory_id"),
           @Index(name = "idx_payroll_worker", columnList = "worker_id"),
           @Index(name = "idx_payroll_period_start", columnList = "period_start"),
           @Index(name = "idx_payroll_status", columnList = "status"),
           @Index(name = "idx_payroll_factory_period", columnList = "factory_id, period_start, period_end")
       }
)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class PayrollRecord extends BaseEntity {

    /** Sprint3-E F-VFLAG-1: 凭证生成状态. UNCREATED → PENDING → CREATED / FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "vflag", nullable = false, length = 20)
    private com.cretas.aims.entity.enums.VoucherFlag vflag = com.cretas.aims.entity.enums.VoucherFlag.UNCREATED;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 工人ID
     */
    @Column(name = "worker_id", nullable = false)
    private Long workerId;

    /**
     * 工人姓名 (冗余存储便于查询)
     */
    @Column(name = "worker_name", nullable = false, length = 50)
    private String workerName;

    // ==================== 周期字段 ====================

    /**
     * 结算周期开始日期
     */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    /**
     * 结算周期结束日期
     */
    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /**
     * 结算周期类型
     * DAILY: 日结, WEEKLY: 周结, MONTHLY: 月结
     */
    @Column(name = "period_type", nullable = false, length = 20)
    private String periodType;

    // ==================== 计件工资字段 ====================

    /**
     * 总计件数
     */
    @Column(name = "total_piece_count")
    private Integer totalPieceCount;

    /**
     * 计件工资金额
     */
    @Column(name = "piece_rate_wage", precision = 12, scale = 2)
    private BigDecimal pieceRateWage;

    /**
     * 使用的计件规则ID
     */
    @Column(name = "piece_rule_id")
    private Long pieceRuleId;

    // ==================== 其他工资字段 ====================

    /**
     * 基本工资
     */
    @Column(name = "base_salary", precision = 12, scale = 2)
    private BigDecimal baseSalary;

    /**
     * 加班工资
     */
    @Column(name = "overtime_wage", precision = 12, scale = 2)
    private BigDecimal overtimeWage;

    /**
     * 加班时长 (小时)
     */
    @Column(name = "overtime_hours", precision = 8, scale = 2)
    private BigDecimal overtimeHours;

    /**
     * 奖金
     */
    @Column(name = "bonus_amount", precision = 12, scale = 2)
    private BigDecimal bonusAmount;

    /**
     * 扣款
     */
    @Column(name = "deduction_amount", precision = 12, scale = 2)
    private BigDecimal deductionAmount;

    /**
     * 总工资
     */
    @Column(name = "total_wage", precision = 12, scale = 2)
    private BigDecimal totalWage;

    // ==================== 效率相关 ====================

    /**
     * 平均效率 (件/小时)
     */
    @Column(name = "average_efficiency", precision = 8, scale = 2)
    private BigDecimal averageEfficiency;

    /**
     * 总工作时长 (小时)
     */
    @Column(name = "total_work_hours", precision = 8, scale = 2)
    private BigDecimal totalWorkHours;

    /**
     * 效率评级
     * A: 优秀, B: 良好, C: 合格, D: 待提升
     */
    @Column(name = "efficiency_rating", length = 1)
    private String efficiencyRating;

    // ==================== 审核字段 ====================

    /**
     * 工资记录状态
     * PENDING: 待审核, APPROVED: 已审核, PAID: 已发放
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /**
     * 审核人ID
     */
    @Column(name = "approved_by")
    private Long approvedBy;

    /**
     * 审核时间
     */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /**
     * 发放时间
     */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /**
     * 备注
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ==================== 辅助方法 ====================

    /**
     * 计算并设置总工资
     * 总工资 = 基本工资 + 计件工资 + 加班工资 + 奖金 - 扣款
     */
    public void calculateTotalWage() {
        BigDecimal total = BigDecimal.ZERO;

        if (baseSalary != null) {
            total = total.add(baseSalary);
        }
        if (pieceRateWage != null) {
            total = total.add(pieceRateWage);
        }
        if (overtimeWage != null) {
            total = total.add(overtimeWage);
        }
        if (bonusAmount != null) {
            total = total.add(bonusAmount);
        }
        if (deductionAmount != null) {
            total = total.subtract(deductionAmount);
        }

        this.totalWage = total;
    }

    /**
     * 在持久化和更新前自动计算总工资
     */
    @PrePersist
    @PreUpdate
    protected void prePersistOrUpdate() {
        calculateTotalWage();
    }
}
