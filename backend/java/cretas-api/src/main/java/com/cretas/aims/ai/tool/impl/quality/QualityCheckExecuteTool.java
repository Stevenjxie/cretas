package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.repository.QualityInspectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 质检执行评估工具
 *
 * 获取指定生产批次的最新质检结果。
 *
 * Intent Code: QUALITY_CHECK_EXECUTE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class QualityCheckExecuteTool extends AbstractBusinessTool {

    @Autowired
    private QualityInspectionRepository qualityInspectionRepository;

    @Override
    public String getToolName() {
        return "quality_check_execute";
    }

    @Override
    public String getDescription() {
        return "执行质检评估。获取指定生产批次的最新质检结果，包括合格率、检验数据等。" +
                "适用场景：查看批次质检结果、评估产品质量状态。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> productionBatchId = new HashMap<>();
        productionBatchId.put("type", "integer");
        productionBatchId.put("description", "生产批次ID");
        properties.put("productionBatchId", productionBatchId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("productionBatchId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("productionBatchId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行质检评估 - 工厂ID: {}, 参数: {}", factoryId, params);

        Long productionBatchId = getLong(params, "productionBatchId");

        if (productionBatchId == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "请提供生产批次ID (productionBatchId)");
            return result;
        }

        Optional<QualityInspection> latestInspection =
                qualityInspectionRepository.findFirstByFactoryIdAndProductionBatchIdOrderByInspectionDateDesc(
                        factoryId, productionBatchId);

        if (latestInspection.isEmpty()) {
            return buildSimpleResult(
                    "该批次暂无质检记录，可能尚未进行质量检验或记录未同步。",
                    Map.of("productionBatchId", productionBatchId, "hasInspection", false)
            );
        }

        QualityInspection inspection = latestInspection.get();
        String qualityStatus = determineQualityStatus(inspection);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productionBatchId", productionBatchId);
        data.put("inspectionId", inspection.getId());
        data.put("inspectionDate", inspection.getInspectionDate().toString());
        data.put("sampleSize", inspection.getSampleSize());
        data.put("passCount", inspection.getPassCount());
        data.put("failCount", inspection.getFailCount());
        data.put("passRate", inspection.getPassRate());
        data.put("result", inspection.getResult());
        data.put("qualityStatus", qualityStatus);

        return buildSimpleResult(
                "批次质检结果: " + qualityStatus + " (合格率: " + inspection.getPassRate() + "%)",
                data
        );
    }

    private String determineQualityStatus(QualityInspection inspection) {
        BigDecimal passRate = inspection.getPassRate();
        if (passRate == null) return "未知";
        if (passRate.compareTo(new BigDecimal("95")) >= 0) return "优秀";
        if (passRate.compareTo(new BigDecimal("85")) >= 0) return "合格";
        if (passRate.compareTo(new BigDecimal("70")) >= 0) return "待处理";
        return "不合格";
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("productionBatchId".equals(paramName)) {
            return "请提供生产批次ID";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("productionBatchId".equals(paramName)) {
            return "生产批次ID";
        }
        return super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
