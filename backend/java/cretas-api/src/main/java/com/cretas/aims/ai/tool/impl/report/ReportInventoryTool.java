package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Inventory Report Tool
 *
 * Provides inventory-related reports including stock levels,
 * inventory turnover, aging analysis, and stock movement.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ReportInventoryTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_inventory";
    }

    @Override
    public String getDescription() {
        return "获取库存报表，包含库存水平、库存周转、库龄分析、出入库流水等数据。" +
                "适用场景：库存盘点、库龄预警、周转率分析、库存成本分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // period: 时间周期（可选）
        Map<String, Object> period = new HashMap<>();
        period.put("type", "string");
        period.put("description", "时间周期：today(今日), week(本周), month(本月), quarter(本季度), year(本年)");
        period.put("enum", Arrays.asList("today", "week", "month", "quarter", "year"));
        period.put("default", "today");
        properties.put("period", period);

        // startDate: 开始日期（可选）
        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式: yyyy-MM-dd");
        startDate.put("format", "date");
        properties.put("startDate", startDate);

        // endDate: 结束日期（可选）
        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("description", "结束日期，格式: yyyy-MM-dd");
        endDate.put("format", "date");
        properties.put("endDate", endDate);

        // warehouseId: 仓库ID（可选）
        Map<String, Object> warehouseId = new HashMap<>();
        warehouseId.put("type", "string");
        warehouseId.put("description", "仓库ID，筛选特定仓库的库存数据");
        properties.put("warehouseId", warehouseId);

        // category: 物料类别（可选）
        Map<String, Object> category = new HashMap<>();
        category.put("type", "string");
        category.put("description", "物料类别筛选");
        category.put("enum", Arrays.asList("RAW_MATERIAL", "SEMI_FINISHED", "FINISHED", "PACKAGING", "ALL"));
        properties.put("category", category);

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
        log.info("执行库存报表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析时间参数
        String period = getString(params, "period", "today");
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        String warehouseId = getString(params, "warehouseId");
        String category = getString(params, "category");

        // 计算报表日期
        LocalDate reportDate = LocalDate.now();
        if (endDateStr != null) {
            reportDate = LocalDate.parse(endDateStr);
        }

        // 调用服务获取库存报表数据
        Map<String, Object> inventoryData = reportService.getInventoryReport(factoryId, reportDate);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "INVENTORY");
        result.put("period", period);
        result.put("reportDate", reportDate.format(DateTimeFormatter.ISO_DATE));
        result.put("generatedAt", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        result.put("data", inventoryData);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        queryConditions.put("period", period);
        queryConditions.put("reportDate", reportDate.format(DateTimeFormatter.ISO_DATE));
        if (warehouseId != null) queryConditions.put("warehouseId", warehouseId);
        if (category != null) queryConditions.put("category", category);
        result.put("queryConditions", queryConditions);

        log.info("库存报表查询完成 - 工厂ID: {}, 报表日期: {}", factoryId, reportDate);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "period", "请问您想查看哪个时间点的库存数据？可选：今日、本周、本月、本季度、本年。",
            "startDate", "请问报表的开始日期是？（格式：yyyy-MM-dd）",
            "endDate", "请问报表的结束日期是？（格式：yyyy-MM-dd）",
            "warehouseId", "请问要查看哪个仓库的库存？如需全部仓库可不指定。",
            "category", "请问要查看哪类物料的库存？可选：原材料、半成品、成品、包装材料、全部。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "period", "时间周期",
            "startDate", "开始日期",
            "endDate", "结束日期",
            "warehouseId", "仓库ID",
            "category", "物料类别"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
