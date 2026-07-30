package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.hr.enums.DeductionStatus;
import com.cretas.aims.entity.hr.enums.DeductionType;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 个税专项附加扣除 — 每个员工每项扣除一条记录, 跨月生效直到 validTo.
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code deductionType} — 6 类 (CHILD_EDUCATION / CONTINUING_EDUCATION /
 *       SERIOUS_ILLNESS / HOUSING_LOAN / HOUSING_RENT / ELDER_SUPPORT)</li>
 *   <li>{@code monthlyAmount} — 月度扣除金额 (¥元), MVP 由 user 输入实际金额, 不强制上限</li>
 *   <li>{@code validFrom} — 生效起始日 (含当日)</li>
 *   <li>{@code validTo} — 失效日 (含当日); null 表示长期有效</li>
 *   <li>{@code status} — ACTIVE / EXPIRED / CANCELLED (仅 ACTIVE 参与月度计税)</li>
 * </ul>
 *
 * <p>R4 防呆: {@code uq_special_deduction_factory_user_type_from} unique
 * (factory_id, user_id, deduction_type, valid_from) WHERE status='ACTIVE'
 * 防止同 user 同 type 同生效日重复申报.
 *
 * <p>月度计税逻辑 (见 SalaryItemServiceImpl.previewComputeFromBase):
 * <pre>
 *   taxable_income = base - 个人社保 - 个人公积金 - 5000起征点 - 专项附加扣除总额
 *   其中专项附加扣除总额 = sum(monthlyAmount) FOR status=ACTIVE AND yearMonth ∈ [validFrom, validTo]
 * </pre>
 *
 * <p>Deferred (next batch):
 * <ul>
 *   <li>城市档位的住房租金自动按城市计算</li>
 *   <li>auto-expiry 定时任务 (现在需 HR 手动改 status)</li>
 *   <li>从外部 HR 系统 bulk import</li>
 * </ul>
 *
 * @author Cretas Team — P1-40 H-WAGE 专项扣除 follow-up
 * @since 2026-05-17
 */
@Entity
@Table(name = "salary_special_deductions",
       indexes = {
           @Index(name = "idx_special_deduction_factory_user",
                  columnList = "factory_id,user_id,status"),
           @Index(name = "idx_special_deduction_factory_status",
                  columnList = "factory_id,status,valid_from,valid_to")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalarySpecialDeduction extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "deduction_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private DeductionType deductionType;

    @Column(name = "monthly_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyAmount;

    /** 生效起始日 (含当日). */
    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** 失效日 (含当日); null 表示长期有效. */
    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DeductionStatus status = DeductionStatus.ACTIVE;

    @Column(name = "notes", length = 500)
    private String notes;
}
