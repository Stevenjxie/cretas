package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 销售报表 Tool
 *
 * 获取销售统计和排名数据。
 * 对应意图: SALES_STATS, SALES_RANKING, PRODUCT_SALES_RANKING
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class ReportSalesTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_sales";
    }

    @Override
    public String getDescription() {
        return "获取销售报表，包含销售统计、销售排名、产品销售排名等数据。" +
                "适用场景：销售统计查询、销售排名查看、产品销售排行。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> period = new HashMap<>();
        period.put("type", "string");
        period.put("description", "时间周期：daily(日), weekly(周), monthly(月), quarterly(季), yearly(年)");
        period.put("default", "monthly");
        properties.put("period", period);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行销售报表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String period = getString(params, "period", "monthly");

        Map<String, Object> dashboard = reportService.getDashboardOverview(factoryId, period);

        Map<String, Object> result = new HashMap<>();
        result.put("dashboard", dashboard);
        result.put("reportType", "sales");
        result.put("period", period);
        result.put("message", "销售报表获取成功，周期: " + period);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "period", "请问您要查看哪个时间段的销售数据？可选：日(daily)、周(weekly)、月(monthly)、季(quarterly)、年(yearly)。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "period", "时间周期"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
