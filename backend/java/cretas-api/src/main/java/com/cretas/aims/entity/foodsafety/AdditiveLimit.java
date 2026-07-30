package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.List;

/**
 * GB 2760-2014 食品添加剂限量库 — Sprint 8 P3 Phase A.
 *
 * <p>GB 2760-2014《食品安全国家标准 食品添加剂使用标准》强制规定每个食品类目允许使用
 * 的添加剂 + 最大使用量. 食品企业必须严格遵守, 超限即违法.
 *
 * <p>V1 seed (Flyway V20260820_04): 卤味 / 熟肉制品 (08.02 类目) 常用 30 种添加剂
 * (亚硝酸钠 / 山梨酸钾 / 苯甲酸 / 卡拉胶 / 谷氨酸钠 / 等).
 *
 * <p>跟 Skill `food-additive-compliance` 关联: `additive_compliance_check` Tool
 * 查 batch ingredient → 对每 ingredient 查询 (additive_code, food_category) →
 * 跟 max_limit 比较 → 超限即列警告 + 营养标签草稿.
 *
 * <p>系统级 entity, 无 factory_id (国标对所有工厂统一). 数据通过 Flyway seed 维护,
 * 不允许 factory 自定义.
 *
 * <p>软删除: 国标变更时旧 entry 标 deleted_at 而非物理删除, 保留历史合规追溯能力.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "additive_limits",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_additive_code_category",
                        columnNames = {"additive_code", "food_category"})
        },
        indexes = {
                @Index(name = "idx_additive_limits_lookup",
                        columnList = "additive_code,food_category,active")
        })
@Where(clause = "deleted_at IS NULL")
public class AdditiveLimit extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 添加剂中文名 (e.g. "亚硝酸钠"). */
    @Column(name = "additive_name", nullable = false, length = 100)
    private String additiveName;

    /** 添加剂 INS 代码 (e.g. "INS 250"). */
    @Column(name = "additive_code", nullable = false, length = 50)
    private String additiveCode;

    /** 食品类目 (e.g. "08.02 熟肉制品", 对应 GB 2760 类目编号). */
    @Column(name = "food_category", nullable = false, length = 100)
    private String foodCategory;

    /** 最大使用量 (0 表示按需适量使用, 不限量). */
    @Column(name = "max_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxLimit;

    /** 单位 (e.g. "mg/kg", "g/kg"). */
    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    /** 法规引用 (e.g. "GB 2760-2014 表 A.1"). */
    @Column(name = "regulation_ref", nullable = false, length = 100)
    private String regulationRef;

    /** 是否启用 (国标废止时设 false). */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * 添加剂俗名 / 化学名 / 英文名 — JSONB list&lt;String&gt;.
     *
     * <p>Sprint 9 P2.F V2 新增. 支持 Tool fuzzy 匹配 (用户用俗名输入时自动找到 INS code).
     * <p>e.g. {@code ["亚硝酸钠", "硝酸钠", "Sodium Nitrite"]}.
     * <p>历史 V1 entries 默认 NULL, 通过 V20260821_39 回填常用 entries.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "aliases", columnDefinition = "jsonb")
    private List<String> aliases;
}
