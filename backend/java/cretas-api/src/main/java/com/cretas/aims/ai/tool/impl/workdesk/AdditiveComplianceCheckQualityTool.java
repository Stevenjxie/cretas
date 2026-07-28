package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.foodsafety.AdditiveLimit;
import com.cretas.aims.repository.foodsafety.AdditiveLimitRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 质量主管 Workdesk 添加剂合规简洁判定 Tool — Sprint 8 P4c.
 *
 * <p>放行决策语境的 GB 2760 合规判定 — 输出: 整体 compliant + 关键超限项 + releaseHint.
 * 复用 P3 ship 的 {@link AdditiveLimitRepository}.
 *
 * <p>若客户端不传 ingredients (无 BOM 数据), 则只返回该 productCategory 的合规参考表 +
 * 不能给出明确放行建议 (R5 dead-end: 提示去 BOM 页面登记配料).
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"B-X 添加剂合规吗"</li>
 *   <li>"卤猪蹄添加剂超限了吗"</li>
 *   <li>"放行前添加剂检查"</li>
 *   <li>"GB 2760 合规"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code ADDITIVE_COMPLIANCE_QUALITY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4c)
 */
@Slf4j
@Component
public class AdditiveComplianceCheckQualityTool extends AbstractBusinessTool {

    /** 默认食品类目 — 卤味 / 熟肉制品 (GB 2760-2014 08.02). */
    private static final String DEFAULT_CATEGORY = "08.02 熟肉制品";

    @Autowired
    private AdditiveLimitRepository additiveLimitRepository;

    @Override
    public String getToolName() {
        return "additive_compliance_check_quality";
    }

    @Override
    public String getDescription() {
        return "质量主管放行前添加剂合规判定 (GB 2760-2014) — 给出整体 compliant + 超限项 + "
                + "放行建议. 客户端必须传 ingredients (从 BOM 取). "
                + "LLM 触发: 'B-X 添加剂合规吗' / '卤猪蹄添加剂超限了吗' / "
                + "'放行前添加剂检查' / 'GB 2760 合规'. read-only. "
                + "默认食品类目 '08.02 熟肉制品' (卤味).";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号 (必填), e.g. B-20260518-A03");
        properties.put("batchNumber", batchNumber);

        Map<String, Object> productCategory = new HashMap<>();
        productCategory.put("type", "string");
        productCategory.put("description",
                "食品类目 (GB 2760), 默认 '08.02 熟肉制品'");
        properties.put("productCategory", productCategory);

        Map<String, Object> ingredients = new HashMap<>();
        ingredients.put("type", "array");
        ingredients.put("description",
                "BOM ingredients 列表 (可选). 每项 {additiveCode, usageAmount, unit}. " +
                "缺省时返回 R5 dead-end actionHint 提示去 BOM 页面登记");
        properties.put("ingredients", ingredients);

        schema.put("properties", properties);
        schema.put("required", List.of("batchNumber"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        String productCategory = getString(params, "productCategory", DEFAULT_CATEGORY);
        Object ingredientsObj = params.get("ingredients");
        log.info("additive_compliance_check_quality — factory={} batch={} category={}",
                factoryId, batchNumber, productCategory);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNumber", batchNumber);
        data.put("productCategory", productCategory);

        // R5 dead-end: ingredients 缺 → 引导用户去配 BOM
        if (!(ingredientsObj instanceof List<?>) || ((List<?>) ingredientsObj).isEmpty()) {
            data.put("hasIngredients", false);
            data.put("compliant", null);
            data.put("releaseHint", "BLOCK_RELEASE_NO_DATA");
            data.put("actionHint",
                    "/production/bom?materialBatch=" + batchNumber + " — 去 BOM 页面登记配料后才能验证添加剂");
            return buildSimpleResult(
                    String.format("⚠️ 批次 %s 无 ingredients 数据 — 无法验证添加剂合规, " +
                            "请先在 BOM 页面登记配料", batchNumber),
                    data);
        }

        List<Map<String, Object>> ingredients = (List<Map<String, Object>>) ingredientsObj;
        List<Map<String, Object>> violations = new ArrayList<>();
        List<Map<String, Object>> compliantItems = new ArrayList<>();

        for (Map<String, Object> ing : ingredients) {
            String additiveCode = (String) ing.get("additiveCode");
            Object usageObj = ing.get("usageAmount");
            String unit = (String) ing.getOrDefault("unit", "mg/kg");
            if (additiveCode == null || additiveCode.isBlank()) continue;

            BigDecimal usage = toBigDecimal(usageObj);
            Optional<AdditiveLimit> limitOpt = additiveLimitRepository
                    .findByAdditiveCodeAndFoodCategoryAndActiveTrue(additiveCode, productCategory);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("additiveCode", additiveCode);
            row.put("additiveName", limitOpt.map(AdditiveLimit::getAdditiveName).orElse(null));
            row.put("usageAmount", usage);
            row.put("unit", unit);
            row.put("limitFound", limitOpt.isPresent());

            if (limitOpt.isEmpty()) {
                row.put("compliant", false);
                row.put("reason", "无对应类目限量数据");
                violations.add(row);
                continue;
            }
            AdditiveLimit limit = limitOpt.get();
            row.put("maxLimit", limit.getMaxLimit());
            row.put("limitUnit", limit.getUnit());

            if (usage == null) {
                row.put("compliant", null);
                row.put("reason", "未提供 usageAmount");
                violations.add(row);
            } else if (limit.getMaxLimit() != null
                    && usage.compareTo(limit.getMaxLimit()) > 0) {
                row.put("compliant", false);
                row.put("exceedRatio",
                        usage.subtract(limit.getMaxLimit())
                                .divide(limit.getMaxLimit(), 4, java.math.RoundingMode.HALF_UP));
                row.put("reason", "超 GB 2760 限量");
                violations.add(row);
            } else {
                row.put("compliant", true);
                compliantItems.add(row);
            }
        }

        boolean overallCompliant = violations.isEmpty();
        data.put("hasIngredients", true);
        data.put("totalChecked", ingredients.size());
        data.put("violationCount", violations.size());
        data.put("compliant", overallCompliant);
        data.put("violations", violations);
        data.put("compliantItems", compliantItems);

        String message;
        String releaseHint;
        if (overallCompliant) {
            message = String.format("✅ 批次 %s GB 2760 添加剂全部合规 (%d 项检查)",
                    batchNumber, ingredients.size());
            releaseHint = "RELEASE_OK";
        } else {
            message = String.format("🚨 批次 %s GB 2760 添加剂 %d 项超限/异常, 不可放行",
                    batchNumber, violations.size());
            releaseHint = "BLOCK_RELEASE";
            data.put("actionHint", "/quality/additive?batchNumber=" + batchNumber);
        }
        data.put("releaseHint", releaseHint);
        return buildSimpleResult(message, data);
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return new BigDecimal(v.toString());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
