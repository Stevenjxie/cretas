package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.client.PythonSmartBIClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 财务PPT导出 Tool
 *
 * 将财务分析图表导出为PPT演示文稿。
 * 需要提供图表截图(base64)和AI分析结果。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-09
 */
@Slf4j
@Component
public class FinancePptExportTool extends AbstractBusinessTool {

    @Autowired
    private PythonSmartBIClient pythonClient;

    @Override
    public String getToolName() {
        return "finance_ppt_export";
    }

    @Override
    public String getDescription() {
        return "将财务分析图表导出为PPT演示文稿。需要提供图表截图(base64)和AI分析结果。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("chart_images", Map.of(
            "type", "object",
            "description", "图表截图，格式: {chartType: base64PNG}"
        ));
        props.put("analysis_results", Map.of(
            "type", "object",
            "description", "AI分析结果，格式: {chartType: 分析文本}"
        ));
        props.put("year", Map.of("type", "integer", "description", "分析年份"));
        props.put("company_name", Map.of("type", "string", "description", "公司名称"));

        return Map.of(
            "type", "object",
            "properties", props,
            "required", List.of("chart_images")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("chart_images");
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Map<String, String> chartImages = (Map<String, String>) params.get("chart_images");
        Map<String, String> analysisResults = (Map<String, String>) params.getOrDefault("analysis_results", new HashMap<>());
        Integer year = getInteger(params, "year", 2026);
        String companyName = getString(params, "company_name", "白垩纪科技");

        log.info("Exporting financial PPT: {} charts, company={}", chartImages.size(), companyName);

        // factoryId used as request origin marker — no data lookup needed here,
        // all chart data is sent by the caller as base64 images.
        Map<String, Object> request = new HashMap<>();
        request.put("factory_id", factoryId);
        request.put("chart_images", chartImages);
        request.put("analysis_results", analysisResults);
        request.put("year", year);
        request.put("company_name", companyName);

        Map<String, Object> result = pythonClient.callFinancialDashboard(
                "/export-ppt", request, factoryId);

        if (result == null) {
            return buildSimpleResult("PPT导出失败：Python服务不可用", null);
        }

        return buildSimpleResult("PPT导出成功", result);
    }
}
