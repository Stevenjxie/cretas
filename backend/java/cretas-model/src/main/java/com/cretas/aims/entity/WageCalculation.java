package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.WageMode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sprint 6 Track W4-B: 工资计算明细 (WageCalculation).
 *
 * <p>每次 {@link com.cretas.aims.service.WageCalculationService#calculateMonthly} 执行后
 * 保存一条 (factoryId, employeeId, periodMonth) 计算结果, 用于:
 * <ul>
 *   <li>审计 / debug "为什么这个员工这月 ¥X" — 显 mode + 各分项</li>
 *   <li>月底 schedule 批量计算后 → 批量生成 PayrollRecord / Voucher</li>
 *   <li>重复 calc idempotent: 同 (factoryId, employeeId, periodMonth) upsert</li>
 * </ul>
 *
 * <p><b>derived getter {@link #getTotalAmount()}</b>:
 * <ul>
 *   <li>PIECE_RATE: pieceRateAmount</li>
 *   <li>HOURLY: hourlyAmount + overtimeAmount</li>
 *   <li>MIXED: hourlyAmount + overtimeAmount + pieceRateAmount</li>
 * </ul>
 *
 * <p>periodMonth 用 LocalDate (该月 1 号) 表示, 例 2026-05-01 = 2026 年 5 月.
 *
 * @since 2026-05-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "wage_calculation",
       indexes = {
           @Index(name = "idx_wage_calc_factory", columnList = "factory_id"),
           @Index(name = "idx_wage_calc_employee", columnList = "factory_id,employee_id"),
           @Index(name = "idx_wage_calc_period", columnList = "factory_id,period_month"),
           @Index(name = "idx_wage_calc_employee_period",
                  columnList = "factory_id,employee_id,period_month",
                  unique = true)
       })
@Where(clause = "deleted_at IS NULL")
public class WageCalculation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    /**
     * 员工姓名 (冗余存储便于查询).
     */
    @Column(name = "employee_name", length = 100)
    private String employeeName;

    /**
     * 计算周期月份 (该月 1 号), 例: 2026-05-01 = 2026 年 5 月.
     */
    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    /**
     * 计算 mode.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private WageMode mode;

    /**
     * 按时部分金额 (HOURLY 基础工时 × 时薪). MIXED 时仅基础工时部分.
     */
    @Column(name = "hourly_amount", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal hourlyAmount = BigDecimal.ZERO;

    /**
     * 加班金额 (overtimeHours × hourlyRate × overtimeMultiplier).
     */
    @Column(name = "overtime_amount", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal overtimeAmount = BigDecimal.ZERO;

    /**
     * 计件部分金额 (piece count × PieceRateRule 阶梯).
     */
    @Column(name = "piece_rate_amount", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal pieceRateAmount = BigDecimal.ZERO;

    /**
     * 总工时 (小时).
     */
    @Column(name = "total_hours", precision = 8, scale = 2)
    @Builder.Default
    private BigDecimal totalHours = BigDecimal.ZERO;

    /**
     * 加班工时 (小时).
     */
    @Column(name = "overtime_hours", precision = 8, scale = 2)
    @Builder.Default
    private BigDecimal overtimeHours = BigDecimal.ZERO;

    /**
     * 计件总数.
     */
    @Column(name = "total_piece_count")
    @Builder.Default
    private Integer totalPieceCount = 0;

    /**
     * 应用的 HourlyRateRule ID (audit linkno).
     */
    @Column(name = "hourly_rule_id")
    private Long hourlyRuleId;

    /**
     * 应用的 PieceRateRule ID (audit linkno).
     */
    @Column(name = "piece_rule_id")
    private Long pieceRuleId;

    /**
     * 备注 / 异常说明.
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ============ derived getter ============

    /**
     * 计算总金额. 按 mode 分支:
     * <ul>
     *   <li>PIECE_RATE: pieceRateAmount</li>
     *   <li>HOURLY: hourlyAmount + overtimeAmount</li>
     *   <li>MIXED: hourlyAmount + overtimeAmount + pieceRateAmount</li>
     * </ul>
     *
     * <p>@Transient — 不持久化, 每次 getter 调用动态算 (保 source 唯一).
     */
    @Transient
    public BigDecimal getTotalAmount() {
        if (mode == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal h = hourlyAmount != null ? hourlyAmount : BigDecimal.ZERO;
        BigDecimal ot = overtimeAmount != null ? overtimeAmount : BigDecimal.ZERO;
        BigDecimal p = pieceRateAmount != null ? pieceRateAmount : BigDecimal.ZERO;
        return switch (mode) {
            case PIECE_RATE -> p;
            case HOURLY -> h.add(ot);
            case MIXED -> h.add(ot).add(p);
        };
    }
}
