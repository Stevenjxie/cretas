package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.foodsafety.NutritionLabel;
import com.cretas.aims.repository.foodsafety.NutritionLabelRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 营养标签查询 Tool (READ) — Sprint 9 P2.B (2026-05-21).
 *
 * <p>按 productCode 或 productName 查已生成的营养标签 (DRAFT / PUBLISHED / REVOKED 全部).
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"查 XX 产品营养标签"</li>
 *   <li>"卤猪蹄的营养标签生成了吗"</li>
 *   <li>"列出所有营养标签"</li>
 * </ul>
 *
 * <p>Intent Code: {@code NUTRITION_LABEL_LOOKUP}
 */
@Slf4j
@Component
public class NutritionLabelLookupTool extends AbstractBusinessTool {

    @Autowired
    private NutritionLabelRepository nutritionLabelRepository;

    @Override
    public String getToolName() {
        return "nutrition_label_lookup";
    }

    @Override
    public String getDescription() {
        return "GB 28050 营养标签查询 — 按 productCode (SKU) 或 productName 查已生成标签. "
                + "LLM 触发: '查 XX 产品营养标签' / '卤猪蹄的营养标签生成了吗' / '列出所有营养标签'. "
                + "read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> productCode = new HashMap<>();
        productCode.put("type", "string");
        productCode.put("description", "产品编码 SKU (可选, 与 productName 二选一)");
        properties.put("productCode", productCode);

        Map<String, Object> productName = new HashMap<>();
        productName.put("type", "string");
        productName.put("description", "产品名称 (可选, 模糊查)");
        properties.put("productName", productName);

        schema.put("properties", properties);
        schema.put("required", new ArrayList<>()); // 至少一个 — doExecute 校验
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return new ArrayList<>();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) {
        String productCode = getString(params, "productCode");
        String productName = getString(params, "productName");

        log.info("nutrition_label_lookup — factory={} productCode={} productName={}",
                factoryId, productCode, productName);

        List<NutritionLabel> labels;
        if (productCode != null && !productCode.isBlank()) {
            labels = nutritionLabelRepository
                    .findByFactoryIdAndProductCodeOrderByCreatedAtDesc(factoryId, productCode);
        } else if (productName != null && !productName.isBlank()) {
            labels = nutritionLabelRepository
                    .findByFactoryIdAndProductNameContainingOrderByCreatedAtDesc(
                            factoryId, productName);
        } else {
            // 无 filter → 返 factory 全部 (limit 50 for safety)
            labels = nutritionLabelRepository.findAll().stream()
                    .filter(l -> factoryId.equals(l.getFactoryId()))
                    .limit(50)
                    .toList();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (NutritionLabel l : labels) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", l.getId());
            row.put("productCode", l.getProductCode());
            row.put("productName", l.getProductName());
            row.put("bomVersionId", l.getBomVersionId());
            row.put("servingSize", l.getServingSize());
            row.put("energyPerServing", l.getEnergyPerServing());
            row.put("proteinPerServing", l.getProteinPerServing());
            row.put("fatPerServing", l.getFatPerServing());
            row.put("carbohydratePerServing", l.getCarbohydratePerServing());
            row.put("sodiumPerServing", l.getSodiumPerServing());
            row.put("status", l.getStatus());
            row.put("generatedByUserId", l.getGeneratedByUserId());
            row.put("createdAt", l.getCreatedAt());
            rows.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalCount", rows.size());
        data.put("labels", rows);
        data.put("filterProductCode", productCode);
        data.put("filterProductName", productName);
        data.put("actionHint", productCode != null
                ? "/quality/nutrition-label?productCode=" + productCode
                : "/quality/nutrition-label");

        String message;
        if (rows.isEmpty()) {
            String filter = productCode != null ? productCode
                    : (productName != null ? productName : "(全部)");
            message = String.format(
                    "ℹ️ 未找到产品 '%s' 的营养标签. 可调用 nutrition_label_generate 生成", filter);
        } else {
            message = String.format("📋 查到 %d 个营养标签 (GB 28050-2011). 详见 labels 字段", rows.size());
        }
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
