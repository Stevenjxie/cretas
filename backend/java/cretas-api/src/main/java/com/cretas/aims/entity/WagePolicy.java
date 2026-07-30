package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.WageMode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

/**
 * Sprint 6 Track W4-B: 工资策略 (WagePolicy).
 *
 * <p><b>Context</b>: Sprint 5 PR #57 E ship Wage trigger (ProcessingService 钩 + PieceRateRule).
 * 只 ship 按件 (PIECE_RATE) mode. F006 卤制品客户场景: 部分员工按时 (HOURLY) /
 * 部分员工混合 (MIXED: 基础工时 + 计件提成).
 *
 * <p>本实体定义"员工"或"工厂默认"层级的 wage 计算 mode:
 * <ul>
 *   <li><b>employeeId = null</b>: factory-level default policy (factoryId 范围内, 未单独配置的员工兜底)</li>
 *   <li><b>employeeId != null</b>: 员工级别 override (优先于 factory default)</li>
 * </ul>
 *
 * <p>查询顺序: per-employee → factory default → 系统兜底 PIECE_RATE (向后兼容 PR #57 E).
 *
 * <p>{@link #mixedFormulaHint} 仅 MIXED mode 使用 (例: "基础工时 + 80% 计件提成"), 当前
 * 实现 hardcode HOURLY + PIECE_RATE 全额相加 (per spec §W4-B), 未来可扩展 SpEL.
 *
 * @since 2026-05-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "wage_policy",
       indexes = {
           @Index(name = "idx_wage_policy_factory", columnList = "factory_id"),
           @Index(name = "idx_wage_policy_employee", columnList = "factory_id,employee_id"),
           @Index(name = "idx_wage_policy_active", columnList = "factory_id,is_active")
       })
@Where(clause = "deleted_at IS NULL")
public class WagePolicy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 员工 ID; null = factory-level default policy (兜底).
     */
    @Column(name = "employee_id")
    private Long employeeId;

    /**
     * 工资计算 mode. PIECE_RATE (默认, 向后兼容 PR #57 E) / HOURLY / MIXED.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    @Builder.Default
    private WageMode mode = WageMode.PIECE_RATE;

    /**
     * MIXED mode hint (例: "基础工时 + 80% 计件"). 仅 UI 显, 不参与 calc.
     * 当前 calc 实现 (per spec §W4-B): MIXED = HOURLY 全额 + PIECE_RATE 全额.
     */
    @Column(name = "mixed_formula_hint", length = 200)
    private String mixedFormulaHint;

    /**
     * 是否启用.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 备注.
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * AUD-4 JPA @Version optimistic lock — Canvas Phase B Factory Config Hub (2026-05-22).
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;
}
