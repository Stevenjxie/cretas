package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 工厂级 社保 / 公积金 费率配置 (#833 H-WAGE follow-up).
 *
 * <p>背景: #833 H-WAGE MVP 把社保 / 公积金费率硬编码 (个人 10.5% + 单位 26% + 公积金 8%/8%).
 * 客户需要按工厂配置 — 不同城市公积金范围 5-12%, 社保比例也按地市差异.
 *
 * <p>History 设计: 每次 save 都生成新一条 (status=ACTIVE) + 老 ACTIVE 改 status=ARCHIVED.
 * 当前生效 = 最新的 ACTIVE; effectiveFrom 之后产生的工资单按新费率计算.
 *
 * <p>字段语义 (rate 字段统一 0-1 小数, e.g. 0.08 表示 8%):
 * <ul>
 *   <li>{@code employeePensionRate} — 个人养老 (默认 0.08)</li>
 *   <li>{@code employerPensionRate} — 单位养老 (默认 0.16)</li>
 *   <li>{@code employeeMedicalRate} — 个人医疗 (默认 0.02)</li>
 *   <li>{@code employerMedicalRate} — 单位医疗 (默认 0.08)</li>
 *   <li>{@code employeeUnemploymentRate} — 个人失业 (默认 0.005)</li>
 *   <li>{@code employerUnemploymentRate} — 单位失业 (默认 0.005)</li>
 *   <li>{@code employeeProvidentFundRate} — 个人公积金 (默认 0.08, 城市范围 5%-12%)</li>
 *   <li>{@code employerProvidentFundRate} — 单位公积金 (默认 0.08, 城市范围 5%-12%)</li>
 *   <li>{@code baseSalaryLowerBound} — 缴费基数下限 (¥元, 通常城市最低工资 60%)</li>
 *   <li>{@code baseSalaryUpperBound} — 缴费基数上限 (¥元, 通常城市平均工资 3倍)</li>
 *   <li>{@code effectiveFrom} — 该费率生效起始月 (含)</li>
 *   <li>{@code status} — ACTIVE (当前生效) / ARCHIVED (历史版本)</li>
 * </ul>
 *
 * <p>R4 防呆: factory 最多 1 条 ACTIVE 配置 (通过 service 层 transaction 保证).
 *
 * @author Cretas Team — #833 公积金/社保 rate config follow-up
 * @since 2026-05-18
 */
@Entity
@Table(name = "hr_insurance_configs",
       indexes = {
           @Index(name = "idx_insurance_factory_status",
                  columnList = "factory_id,status,effective_from"),
           @Index(name = "idx_insurance_factory_eff",
                  columnList = "factory_id,effective_from")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HrInsuranceConfig extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    // ===== 个人 / 单位 养老 =====
    @Column(name = "employee_pension_rate", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal employeePensionRate = new BigDecimal("0.0800");

    @Column(name = "employer_pension_rate", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal employerPensionRate = new BigDecimal("0.1600");

    // ===== 个人 / 单位 医疗 =====
    @Column(name = "employee_medical_rate", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal employeeMedicalRate = new BigDecimal("0.0200");

    @Column(name = "employer_medical_rate", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal employerMedicalRate = new BigDecimal("0.0800");

    // ===== 个人 / 单位 失业 =====
    @Column(name = "employee_unemployment_rate", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal employeeUnemploymentRate = new BigDecimal("0.0050");

    @Column(name = "employer_unemployment_rate", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal employerUnemploymentRate = new BigDecimal("0.0050");

    // ===== 个人 / 单位 公积金 =====
    @Column(name = "employee_provident_fund_rate", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal employeeProvidentFundRate = new BigDecimal("0.0800");

    @Column(name = "employer_provident_fund_rate", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal employerProvidentFundRate = new BigDecimal("0.0800");

    // ===== 缴费基数上下限 =====
    @Column(name = "base_salary_lower_bound", precision = 12, scale = 2)
    private BigDecimal baseSalaryLowerBound;

    @Column(name = "base_salary_upper_bound", precision = 12, scale = 2)
    private BigDecimal baseSalaryUpperBound;

    /** 该费率生效起始月 (含). 通常 YYYY-MM-01. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** ACTIVE = 当前生效; ARCHIVED = 历史版本. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "created_by")
    private Long createdBy;

    /**
     * Canvas-P3 AUD-4 P1: JPA @Version 乐观锁 (per Flyway V20260824_06).
     * Phase B (PR #201) uses {@code getOptLockVersion()} as the canonical accessor.
     */
    @Version
    @Column(name = "opt_lock_version", nullable = false)
    @Builder.Default
    private Long optLockVersion = 0L;
}
