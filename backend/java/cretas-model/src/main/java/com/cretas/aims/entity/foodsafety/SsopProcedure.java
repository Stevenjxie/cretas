package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

/**
 * SSOP 清洁标准操作模板 (Sanitation Standard Operating Procedures) — Sprint 9 P2.E.
 *
 * <p>法定: GB 14881-2013 食品生产通用卫生规范 — 食品生产企业应建立清洁消毒制度,
 * 包括标准程序 + 频率 + 化学品 + 检验员. 第三方 audit (HACCP / FSSC22000 / ISO22000)
 * 必查项.
 *
 * <p>vs HJ 0 食品垂直清洁规范, Cretas 食品垂直 V2 差异化护城河 — 模板 +
 * 每日 task scheduler + production gate 阻产 + 月度审计报告.
 *
 * <p>target (清洁对象):
 * <ul>
 *   <li>{@code EQUIPMENT} — 生产设备 (生产线/灌装机/切割台)</li>
 *   <li>{@code TOOLS} — 工器具 (刀具/容器/搅拌器)</li>
 *   <li>{@code ENVIRONMENT} — 环境 (车间/地面/天花板/墙面)</li>
 *   <li>{@code EMPLOYEE} — 员工卫生 (手部/工服/鞋套)</li>
 *   <li>{@code PEST_CONTROL} — 防虫害 (粘鼠板/灭蝇灯/化学防治)</li>
 * </ul>
 *
 * <p>frequency (执行频率):
 * <ul>
 *   <li>{@code DAILY} — 每日 (scheduler 6am 自动创 record)</li>
 *   <li>{@code SHIFT} — 每班次</li>
 *   <li>{@code WEEKLY} — 每周</li>
 *   <li>{@code MONTHLY} — 每月</li>
 * </ul>
 *
 * <p>{@code inspectorRole} 检验员角色 (e.g. quality_mgr / workshop_supervisor) —
 * 用于 record 完成后 sign-off 时校验签字人权限.
 *
 * <p>{@code procedureCode} factory 内唯一 (unique constraint), e.g. "SSOP-DAILY-LINE-A".
 *
 * <p>软删除 via BaseEntity @Where. 已废弃的模板应 active=false (而非删).
 *
 * @since 2026-05-21
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "ssop_procedures",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ssop_procedures_factory_code",
                        columnNames = {"factory_id", "procedure_code"})
        },
        indexes = {
                @Index(name = "idx_ssop_procedures_factory_active",
                        columnList = "factory_id,active"),
                @Index(name = "idx_ssop_procedures_target_frequency",
                        columnList = "factory_id,target,frequency,active")
        })
@Where(clause = "deleted_at IS NULL")
public class SsopProcedure extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 工厂 ID. */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 程序编码 (factory 内唯一). e.g. "SSOP-DAILY-LINE-A". */
    @Column(name = "procedure_code", nullable = false, length = 100)
    private String procedureCode;

    /** 程序名称. e.g. "生产线 A 每日开工前消毒". */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** 清洁对象: EQUIPMENT / TOOLS / ENVIRONMENT / EMPLOYEE / PEST_CONTROL. */
    @Column(name = "target", nullable = false, length = 30)
    private String target;

    /** 执行频率: DAILY / SHIFT / WEEKLY / MONTHLY. */
    @Column(name = "frequency", nullable = false, length = 20)
    private String frequency;

    /** 标准操作步骤 (markdown). */
    @Column(name = "steps", columnDefinition = "TEXT")
    private String steps;

    /** 化学品 / 消毒剂使用规格 (e.g. "200ppm 氯消毒液浸泡 5 分钟"). */
    @Column(name = "chemicals_used", columnDefinition = "TEXT")
    private String chemicalsUsed;

    /** 检验员角色 (e.g. quality_mgr). 执行完成 sign-off 需该角色身份. */
    @Column(name = "inspector_role", nullable = false, length = 50)
    private String inspectorRole;

    /** 是否启用. false = 废弃 (但保留, 用于历史 records 追溯). */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}
