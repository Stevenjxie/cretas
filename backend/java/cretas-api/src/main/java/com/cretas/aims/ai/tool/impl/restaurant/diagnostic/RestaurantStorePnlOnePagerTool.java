package com.cretas.aims.ai.tool.impl.restaurant.diagnostic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * P&L 一页纸 — Sprint 11.5 Phase 1 wired-up version.
 *
 * <p>Pre-Sprint 11.5: pure wrapper, Python returned
 * "未提供 financial_metrics (需 DiagnosticsHandler 先运行或直接传入)" because
 * no upstream populated context for the composite path.
 *
 * <p>Sprint 11.5 Phase 1: this Tool now overrides {@link #buildSectionParams}
 * to fetch {@code financial_metrics} from {@code smart_bi_finance_data} via
 * {@link RestaurantFinancialMetricsFetcher} and inject under
 * {@code params["financial_metrics"]} — Python section reads it via
 * {@code request.params.get("financial_metrics")} (see Python
 * {@code backend/python/smartbi/services/restaurant/sections/store_pnl_one_pager.py}
 * line 96).
 *
 * <p>If no REVENUE+COST data exists for the resolved period, we do NOT
 * inject — let Python emit its canonical "未提供 financial_metrics" skip so
 * the user-facing message stays consistent with the no-data path.
 */
@Slf4j
@Component
public class RestaurantStorePnlOnePagerTool extends AbstractRestaurantDiagnosticTool {

    @Autowired
    protected RestaurantFinancialMetricsFetcher metricsFetcher;

    @Override
    public String getToolName() {
        return "restaurant_store_pnl_one_pager";
    }

    @Override
    public String getDescription() {
        return "单店 P&L 一页纸 (headline + 财务分解 + 诊断聚合) - 生成完整的单店利润表简报, 含 headline 金句和关键警告. 适用场景: 客户问'给我这家店完整利润表'/'一页纸总结这家店的经营状况'.";
    }

    @Override
    protected String getSectionName() {
        return "store_pnl_one_pager";
    }

    /**
     * Inject {@code financial_metrics} (camelCase dict per Python
     * {@code FinancialMetrics.to_dict()}) into the Python section params.
     * Caller may override by passing {@code financial_metrics} directly in
     * {@code params}; otherwise we fetch from {@code smart_bi_finance_data}
     * for the {@code month} param (default "上月").
     */
    @Override
    protected Map<String, Object> buildSectionParams(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) {

        Map<String, Object> sectionParams = super.buildSectionParams(factoryId, params, context);

        // 1) Caller-supplied financial_metrics wins (lets tests / composite
        // upstream stages override). Skip auto-fetch.
        if (sectionParams.get("financial_metrics") instanceof Map) {
            return sectionParams;
        }

        // 2) Try context["financial_metrics"] (future composite handoff).
        Object fromContext = context.get("financial_metrics");
        if (fromContext instanceof Map) {
            sectionParams.put("financial_metrics", fromContext);
            return sectionParams;
        }

        // 3) Auto-fetch from smart_bi_finance_data.
        String month = getString(params, "month");
        Optional<Map<String, Object>> metricsOpt = metricsFetcher.fetch(factoryId, month);
        if (metricsOpt.isPresent()) {
            sectionParams.put("financial_metrics", metricsOpt.get());
            log.info("RestaurantStorePnlOnePagerTool: injected financial_metrics for factory={} keys={}",
                    factoryId, metricsOpt.get().keySet());
        } else {
            log.info("RestaurantStorePnlOnePagerTool: no financial_metrics available for factory={} month={} — Python will skip",
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
