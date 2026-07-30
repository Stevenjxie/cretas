package com.cretas.aims.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * 原料包装层级配置.
 *
 * 1 个原料 1 条记录 (UNIQUE on materialTypeId).
 * 一级单位必填 (= raw_material_types.unit), 二/三级可选, 提供换算系数:
 * - level1_per_level2: 例 10 (10 kg / 箱)
 * - level2_per_level3: 例 12 (12 箱 / 柜)
 *
 * 校验交给 DB CHECK 约束 (V20260506_02): 数量和单位必须同时存在或同时为空,
 * 三级必须有二级才能存在.
 *
 * @since 2026-05-06
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "material_packaging_hierarchy",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"material_type_id"})
       },
       indexes = {
           @Index(name = "idx_mph_factory", columnList = "factory_id"),
           @Index(name = "idx_mph_material_type", columnList = "material_type_id")
       })
@Where(clause = "deleted_at IS NULL")
public class MaterialPackagingHierarchy extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "material_type_id", nullable = false, length = 36)
    private String materialTypeId;

    /** 一级单位 (基础, 与 raw_material_types.unit 一致). */
    @Column(name = "level1_unit", nullable = false, length = 20)
    private String level1Unit;

    /** 一级 / 二级换算: 例 10 表示 10 个一级单位 = 1 个二级单位 (10 kg / 箱). */
    @Column(name = "level1_per_level2", precision = 15, scale = 4)
    private BigDecimal level1PerLevel2;

    @Column(name = "level2_unit", length = 20)
    private String level2Unit;

    /** 二级 / 三级换算: 例 12 表示 12 个二级单位 = 1 个三级单位 (12 箱 / 柜). */
    @Column(name = "level2_per_level3", precision = 15, scale = 4)
    private BigDecimal level2PerLevel3;

    @Column(name = "level3_unit", length = 20)
    private String level3Unit;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;
}
