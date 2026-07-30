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

import java.math.BigDecimal;

/**
 * HACCP 关键控制点 (Critical Control Point) 配置 — Sprint 8 P3 Phase A.
 *
 * <p>HACCP (Hazard Analysis and Critical Control Point) 是食品安全的国际标准方法,
 * GB/T 27341 + 食品安全法 强制要求食品生产企业实施 HACCP 体系.
 *
 * <p>每个 CCP 定义一个关键控制点 (e.g. 卤煮中心温度, 冷却时间, pH 值), 配置:
 * - 危害类型 (BIOLOGICAL 生物性 / CHEMICAL 化学性 / PHYSICAL 物理性)
 * - 临界限值 (critical limit, 上下限)
 * - 监控程序 / 纠偏措施 / 验证程序 / 记录保存
 *
 * <p>跟 {@link HaccpMonitoringRecord} 关联: 每个 batch 在 CCP 处必须监控记录,
 * 超出限值即 deviation 触发纠偏措施.
 *
 * <p>factory_id scope: factory 内 unique(checkpoint_code). 每个 factory 独立配置.
 *
 * <p>软删除沿用 BaseEntity. 已有监控记录的 CCP 应禁用 (active=false), 不应物理删除
 * (避免历史记录悬空).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "haccp_checkpoints",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_haccp_checkpoint_factory_code",
                        columnNames = {"factory_id", "checkpoint_code"})
        },
        indexes = {
                @Index(name = "idx_haccp_checkpoints_factory", columnList = "factory_id"),
                @Index(name = "idx_haccp_checkpoints_active",
                        columnList = "factory_id,active")
        })
@Where(clause = "deleted_at IS NULL")
public class HaccpCheckpoint extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 工厂 ID. */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** CCP 编码 (e.g. "CCP-01"). factory 内唯一. */
    @Column(name = "checkpoint_code", nullable = false, length = 50)
    private String checkpointCode;

    /** CCP 名称 (e.g. "中心温度", "冷却时间"). */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 危害类型: BIOLOGICAL / CHEMICAL / PHYSICAL. */
    @Column(name = "hazard_type", nullable = false, length = 20)
    private String hazardType;

    /** 描述 / 详细说明. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 临界限值下限 (e.g. 中心温度 75 ℃). */
    @Column(name = "critical_limit_min", nullable = false, precision = 19, scale = 4)
    private BigDecimal criticalLimitMin;

    /** 临界限值上限 (e.g. 冷却时间 90 min). */
    @Column(name = "critical_limit_max", nullable = false, precision = 19, scale = 4)
    private BigDecimal criticalLimitMax;

    /** 单位 (e.g. "℃", "min", "mg/kg"). */
    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    /** 监控程序 (e.g. "每批次产品出锅前用红外测温计测量中心位置"). */
    @Column(name = "monitoring_procedure", columnDefinition = "TEXT")
    private String monitoringProcedure;

    /** 纠偏措施 (e.g. "中心温度不达标 → 继续加热 5 min 重测"). */
    @Column(name = "corrective_action", columnDefinition = "TEXT")
    private String correctiveAction;

    /** 验证程序 (e.g. "周度抽检温度计校准记录"). */
    @Column(name = "verification_procedure", columnDefinition = "TEXT")
    private String verificationProcedure;

    /** 记录保存要求 (e.g. "记录保存 2 年"). */
    @Column(name = "record_keeping", columnDefinition = "TEXT")
    private String recordKeeping;

    /** 是否启用 — false 时不可用于新监控记录, 但保留历史引用. */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * AUD-4 P1 Lost Update prevention (Canvas Phase A Food Safety Hub, 2026-05-21).
     *
     * <p>JPA @Version optimistic locking. Hibernate auto-increments on each save() and
     * throws {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
     * if the loaded version differs from the DB version at flush time. Parallel PUT
     * attempts trip the lock — loser sees 409 with refresh hint.
     *
     * <p>Column added via Flyway {@code V20260823_02} with {@code DEFAULT 0 NOT NULL}.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
