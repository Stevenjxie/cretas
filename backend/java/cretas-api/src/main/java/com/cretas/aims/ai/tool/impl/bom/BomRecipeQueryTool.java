package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.service.bom.BomRecipeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * M-BOM-1 BOM 配方查询 (Track D1).
 *
 * 适用场景: "红烧肉的 BOM 是什么?" / "猪蹄 SKU-201 当前配方"
 */
@Slf4j
@Component
public class BomRecipeQueryTool extends AbstractBusinessTool {

    @Autowired
    private BomRecipeService recipeService;

    @Override
    public String getToolName() {
        return "bom_recipe_query";
    }

    @Override
    public String getDescription() {
        return "查询 BOM 配方. 支持按产品 productTypeId 取当前生效版本, 或按 recipeId 取详情. "
             + "适用场景: 查看某产品当前 BOM / 查询配方明细 / 看出成率 / 看物料构成.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description", "产品类型 ID — 取当前生效 BOM. 与 recipeId 二选一.");

        Map<String, Object> recipeId = new HashMap<>();
        recipeId.put("type", "string");
        recipeId.put("description", "BOM 配方 ID (UUID) — 取详情. 与 productTypeId 二选一.");

        Map<String, Object> properties = new HashMap<>();
        properties.put("productTypeId", productTypeId);
        properties.put("recipeId", recipeId);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());  // 二选一, 走自定义校验
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String productTypeId = getString(params, "productTypeId");
        String recipeId = getString(params, "recipeId");

        if ((productTypeId == null || productTypeId.isBlank())
                && (recipeId == null || recipeId.isBlank())) {
            return buildSimpleResult("请提供 productTypeId 或 recipeId 至少一个",
                    Map.of("status", "NEED_MORE_INFO"));
        }

        BomRecipe recipe;
        if (recipeId != null && !recipeId.isBlank()) {
            recipe = recipeService.getRecipe(factoryId, recipeId);
        } else {
            Optional<BomRecipe> opt = recipeService.getCurrentRecipe(factoryId, productTypeId);
            if (opt.isEmpty()) {
                return buildSimpleResult("产品无当前生效 BOM: " + productTypeId,
                        Map.of("found", false, "productTypeId", productTypeId));
            }
            recipe = opt.get();
        }

        log.info("BOM 查询: factoryId={}, recipeId={}, productTypeId={}",
                factoryId, recipe.getId(), recipe.getProductTypeId());
        return buildSimpleResult("查询成功", recipe);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
