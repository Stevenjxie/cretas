package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.service.bom.BomRecipeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * M-BOM-1 BOM 成本重算 (Track D1).
 *
 * 适用场景: "算一下红烧肉 BOM 当前成本" / "如果牛肉涨价 10%, 配方成本多少?"
 *
 * 本 Tool 仅基于当前 unit_price 重算; 价格情景模拟留 Day 5+ Future.
 */
@Slf4j
@Component
public class BomRecipeCostCalculateTool extends AbstractBusinessTool {

    @Autowired
    private BomRecipeService recipeService;

    @Override
    public String getToolName() {
        return "bom_recipe_cost_calculate";
    }

    @Override
    public String getDescription() {
        return "重算 BOM 配方的总成本 (material/labor/overhead/total). "
             + "适用场景: 看 BOM 实际成本 / 利润分析 / 价格调整后重算.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> recipeId = new HashMap<>();
        recipeId.put("type", "string");
        recipeId.put("description", "BOM 配方 ID");

        Map<String, Object> properties = new HashMap<>();
        properties.put("recipeId", recipeId);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("recipeId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("recipeId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String recipeId = getString(params, "recipeId");
        BomRecipe recipe = recipeService.calculateCost(factoryId, recipeId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recipeCode", recipe.getRecipeCode());
        summary.put("productName", recipe.getProductName());
        summary.put("totalMaterialCost", recipe.getTotalMaterialCost());
        summary.put("totalLaborCost", recipe.getTotalLaborCost());
        summary.put("totalOverheadCost", recipe.getTotalOverheadCost());
        summary.put("totalCost", recipe.getTotalCost());
        summary.put("standardSalePrice", recipe.getStandardSalePrice());

        log.info("BOM 成本重算: factoryId={}, recipe={}, totalCost={}",
                factoryId, recipe.getRecipeCode(), recipe.getTotalCost());
        return buildSimpleResult("成本计算完成", summary);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
