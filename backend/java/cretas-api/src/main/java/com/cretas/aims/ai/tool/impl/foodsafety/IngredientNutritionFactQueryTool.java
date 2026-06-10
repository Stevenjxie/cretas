package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.foodsafety.IngredientNutritionFact;
import com.cretas.aims.repository.foodsafety.IngredientNutritionFactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 营养素库查询 Tool (READ + FILTER) — Sprint 9 P2.B (2026-05-21).
 *
 * <p>查 IngredientNutritionFact (中国食物成分表 第 6 版).
 *
 * <p><b>factoryId 隔离豁免说明</b>: 本 Tool 查询的是中国食物成分表第 6 版营养数据库
 * ({@code ingredient_nutrition_facts} 表)。该表是全局参考数据，无 {@code factory_id} 列，
 * 天然不存在租户归属。所有工厂共享同一份营养参考数据，无需按工厂过滤。
 * {@code @FactoryIsolationExempt}
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"猪蹄的营养成分"</li>
 *   <li>"100g 老抽多少钠"</li>
 *   <li>"列出全部营养素库"</li>
 *   <li>"看下营养素表"</li>
 * </ul>
 *
 * <p>Intent Code: {@code INGREDIENT_NUTRITION_QUERY}
 */
@Slf4j
@Component
public class IngredientNutritionFactQueryTool extends AbstractBusinessTool {

    @Autowired
    private IngredientNutritionFactRepository ingredientNutritionFactRepository;

    @Override
    public String getToolName() {
        return "ingredient_nutrition_fact_query";
    }

    @Override
    public String getDescription() {
        return "营养素库查询 — 查中国食物成分表第 6 版 (per 100g 能量/蛋白质/脂肪/碳水/钠+推荐 4 项). "
                + "LLM 触发: '猪蹄的营养成分' / '100g 老抽多少钠' / '列出全部营养素库' / '看下营养素表'. "
                + "read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> ingredientName = new HashMap<>();
        ingredientName.put("type", "string");
        ingredientName.put("description", "原料中文名 (可选, 模糊查), e.g. '猪蹄' / '老抽'");
        properties.put("ingredientName", ingredientName);

        Map<String, Object> ingredientCode = new HashMap<>();
        ingredientCode.put("type", "string");
        ingredientCode.put("description", "中国食物成分表 ID (可选, 精确查), e.g. '08-1-001'");
        properties.put("ingredientCode", ingredientCode);

        schema.put("properties", properties);
        schema.put("required", new ArrayList<>());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return new ArrayList<>();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) {
        String ingredientName = getString(params, "ingredientName");
        String ingredientCode = getString(params, "ingredientCode");

        log.info("ingredient_nutrition_fact_query — name={} code={}", ingredientName, ingredientCode);

        List<IngredientNutritionFact> facts;
        if (ingredientCode != null && !ingredientCode.isBlank()) {
            Optional<IngredientNutritionFact> single = ingredientNutritionFactRepository
                    .findByIngredientCodeAndActiveTrue(ingredientCode);
            facts = single.map(List::of).orElse(List.of());
        } else if (ingredientName != null && !ingredientName.isBlank()) {
            facts = ingredientNutritionFactRepository
                    .findByIngredientNameContainingAndActiveTrue(ingredientName);
        } else {
            facts = ingredientNutritionFactRepository.findByActiveTrue();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (IngredientNutritionFact f : facts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.getId());
            row.put("ingredientName", f.getIngredientName());
            row.put("ingredientCode", f.getIngredientCode());
            row.put("per100gEnergy", f.getPer100gEnergy());
            row.put("per100gProtein", f.getPer100gProtein());
            row.put("per100gFat", f.getPer100gFat());
            row.put("per100gCarbohydrate", f.getPer100gCarbohydrate());
            row.put("per100gSodium", f.getPer100gSodium());
            row.put("per100gSaturatedFat", f.getPer100gSaturatedFat());
            row.put("per100gTransFat", f.getPer100gTransFat());
            row.put("per100gSugar", f.getPer100gSugar());
            row.put("per100gDietaryFiber", f.getPer100gDietaryFiber());
            row.put("sourceRef", f.getSourceRef());
            rows.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalCount", rows.size());
        data.put("ingredients", rows);
        data.put("filterName", ingredientName);
        data.put("filterCode", ingredientCode);
        data.put("actionHint", "/quality/nutrition-fact-library");

        String message;
        if (rows.isEmpty()) {
            String filter = ingredientCode != null ? ingredientCode
                    : (ingredientName != null ? ingredientName : "(全部)");
            message = String.format(
                    "ℹ️ 营养素库未收录 '%s' (中国食物成分表第 6 版). 如需要可申请扩库", filter);
        } else {
            message = String.format(
                    "📚 查到 %d 项营养素 (中国食物成分表第 6 版). 详见 ingredients 字段", rows.size());
        }
        return buildSimpleResult(message, data);
    }
}
