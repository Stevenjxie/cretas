package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.service.bom.BomRecipeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * M-BOM-1 BOM 配方激活 (Track D1, WRITE w/ Preview).
 *
 * 状态机: DRAFT → ACTIVE. 同产品其他 is_current=TRUE 版本自动置为 FALSE.
 * 支持 Preview (TCC pattern): 返回 "currentVersion / newVersion / impactedVersions" 不实际改.
 */
@Slf4j
@Component
public class BomRecipeActivateTool extends AbstractBusinessTool {

    @Autowired
    private BomRecipeService recipeService;

    @Override
    public String getToolName() {
        return "bom_recipe_activate";
    }

    @Override
    public String getDescription() {
        return "激活 BOM 配方 (DRAFT → ACTIVE, 同产品其他版本自动失效). "
             + "支持预览: 用户确认后才真实生效. "
             + "适用场景: 配方调试完毕生效投产 / 切换新版本配方.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> recipeId = new HashMap<>();
        recipeId.put("type", "string");
        recipeId.put("description", "要激活的 BOM 配方 ID (必须 status=DRAFT)");

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
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        String recipeId = getString(params, "recipeId");
        BomRecipe target = recipeService.getRecipe(factoryId, recipeId);

        if (target.getStatus() != BomRecipe.Status.DRAFT) {
            throw new IllegalStateException(
                    "只有 DRAFT 状态可激活, 当前 status=" + target.getStatus());
        }

        // Find currently active version of same product to display "impact".
        Optional<BomRecipe> current = recipeService.getCurrentRecipe(factoryId, target.getProductTypeId());

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("status", "PREVIEW");
        preview.put("action", "激活 BOM 配方");
        preview.put("targetRecipe", Map.of(
                "recipeCode", target.getRecipeCode(),
                "version", target.getVersion(),
                "productName", target.getProductName()
        ));
        if (current.isPresent()) {
            BomRecipe c = current.get();
            preview.put("willDeactivate", Map.of(
                    "recipeCode", c.getRecipeCode(),
                    "version", c.getVersion()
            ));
            preview.put("warning", "同产品已有生效版本 v" + c.getVersion() + ", 激活后会自动失效");
        } else {
            preview.put("willDeactivate", null);
            preview.put("warning", "该产品首次激活 BOM");
        }
        return preview;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String recipeId = getString(params, "recipeId");
        Long operatorId = getLong(params, "operatorId");  // 来自 context, 可选

        BomRecipe activated = recipeService.activateRecipe(factoryId, recipeId, operatorId);
        log.info("BOM 激活: factoryId={}, recipeId={}, recipeCode={}",
                factoryId, recipeId, activated.getRecipeCode());
        return buildSimpleResult("BOM 已激活: " + activated.getRecipeCode(), activated);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
