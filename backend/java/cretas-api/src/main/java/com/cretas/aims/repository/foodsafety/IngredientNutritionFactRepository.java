package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.IngredientNutritionFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository — Sprint 9 P2.B {@link IngredientNutritionFact}.
 *
 * <p>系统级 entity (无 factory_id). nutrition-label-workflow Skill 主要查询点.
 * BaseEntity 软删除 via @Where 自动过滤 deleted_at IS NULL.
 */
@Repository
public interface IngredientNutritionFactRepository
        extends JpaRepository<IngredientNutritionFact, Long> {

    /** 按食物成分表 ID 精确查 (NutritionLabelGenerateTool 主路径). */
    Optional<IngredientNutritionFact> findByIngredientCodeAndActiveTrue(String ingredientCode);

    /** 按原料名 LIKE 查 (fallback path, 模糊匹配 BomRecipeItem.materialName). */
    List<IngredientNutritionFact> findByIngredientNameContainingAndActiveTrue(String ingredientName);

    /** 列全部启用条目 (营养素库 reference 查询用). */
    List<IngredientNutritionFact> findByActiveTrue();
}
