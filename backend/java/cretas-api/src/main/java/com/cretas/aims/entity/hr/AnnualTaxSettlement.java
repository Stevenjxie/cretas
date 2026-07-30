package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.hr.enums.AnnualTaxSettlementStatus;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 年度汇算 — China 综合所得 annual tax settlement (#833 follow-up).
 *
 * <p>每个 user 每个 taxYear 一条记录 (R4 防呆 unique constraint).
 * 由 {@code AnnualTaxSettlementService.computeForUser} 聚合 SalaryItem (#833) +
 * AnnualBonusTax (#861) + SpecialDeduction (#844) 产生.
 *
 * <p>核心字段语义 (人民币元, scale=2):
 * <ul>
 *   <li>{@code totalSalary} — 年度综合所得 (sum of monthly baseSalary)</li>
 *   <li>{@code totalBonus} — 年度年终奖 (info-only, 不并入汇算)</li>
 *   <li>{@code totalSocialInsurance} — 年度个人社保 sum</li>
 *   <li>{@code totalProvidentFund} — 年度个人公积金 sum</li>
 *   <li>{@code totalSpecialDeductions} — 年度专项附加扣除 (12-month probe sum)</li>
 *   <li>{@code annualTaxableIncome} — 应纳税所得额 = totalSalary − 60000 − 社保 − 公积金 − 专项</li>
 *   <li>{@code annualTaxDue} — 年度应纳税额 = 应纳税所得额 × 适用税率 − 速算扣除数</li>
 *   <li>{@code monthlyPrepaidSum} — 月度个税已预缴合计 (sum of monthly personalTax)</li>
 *   <li>{@code refundOwed} — 汇算补退 = annualTaxDue − monthlyPrepaidSum
 *       (>0: 应补缴, &lt;0: 应退税, =0: 平账)</li>
 *   <li>{@code annualBonusTax} — 年终奖个税合计 (info-only, sum of #861 annualBonusTax)</li>
 * </ul>
 *
 * <p>状态流转: DRAFT → CONFIRMED → REPORTED.
 * REPORTED 表示已申报税局, 锁住不可改 (R5 防呆).
 *
 * @author Cretas Team — #833 H-WAGE 年度汇算 follow-up
 * @since 2026-05-18
 */
@Entity
@Table(name = "annual_tax_settlements",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_annual_tax_factory_user_year",
                             columnNames = {"factory_id", "user_id", "tax_year"})
       },
       indexes = {
           @Index(name = "idx_annual_tax_factory_year",
                  columnList = "factory_id,tax_year"),
           @Index(name = "idx_annual_tax_factory_status",
                  columnList = "factory_id,status,tax_year")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnualTaxSettlement extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 纳税年度 (e.g. 2025 → 汇算 2025-01 至 2025-12). */
    @Column(name = "tax_year", nullable = false)
    private Integer taxYear;

    @Column(name = "total_salary", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalSalary = BigDecimal.ZERO;

    @Column(name = "total_bonus", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalBonus = BigDecimal.ZERO;

    @Column(name = "total_social_insurance", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalSocialInsurance = BigDecimal.ZERO;

    @Column(name = "total_provident_fund", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalProvidentFund = BigDecimal.ZERO;

    @Column(name = "total_special_deductions", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalSpecialDeductions = BigDecimal.ZERO;

    @Column(name = "annual_taxable_income", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal annualTaxableIncome = BigDecimal.ZERO;

    @Column(name = "annual_tax_due", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal annualTaxDue = BigDecimal.ZERO;

    @Column(name = "monthly_prepaid_sum", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal monthlyPrepaidSum = BigDecimal.ZERO;

    /** 汇算补退 (>0: 应补缴, &lt;0: 应退税, =0: 平账). */
    @Column(name = "refund_owed", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal refundOwed = BigDecimal.ZERO;

    @Column(name = "annual_bonus_tax", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal annualBonusTax = BigDecimal.ZERO;

    /** 适用年度档位 label, e.g. "≤36000" / ">960000". */
    @Column(name = "bracket_label", length = 20)
    private String bracketLabel;

    /** 适用税率 percent label, e.g. "3%" / "45%". */
    @Column(name = "bracket_rate", length = 10)
    private String bracketRate;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AnnualTaxSettlementStatus status = AnnualTaxSettlementStatus.DRAFT;

    /** 实际参与汇算的月数 (≤12, 跨年入职/离职时不足 12). */
    @Column(name = "months_covered", nullable = false)
    @Builder.Default
    private Integer monthsCovered = 0;

    @Column(name = "computed_at")
    @Builder.Default
    private LocalDateTime computedAt = LocalDateTime.now();

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "reported_at")
    private LocalDateTime reportedAt;

    @Column(name = "reported_by")
    private Long reportedBy;

    @Column(name = "notes", length = 500)
    private String notes;
}
