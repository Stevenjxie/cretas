package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 车间日报 / 高管日报 Tool
 *
 * 获取车间日报数据，包含生产和总览信息。
 * 对应意图: REPORT_WORKSHOP_DAILY, REPORT_EXECUTIVE_DAILY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportWorkshopDailyTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_workshop_daily";
    }

    @Override
    public String getDescription() {
        return "获取车间日报/高管日报，包含生产产量、质量合格率、设备利用率等核心指标。" +
                "适用场景：车间日报、高管每日简报、生产概况。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> period = new HashMap<>();
        period.put("type", "string");
        period.put("description", "时间周期：today(今日), week(本周), month(本月)");
        period.put("default", "today");
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
        log.info("执行车间日报查询 - 工厂ID: {}", factoryId);

        String period = getString(params, "period", "today");

        Map<String, Object> production = reportService.getProductionDashboard(factoryId, period);
        Map<String, Object> overview = reportService.getDashboardOverview(factoryId, period);

        Map<String, Object> result = new HashMap<>();
        result.put("production", production);
        result.put("overview", overview);
        result.put("reportType", "workshop_daily");
        result.put("period", period);
        result.put("message", "车间日报获取成功，统计周期: " + period + "。包含生产产量、质量合格率、设备利用率等核心指标。");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
