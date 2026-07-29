package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomVersion;
import com.cretas.aims.entity.foodsafety.IngredientNutritionFact;
import com.cretas.aims.entity.foodsafety.NutritionLabel;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.foodsafety.IngredientNutritionFactRepository;
import com.cretas.aims.repository.foodsafety.NutritionLabelRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GB 28050-2011 营养标签生成 Tool (WRITE + Preview) — Sprint 9 P2.B (2026-05-21).
 *
 * <p>法定: GB 28050-2011《食品安全国家标准 预包装食品营养标签通则》强制要求预包装食品
 * 标注 "1+4" 强制营养素 (能量 + 蛋白质 + 脂肪 + 碳水化合物 + 钠) per serving + 推荐
 * 标注饱和脂肪 / 反式脂肪 / 糖 / 膳食纤维.
 *
 * <p>生成算法:
 * <ol>
 *   <li>按 bomVersionId 加载 BomVersion → snapshot_json (items[])
 *       OR 直接通过 BomRecipe.id 加载 BomRecipe + items.</li>
 *   <li>按 servingSize / outputQuantityPerUnit 算缩放系数 scaling.</li>
 *   <li>对每 BomRecipeItem 按 materialName 模糊查 IngredientNutritionFact.</li>
 *   <li>累加: each item.actualQuantity / 100 * per100gNutrient * scaling.</li>
 *   <li>HALF_UP scale 2 quantize.</li>
 * </ol>
 *
 * <p>防呆 4 位一体:
 * <ul>
 *   <li>R1: preview 显计算预览 (能量/蛋白质/...), 用户确认</li>
 *   <li>R2: message 含 productName + servingSize + BOM 版本号 (上下文)</li>
 *   <li>R3: BOM 缺失/原料未匹配返 missingIngredients list 引导补 BOM 或扩营养素库</li>
 *   <li>R4: 同 (factory_id, product_code, bom_version_id) DRAFT 存在 → 返 existingId, 不重复创建</li>
 *   <li>error sticky: BusinessException severity</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"生成 XX 产品营养标签"</li>
 *   <li>"卤猪蹄 200g 营养信息"</li>
 *   <li>"算下这产品 GB 28050 营养标签"</li>
 *   <li>"打印产品营养表"</li>
 * </ul>
 *
 * <p>Intent Code: {@code NUTRITION_LABEL_GENERATE}
 */
@Slf4j
@Component
public class NutritionLabelGenerateTool extends AbstractBusinessTool {

    @Autowired
    private BomRecipeRepository bomRecipeRepository;

    @Autowired
    private BomVersionRepository bomVersionRepository;

    @Autowired
    private IngredientNutritionFactRepository ingredientNutritionFactRepository;

    @Autowired
    private NutritionLabelRepository nutritionLabelRepository;

    @Override
    public String getToolName() {
        return "nutrition_label_generate";
    }

    @Override
    public String getDescription() {
        return "GB 28050-2011 预包装食品营养标签生成 — 按 BOM + servingSize 算 '1+4' 强制营养素 "
                + "(能量/蛋白质/脂肪/碳水化合物/钠) + 推荐 4 项 (饱和脂肪/反式脂肪/糖/膳食纤维). "
                + "WRITE + Preview. LLM 触发: '生成 XX 产品营养标签' / '卤猪蹄 200g 营养信息' / "
                + "'算下这产品 GB 28050 营养标签' / '打印产品营养表'. "
                + "需先有 BomRecipe (bomRecipeId 或 bomVersionId).";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> productCode = new HashMap<>();
        productCode.put("type", "string");
        productCode.put("description", "产品编码 SKU (必填), e.g. 'QHJ-LZT-200G'");
        properties.put("productCode", productCode);

        Map<String, Object> bomRecipeId = new HashMap<>();
        bomRecipeId.put("type", "string");
        bomRecipeId.put("description",
                "BomRecipe.id UUID — 当前 ACTIVE 版本 (二选一与 bomVersionId)");
        properties.put("bomRecipeId", bomRecipeId);

        Map<String, Object> bomVersionId = new HashMap<>();
        bomVersionId.put("type", "string");
        bomVersionId.put("description",
                "BomVersion.id UUID — 历史 APPROVED 版本 (二选一与 bomRecipeId)");
        properties.put("bomVersionId", bomVersionId);

        Map<String, Object> servingSize = new HashMap<>();
        servingSize.put("type", "number");
        servingSize.put("description",
                "每份 g (GB 28050 §5.1.2 营养声称基准). 缺省取 BomRecipe.outputQuantityPerUnit");
        properties.put("servingSize", servingSize);

        schema.put("properties", properties);
        schema.put("required", List.of("productCode"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("productCode");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.WRITE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.LOW;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String productCode = getString(params, "productCode");
        String bomRecipeId = getString(params, "bomRecipeId");
        String bomVersionId = getString(params, "bomVersionId");
        BigDecimal servingSizeOverride = getBigDecimal(params, "servingSize");

        BomRecipe recipe = resolveBomRecipe(factoryId, bomRecipeId, bomVersionId, productCode);
        if (recipe == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "PREVIEW");
            result.put("canDo", false);
            result.put("missingBom", true);
            result.put("productCode", productCode);
            result.put("message", String.format(
                    "⚠️ 未找到 productCode=%s 的 ACTIVE BomRecipe (或指定 bomRecipeId / bomVersionId). " +
                    "请先创建 BOM 配方后再生成营养标签",
                    productCode));
            result.put("actionHint", "/bom/recipe/new?productCode=" + productCode);
            return result;
        }

        BigDecimal servingSize = servingSizeOverride != null
                ? servingSizeOverride
                : recipe.getOutputQuantityPerUnit();
        if (servingSize == null || servingSize.compareTo(BigDecimal.ZERO) <= 0) {
            servingSize = new BigDecimal("100");
        }

        CalculationOutcome outcome = calculateNutrition(recipe, servingSize);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("canDo", true);
        result.put("productCode", productCode);
        result.put("productName", recipe.getProductName());
        result.put("bomRecipeId", recipe.getId());
        result.put("bomVersion", recipe.getVersion());
        result.put("servingSize", servingSize);
        result.put("servingUnit", recipe.getOutputUnit());
        result.put("matchedIngredientCount", outcome.matchedCount);
        result.put("missingIngredients", outcome.missingIngredients);
        result.put("energyPerServing", outcome.energy);
        result.put("proteinPerServing", outcome.protein);
        result.put("fatPerServing", outcome.fat);
        result.put("carbohydratePerServing", outcome.carbohydrate);
        result.put("sodiumPerServing", outcome.sodium);
        result.put("saturatedFatPerServing", outcome.saturatedFat);
        result.put("transFatPerServing", outcome.transFat);
        result.put("sugarPerServing", outcome.sugar);
        result.put("dietaryFiberPerServing", outcome.dietaryFiber);

        Optional<NutritionLabel> existing = findExistingDraft(factoryId, productCode, recipe.getId());
        existing.ifPresent(e -> {
            result.put("existingLabelId", e.getId());
            result.put("existingStatus", e.getStatus());
        });

        String message = String.format(
                "📋 [预览] %s (%s) per %s%s — 能量 %s kJ / 蛋白质 %s g / 脂肪 %s g / 碳水 %s g / 钠 %s mg" +
                "%s",
                recipe.getProductName(), productCode,
                servingSize, recipe.getOutputUnit(),
                outcome.energy, outcome.protein, outcome.fat, outcome.carbohydrate, outcome.sodium,
                outcome.missingIngredients.isEmpty()
                        ? ""
                        : String.format(" (⚠️ %d 项原料未匹配营养素库)", outcome.missingIngredients.size()));
        result.put("message", message);
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String productCode = getString(params, "productCode");
        String bomRecipeId = getString(params, "bomRecipeId");
        String bomVersionId = getString(params, "bomVersionId");
        BigDecimal servingSizeOverride = getBigDecimal(params, "servingSize");
        Long userId = getUserId(context);

        log.info("nutrition_label_generate — factory={} productCode={} userId={}",
                factoryId, productCode, userId);

        BomRecipe recipe = resolveBomRecipe(factoryId, bomRecipeId, bomVersionId, productCode);
        if (recipe == null) {
            throw new BusinessException(404, String.format(
                    "未找到 productCode=%s 的 ACTIVE BomRecipe. 请先创建 BOM 配方", productCode));
        }

        BigDecimal servingSize = servingSizeOverride != null
                ? servingSizeOverride
                : recipe.getOutputQuantityPerUnit();
        if (servingSize == null || servingSize.compareTo(BigDecimal.ZERO) <= 0) {
            servingSize = new BigDecimal("100");
        }

        // R4 幂等: 同 (factory, product_code, bom_recipe_id) DRAFT 存在 → 返 existingId
        Optional<NutritionLabel> existing = findExistingDraft(factoryId, productCode, recipe.getId());
        if (existing.isPresent()) {
            NutritionLabel e = existing.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "ALREADY_EXISTS");
            data.put("existingLabelId", e.getId());
            data.put("existingStatus", e.getStatus());
            data.put("productCode", productCode);
            data.put("productName", e.getProductName());
            data.put("actionHint", "/quality/nutrition-label?id=" + e.getId());
            String message = String.format(
                    "ℹ️ 产品 %s (%s) 已存在 %s 营养标签 #%d, 是否查看?",
                    e.getProductName(), productCode, e.getStatus(), e.getId());
            return buildSimpleResult(message, data);
        }

        CalculationOutcome outcome = calculateNutrition(recipe, servingSize);

        NutritionLabel label = NutritionLabel.builder()
                .factoryId(factoryId)
                .productCode(productCode)
                .productName(recipe.getProductName())
                .bomVersionId(recipe.getId())
                .servingSize(servingSize)
                .energyPerServing(outcome.energy)
                .proteinPerServing(outcome.protein)
                .fatPerServing(outcome.fat)
                .carbohydratePerServing(outcome.carbohydrate)
                .sodiumPerServing(outcome.sodium)
                .saturatedFatPerServing(outcome.saturatedFat)
                .transFatPerServing(outcome.transFat)
                .sugarPerServing(outcome.sugar)
                .dietaryFiberPerServing(outcome.dietaryFiber)
                .calculationDetails(serializeCalculationDetails(outcome))
                .generatedByUserId(userId != null ? userId : 0L)
                .status("DRAFT")
                .build();
        nutritionLabelRepository.save(label);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "CREATED");
        data.put("labelId", label.getId());
        data.put("productCode", productCode);
        data.put("productName", recipe.getProductName());
        data.put("bomRecipeId", recipe.getId());
        data.put("bomVersion", recipe.getVersion());
        data.put("servingSize", servingSize);
        data.put("servingUnit", recipe.getOutputUnit());
        data.put("matchedIngredientCount", outcome.matchedCount);
        data.put("missingIngredients", outcome.missingIngredients);
        data.put("energyPerServing", outcome.energy);
        data.put("proteinPerServing", outcome.protein);
        data.put("fatPerServing", outcome.fat);
        data.put("carbohydratePerServing", outcome.carbohydrate);
        data.put("sodiumPerServing", outcome.sodium);
        data.put("saturatedFatPerServing", outcome.saturatedFat);
        data.put("transFatPerServing", outcome.transFat);
        data.put("sugarPerServing", outcome.sugar);
        data.put("dietaryFiberPerServing", outcome.dietaryFiber);
        data.put("labelMarkdown", buildLabelMarkdown(recipe.getProductName(), servingSize,
                recipe.getOutputUnit(), outcome));
        data.put("actionHint", "/quality/nutrition-label?id=" + label.getId());

        String message = String.format(
                "✅ 已生成营养标签 #%d — %s (%s) per %s%s — GB 28050-2011" +
                "%s",
                label.getId(), recipe.getProductName(), productCode,
                servingSize, recipe.getOutputUnit(),
                outcome.missingIngredients.isEmpty()
                        ? ""
                        : String.format(" (⚠️ %d 项原料未匹配, 营养值仅估算)",
                                outcome.missingIngredients.size()));
        return buildSimpleResult(message, data);
    }

    // ── 内部算法 ──

    private BomRecipe resolveBomRecipe(String factoryId, String bomRecipeId,
            String bomVersionId, String productCode) {
        if (bomRecipeId != null && !bomRecipeId.isBlank()) {
            return bomRecipeRepository.findById(bomRecipeId).orElse(null);
        }
        if (bomVersionId != null && !bomVersionId.isBlank()) {
            Optional<BomVersion> v = bomVersionRepository.findById(bomVersionId);
            if (v.isPresent()) {
                return bomRecipeRepository.findById(v.get().getBomRecipeId()).orElse(null);
            }
        }
        // Fallback: by productCode → current ACTIVE
        return bomRecipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                        factoryId, productCode, BomRecipe.Status.ACTIVE)
                .orElse(null);
    }

    private Optional<NutritionLabel> findExistingDraft(String factoryId, String productCode,
            String bomRecipeId) {
        List<NutritionLabel> labels = nutritionLabelRepository
                .findByFactoryIdAndProductCodeOrderByCreatedAtDesc(factoryId, productCode);
        for (NutritionLabel l : labels) {
            if (bomRecipeId.equals(l.getBomVersionId())
                    && ("DRAFT".equals(l.getStatus()) || "PUBLISHED".equals(l.getStatus()))) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }

    /**
     * Calculate per-serving nutrition values.
     *
     * <p>Algorithm: for each BomRecipeItem (with actualQuantity in g):
     *   - 按 ingredient code (TODO Phase C) / materialName 查 IngredientNutritionFact
     *   - per_serving_X = sum_i(item.actualQuantity / 100 * fact.per100gX) * (servingSize / totalRecipeYield)
     *
     * <p>Simplification: assume BOM items are per-serving already (servingSize ≈ outputQuantityPerUnit).
     * If overridden servingSize ≠ outputQuantityPerUnit, apply scaling.
     */
    private CalculationOutcome calculateNutrition(BomRecipe recipe, BigDecimal servingSize) {
        CalculationOutcome outcome = new CalculationOutcome();
        outcome.energy = BigDecimal.ZERO;
        outcome.protein = BigDecimal.ZERO;
        outcome.fat = BigDecimal.ZERO;
        outcome.carbohydrate = BigDecimal.ZERO;
        outcome.sodium = BigDecimal.ZERO;
        outcome.saturatedFat = BigDecimal.ZERO;
        outcome.transFat = BigDecimal.ZERO;
        outcome.sugar = BigDecimal.ZERO;
        outcome.dietaryFiber = BigDecimal.ZERO;
        outcome.matchedCount = 0;
        outcome.missingIngredients = new ArrayList<>();
        outcome.contributionDetails = new ArrayList<>();

        BigDecimal recipeYield = recipe.getOutputQuantityPerUnit();
        if (recipeYield == null || recipeYield.compareTo(BigDecimal.ZERO) <= 0) {
            recipeYield = new BigDecimal("100");
        }
        // Scaling: servingSize / recipeYield. Both in same unit (typically g).
        BigDecimal scaling = servingSize.divide(recipeYield, 6, RoundingMode.HALF_UP);

        List<BomRecipeItem> items = recipe.getItems();
        if (items == null) items = new ArrayList<>();

        for (BomRecipeItem item : items) {
            if (item.getMaterialName() == null || item.getMaterialName().isBlank()) {
                outcome.missingIngredients.add(Map.of(
                        "materialName", "(空)",
                        "reason", "BOM item 无 materialName"));
                continue;
            }

            // 模糊查 (Phase C 优化: 加 ingredient_code 映射表)
            List<IngredientNutritionFact> matches = ingredientNutritionFactRepository
                    .findByIngredientNameContainingAndActiveTrue(item.getMaterialName());
            IngredientNutritionFact fact = pickBestMatch(matches, item.getMaterialName());

            if (fact == null) {
                Map<String, Object> miss = new LinkedHashMap<>();
                miss.put("materialName", item.getMaterialName());
                miss.put("materialTypeId", item.getMaterialTypeId());
                miss.put("reason", "营养素库未收录 (中国食物成分表)");
                outcome.missingIngredients.add(miss);
                continue;
            }

            outcome.matchedCount++;
            BigDecimal qty = item.getActualQuantity() != null
                    ? item.getActualQuantity()
                    : item.getStandardQuantity();
            if (qty == null) continue;

            // factor = qty(g) / 100 * scaling
            BigDecimal factor = qty.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)
                    .multiply(scaling);

            BigDecimal contribEnergy = fact.getPer100gEnergy().multiply(factor);
            BigDecimal contribProtein = fact.getPer100gProtein().multiply(factor);
            BigDecimal contribFat = fact.getPer100gFat().multiply(factor);
            BigDecimal contribCarbohydrate = fact.getPer100gCarbohydrate().multiply(factor);
            BigDecimal contribSodium = fact.getPer100gSodium().multiply(factor);

            outcome.energy = outcome.energy.add(contribEnergy);
            outcome.protein = outcome.protein.add(contribProtein);
            outcome.fat = outcome.fat.add(contribFat);
            outcome.carbohydrate = outcome.carbohydrate.add(contribCarbohydrate);
            outcome.sodium = outcome.sodium.add(contribSodium);

            if (fact.getPer100gSaturatedFat() != null) {
                outcome.saturatedFat = outcome.saturatedFat.add(
                        fact.getPer100gSaturatedFat().multiply(factor));
            }
            if (fact.getPer100gTransFat() != null) {
                outcome.transFat = outcome.transFat.add(
                        fact.getPer100gTransFat().multiply(factor));
            }
            if (fact.getPer100gSugar() != null) {
                outcome.sugar = outcome.sugar.add(fact.getPer100gSugar().multiply(factor));
            }
            if (fact.getPer100gDietaryFiber() != null) {
                outcome.dietaryFiber = outcome.dietaryFiber.add(
                        fact.getPer100gDietaryFiber().multiply(factor));
            }

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("materialName", item.getMaterialName());
            detail.put("ingredientCode", fact.getIngredientCode());
            detail.put("quantityG", qty);
            detail.put("contribEnergy", contribEnergy.setScale(2, RoundingMode.HALF_UP));
            detail.put("contribProtein", contribProtein.setScale(2, RoundingMode.HALF_UP));
            detail.put("contribSodium", contribSodium.setScale(2, RoundingMode.HALF_UP));
            outcome.contributionDetails.add(detail);
        }

        outcome.energy = outcome.energy.setScale(2, RoundingMode.HALF_UP);
        outcome.protein = outcome.protein.setScale(2, RoundingMode.HALF_UP);
        outcome.fat = outcome.fat.setScale(2, RoundingMode.HALF_UP);
        outcome.carbohydrate = outcome.carbohydrate.setScale(2, RoundingMode.HALF_UP);
        outcome.sodium = outcome.sodium.setScale(2, RoundingMode.HALF_UP);
        outcome.saturatedFat = outcome.saturatedFat.setScale(2, RoundingMode.HALF_UP);
        outcome.transFat = outcome.transFat.setScale(2, RoundingMode.HALF_UP);
        outcome.sugar = outcome.sugar.setScale(2, RoundingMode.HALF_UP);
        outcome.dietaryFiber = outcome.dietaryFiber.setScale(2, RoundingMode.HALF_UP);
        return outcome;
    }

    /** 模糊匹配 — 优先选 name length 最接近的 (避免 "盐" match "盐水"). */
    private IngredientNutritionFact pickBestMatch(List<IngredientNutritionFact> candidates,
            String materialName) {
        if (candidates == null || candidates.isEmpty()) return null;
        IngredientNutritionFact best = null;
        int bestDelta = Integer.MAX_VALUE;
        for (IngredientNutritionFact f : candidates) {
            int delta = Math.abs(f.getIngredientName().length() - materialName.length());
            if (delta < bestDelta) {
                bestDelta = delta;
                best = f;
            }
        }
        return best;
    }

    private String serializeCalculationDetails(CalculationOutcome outcome) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("matchedCount", outcome.matchedCount);
            details.put("missingIngredients", outcome.missingIngredients);
            details.put("contributions", outcome.contributionDetails);
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            log.warn("failed to serialize calculation details", ex);
            return "{}";
        }
    }

    private String buildLabelMarkdown(String productName, BigDecimal servingSize,
            String servingUnit, CalculationOutcome outcome) {
        return String.format(
                "## 营养成分表 (GB 28050-2011)%n%n" +
                "**产品**: %s  %n" +
                "**每份**: %s %s%n%n" +
                "| 项目 | 每份含量 |%n" +
                "|---|---|%n" +
                "| 能量 | %s kJ |%n" +
                "| 蛋白质 | %s g |%n" +
                "| 脂肪 | %s g |%n" +
                "| 碳水化合物 | %s g |%n" +
                "| 钠 | %s mg |%n" +
                "| 饱和脂肪 | %s g |%n" +
                "| 反式脂肪 | %s g |%n" +
                "| 糖 | %s g |%n" +
                "| 膳食纤维 | %s g |%n",
                productName, servingSize, servingUnit,
                outcome.energy, outcome.protein, outcome.fat,
                outcome.carbohydrate, outcome.sodium,
                outcome.saturatedFat, outcome.transFat, outcome.sugar, outcome.dietaryFiber);
    }

    /** 计算中间结果. */
    private static class CalculationOutcome {
        BigDecimal energy;
        BigDecimal protein;
        BigDecimal fat;
        BigDecimal carbohydrate;
        BigDecimal sodium;
        BigDecimal saturatedFat;
        BigDecimal transFat;
        BigDecimal sugar;
        BigDecimal dietaryFiber;
        int matchedCount;
        List<Map<String, Object>> missingIngredients;
        List<Map<String, Object>> contributionDetails;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
