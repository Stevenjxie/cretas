package com.cretas.aims.ai.tool.impl.restaurant.diagnostic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Restaurant shrinkage analysis tool — wraps the Python shrinkage_analysis
 * section (P3.5C B4). Compares per-department standard cost (from BOM) vs
 * actual cost (from monthly inventory count), ranks offenders with variance
 * rate > 2%, and auto-generates Chinese action items with severity-based
 * suggestion text.
 *
 * <p>Activated by intents like "哪个档口损溢最多" / "标准成本 vs 实际" /
 * "档口变差原因" / "月度盘点差异".
 *
 * <p>Sprint 12 Phase B: overrides {@link #buildSectionParams} to inject
 * {@code shrinkage_rows} fetched from {@code wastage_records} JOIN
 * {@code raw_material_types} via {@link RestaurantShrinkageDataFetcher} —
 * mirrors the {@link RestaurantStorePnlOnePagerTool} Phase 1 pattern (Java-side
 * data assembly before Python section call). Pre-Sprint 12 default
 * {@code buildSectionParams()} passed nothing → Python skipped → Composite
 * Tool reported "档口损溢 数据不可用" for every customer query.
 */
@Slf4j
@Component
public class RestaurantShrinkageAnalysisTool extends AbstractRestaurantDiagnosticTool {

    @Autowired
    protected RestaurantShrinkageDataFetcher shrinkageDataFetcher;

    @Override
    public String getToolName() {
        return "restaurant_shrinkage_analysis";
    }

    @Override
    public String getDescription() {
        return "档口标准成本 vs 实际成本差异分析 (损溢). 基于 BOM × 销量计算标准成本, "
             + "对比月度盘点实际成本, 识别超标档口 (>2% 变异率) + 生成 Chinese 改进跟踪表. "
             + "适用场景: 客户问'哪个档口损溢最多'/'标准成本对不对'/'为什么实际成本高'/"
             + "'月度盘点差在哪'.";
    }

    @Override
    protected String getSectionName() {
        return "shrinkage_analysis";
    }

    /**
     * Inject {@code shrinkage_rows} (camelCase list per Python
     * {@code shrinkage_analysis} contract) into the Python section params.
     * Caller may override by passing {@code shrinkage_rows} directly in
     * {@code params}; otherwise we fetch from {@code wastage_records} joined to
     * {@code raw_material_types.category} for the {@code month} param
     * (default "上月").
     */
    @Override
    protected Map<String, Object> buildSectionParams(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) {

        Map<String, Object> sectionParams = super.buildSectionParams(factoryId, params, context);

        // 1) Caller-supplied shrinkage_rows wins (tests / composite upstream override).
        if (sectionParams.get("shrinkage_rows") instanceof List) {
            return sectionParams;
        }

        // 2) Try context["shrinkage_rows"] (future composite handoff path).
        Object fromContext = context.get("shrinkage_rows");
        if (fromContext instanceof List) {
            sectionParams.put("shrinkage_rows", fromContext);
            return sectionParams;
        }

        // 3) Auto-fetch from cretas_prod_db wastage_records + raw_material_types.
        String month = getString(params, "month");
        Optional<List<Map<String, Object>>> rowsOpt = shrinkageDataFetcher.fetch(factoryId, month);
        if (rowsOpt.isPresent()) {
            sectionParams.put("shrinkage_rows", rowsOpt.get());
            log.info("RestaurantShrinkageAnalysisTool: injected {} shrinkage_rows for factory={}",
                    rowsOpt.get().size(), factoryId);
        } else {
            log.info("RestaurantShrinkageAnalysisTool: no shrinkage_rows available for factory={} month={} — Python will skip",
                    factoryId, month);
        }
        return sectionParams;
    }

    /**
     * Lift {@code month} param into the schema (optional) so the LLM can pass
     * "2026-04" / "上月" without dropping into the unknown-arg sink. Other
     * params inherited from {@link AbstractRestaurantDiagnosticTool}.
     */
    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = super.getParametersSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties == null) {
            properties = new HashMap<>();
            schema.put("properties", properties);
        }
        Map<String, Object> monthSchema = new HashMap<>();
        monthSchema.put("type", "string");
        monthSchema.put("description",
                "月份, 支持 '上月' / '本月' / 'YYYY-MM' / 'YYYY年M月', 默认 '上月'");
        properties.put("month", monthSchema);
        return schema;
    }
}
