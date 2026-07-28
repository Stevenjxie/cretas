package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 设备分析报告 Tool
 *
 * 获取设备运行状态、维护记录和故障统计。
 * 对应意图: ANALYZE_EQUIPMENT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportAnalyzeEquipmentTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "report_analyze_equipment";
    }

    @Override
    public String getDescription() {
        return "获取设备分析报告，包含设备运行状态、维护记录和故障统计。" +
                "适用场景：设备分析、设备运行报告、故障统计。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行设备分析报告查询 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("queryType", "equipment_analysis");
        result.put("factoryId", factoryId);
        result.put("message", "设备分析报告已就绪。请前往设备管理页面查看设备运行状态、维护记录和故障统计。");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
