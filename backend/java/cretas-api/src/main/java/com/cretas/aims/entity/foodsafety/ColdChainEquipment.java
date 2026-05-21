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
 * 冷链温控设备实体 (Cold Chain Equipment) — Sprint 9 P2.D.
 *
 * <p>食品行业 P0: 部分品类 (卤味 / 乳制品 / 冷冻熟食) 强制冷链监控. 法规依据:
 * GB 14881 / 食品安全法 — 冷藏 ≤ 8.0 ℃, 冷冻 ≤ -18.0 ℃.
 *
 * <p>核心字段:
 * <ul>
 *   <li>{@code equipmentCode} 设备编码 (factory 内唯一)</li>
 *   <li>{@code type} FREEZER / REFRIGERATOR / COLD_CHAIN_VEHICLE / COLD_DISPLAY / COLD_STORAGE</li>
 *   <li>{@code targetTempMin / Max} 目标温度区间 (℃)</li>
 *   <li>{@code toleranceMinutes} 偏离持续时长阈值, 超过即触发 ColdChainAlert</li>
 * </ul>
 *
 * <p>关联 {@link ColdChainTempReading} (高频时序读数) + {@link ColdChainAlert} (偏离事件).
 *
 * <p>软删除 via BaseEntity {@code @Where deleted_at IS NULL}.
 * 已退役设备应设 {@code active=false}, 不应物理删除 (保留历史 readings/alerts 引用).
 *
 * <p>HJ 0 冷链温控 → Cretas 食品垂直 V2 差异化护城河 +1.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "cold_chain_equipment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_cold_chain_equipment_factory_code",
                        columnNames = {"factory_id", "equipment_code"})
        },
        indexes = {
                @Index(name = "idx_cold_chain_equipment_factory",
                        columnList = "factory_id"),
                @Index(name = "idx_cold_chain_equipment_factory_active",
                        columnList = "factory_id,active")
        })
@Where(clause = "deleted_at IS NULL")
public class ColdChainEquipment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 工厂 ID. */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 设备编码 (factory 内唯一). e.g. "FREEZER-A01". */
    @Column(name = "equipment_code", nullable = false, length = 50)
    private String equipmentCode;

    /** 设备名称 (e.g. "1号冰柜"). */
    @Column(name = "equipment_name", nullable = false, length = 200)
    private String equipmentName;

    /**
     * 设备类型 enum.
     * FREEZER 冰柜 / REFRIGERATOR 冷藏柜 / COLD_CHAIN_VEHICLE 冷链车 /
     * COLD_DISPLAY 陈列冷柜 / COLD_STORAGE 冷库.
     */
    @Column(name = "type", nullable = false, length = 30)
    private String type;

    /** 目标温度下限 (℃). e.g. 冷冻 -18.0. */
    @Column(name = "target_temp_min", nullable = false, precision = 5, scale = 2)
    private BigDecimal targetTempMin;

    /** 目标温度上限 (℃). e.g. 冷藏 8.0. */
    @Column(name = "target_temp_max", nullable = false, precision = 5, scale = 2)
    private BigDecimal targetTempMax;

    /** 温度单位 (默认 "℃"). */
    @Column(name = "unit", nullable = false, length = 10)
    @Builder.Default
    private String unit = "℃";

    /**
     * 偏离持续时长阈值 (min). 默认 15 min — 短期电压波动 / 开关门不报警,
     * 但持续 > 15 min 偏离则触发 ColdChainAlert.
     */
    @Column(name = "tolerance_minutes", nullable = false)
    @Builder.Default
    private Integer toleranceMinutes = 15;

    /** 设备位置 (e.g. "1楼冷库A区"). */
    @Column(name = "location", length = 200)
    private String location;

    /** 是否启用 — false 时新 reading 不触发 alert, 但保留历史. */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
