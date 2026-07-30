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
 * GB 28050-2011 食品营养素库 — Sprint 9 P2.B (2026-05-21).
 *
 * <p>法定: GB 28050-2011《食品安全国家标准 预包装食品营养标签通则》强制规定预包装食品
 * 必须标注 "1+4" 强制营养素 (能量 + 蛋白质 + 脂肪 + 碳水化合物 + 钠), 推荐增加饱和脂肪 /
 * 反式脂肪 / 糖 / 膳食纤维.
 *
 * <p>本表是营养素 reference 数据库 — 按每 100g 原料含量记录, 计算 nutrition_labels
 * 时按 BOM ratio 推算成品营养值.
 *
 * <p>数据来源: 中国食物成分表 第 6 版 (中国疾病预防控制中心营养与健康所).
 *
 * <p>V1 seed (Flyway V20260821_32): 卤味 / 熟肉 / 调味料 30+ 常用原料 (per F006 业务).
 *
 * <p>系统级 entity, 无 factory_id (国标 + 食物成分表对所有工厂统一). 数据通过 Flyway seed
 * 维护, 不允许 factory 自定义.
 *
 * <p>软删除: 食物成分表更新时旧 entry 标 deleted_at 而非物理删除, 保留历史营养标签追溯能力.
 *
 * <p>关联: NutritionLabel.calculate() 通过 ingredientCode (或 name fallback) 查本表
 * 拼装 per-serving 营养值.
 *
 * @author Cretas Team / Sprint 9 P2.B
 * @since 2026-05-21
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "ingredient_nutrition_facts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ingredient_nutrition_code",
                        columnNames = {"ingredient_code"})
        },
        indexes = {
                @Index(name = "idx_ingredient_nutrition_name",
                        columnList = "ingredient_name,active"),
                @Index(name = "idx_ingredient_nutrition_code_active",
                        columnList = "ingredient_code,active")
        })
@Where(clause = "deleted_at IS NULL")
public class IngredientNutritionFact extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 原料中文名 (e.g. "猪蹄" / "老抽 (酱油)"). */
    @Column(name = "ingredient_name", nullable = false, length = 200)
    private String ingredientName;

    /** 中国食物成分表 ID (e.g. "08-1-001"). uniqueConstraint. */
    @Column(name = "ingredient_code", nullable = false, length = 50)
    private String ingredientCode;

    /** 每 100g 能量 (kJ) — GB 28050 强制. */
    @Column(name = "per_100g_energy", nullable = false, precision = 8, scale = 2)
    private BigDecimal per100gEnergy;

    /** 每 100g 蛋白质 (g) — GB 28050 强制. */
    @Column(name = "per_100g_protein", nullable = false, precision = 8, scale = 2)
    private BigDecimal per100gProtein;

    /** 每 100g 脂肪 (g) — GB 28050 强制. */
    @Column(name = "per_100g_fat", nullable = false, precision = 8, scale = 2)
    private BigDecimal per100gFat;

    /** 每 100g 碳水化合物 (g) — GB 28050 强制. */
    @Column(name = "per_100g_carbohydrate", nullable = false, precision = 8, scale = 2)
    private BigDecimal per100gCarbohydrate;

    /** 每 100g 钠 (mg) — GB 28050 强制. */
    @Column(name = "per_100g_sodium", nullable = false, precision = 8, scale = 2)
    private BigDecimal per100gSodium;

    /** 每 100g 饱和脂肪 (g) — GB 28050 推荐 (营养声称需要). */
    @Column(name = "per_100g_saturated_fat", precision = 8, scale = 2)
    private BigDecimal per100gSaturatedFat;

    /** 每 100g 反式脂肪 (g) — GB 28050 推荐. */
    @Column(name = "per_100g_trans_fat", precision = 8, scale = 2)
    private BigDecimal per100gTransFat;

    /** 每 100g 糖 (g) — GB 28050 推荐. */
    @Column(name = "per_100g_sugar", precision = 8, scale = 2)
    private BigDecimal per100gSugar;

    /** 每 100g 膳食纤维 (g) — GB 28050 推荐. */
    @Column(name = "per_100g_dietary_fiber", precision = 8, scale = 2)
    private BigDecimal per100gDietaryFiber;

    /** 数据来源 (e.g. "中国食物成分表 第6版 / GB 28050-2011"). */
    @Column(name = "source_ref", columnDefinition = "TEXT")
    private String sourceRef;

    /** 是否启用 (食物成分表更新时旧值设 false). */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
