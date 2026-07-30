package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 城市差异化 社保 / 公积金 配置覆盖 (#863 follow-up).
 *
 * <p>背景: #863 落地了 工厂级 {@link HrInsuranceConfig} (factory 单一费率 + 单一缴费基数上下限).
 * 但中国实际 社保缴费基数 / 部分费率 按 <b>城市</b> 差异巨大:
 * <ul>
 *   <li>上海 2025: 基数 7384 - 36549 (3 倍社平)</li>
 *   <li>北京 2025: 基数 5360 - 31884</li>
 *   <li>深圳 2025: 基数 2360 - 28860</li>
 * </ul>
 * 同一工厂的员工可能分布在多个城市, 每个城市需要分别配置.
 *
 * <p>本表 = 工厂默认配置 (HrInsuranceConfig) 的 <b>覆盖层</b>:
 * <ul>
 *   <li>{@code cityCode} — 城市代号 (e.g. "BEIJING", "SHANGHAI", "GUANGZHOU", "SHENZHEN")</li>
 *   <li>{@code baseSalaryLowerBound} — 该城市缴费基数下限 (¥元, 必填)</li>
 *   <li>{@code baseSalaryUpperBound} — 该城市缴费基数上限 (¥元, 必填)</li>
 *   <li>{@code overrideRates} — 可选: 部分费率覆盖 jsonb,
 *       e.g. {"employeePensionRate": 0.08, "employerProvidentFundRate": 0.12}.
 *       未列出的费率 fallback 到工厂 HrInsuranceConfig.</li>
 *   <li>{@code effectiveFrom} — 该 override 生效起始月 (含)</li>
 *   <li>{@code status} — ACTIVE / ARCHIVED (同 HrInsuranceConfig history 设计)</li>
 * </ul>
 *
 * <p>R4 防呆: (factory, cityCode, status=ACTIVE) 数据库 unique partial index 保证唯一.
 *
 * <p>不重复 HrInsuranceConfig 的费率字段 — 完整费率走"工厂默认 + 城市 overrideRates 合并"模式.
 * 这样: (1) 避免上游 #863 表结构 churn (2) 多数城市仅基数差异, jsonb 只填 1-2 个真正不同的费率.
 *
 * @author Cretas Team — #863 城市差异化社保 follow-up
 * @since 2026-05-18
 */
@Entity
@Table(name = "hr_city_insurance_overrides",
       indexes = {
           @Index(name = "idx_city_override_factory_status",
                  columnList = "factory_id,status,effective_from"),
           @Index(name = "idx_city_override_factory_city",
                  columnList = "factory_id,city_code,status")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HrCityInsuranceOverride extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 城市代号 (大写 ASCII, e.g. "BEIJING", "SHANGHAI"). */
    @Column(name = "city_code", nullable = false, length = 50)
    private String cityCode;

    /** 城市中文名 (e.g. "北京", "上海"). 给 UI 显示 + 提示消息用. */
    @Column(name = "city_name", length = 100)
    private String cityName;

    // ===== 缴费基数上下限 (城市强制必填) =====
    @Column(name = "base_salary_lower_bound", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseSalaryLowerBound;

    @Column(name = "base_salary_upper_bound", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseSalaryUpperBound;

    /**
     * 可选费率覆盖 jsonb. 仅放需要"覆盖工厂默认"的字段.
     * keys 必须命名为 HrInsuranceConfig 的 camelCase 字段名:
     *   employeePensionRate / employerPensionRate /
     *   employeeMedicalRate / employerMedicalRate /
     *   employeeUnemploymentRate / employerUnemploymentRate /
     *   employeeProvidentFundRate / employerProvidentFundRate
     * values 必须是 0 ~ 0.30 之间的 number (0-1 小数 form, 与 HrInsuranceConfig 一致).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "override_rates", columnDefinition = "jsonb")
    private Map<String, Object> overrideRates;

    /** 该 override 生效起始月 (含). 通常 YYYY-MM-01. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** ACTIVE = 当前生效 (DB unique partial index 保证 factory+city 唯一); ARCHIVED = 历史. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "created_by")
    private Long createdBy;
}
