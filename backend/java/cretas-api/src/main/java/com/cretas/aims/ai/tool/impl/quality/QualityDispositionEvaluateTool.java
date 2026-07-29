package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.service.QualityDispositionRuleService;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 质检处置评估工具
 *
 * 根据质检结果评估处置建议（放行/返工/报废等）。
 *
 * Intent Code: QUALITY_DISPOSITION_EVALUATE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class QualityDispositionEvaluateTool extends AbstractBusinessTool {

    @Autowired
    private QualityInspectionRepository qualityInspectionRepository;

    @Autowired
    private QualityDispositionRuleService qualityDispositionRuleService;

    @Override
    public String getToolName() {
        return "quality_disposition_evaluate";
    }

    @Override
    public String getDescription() {
        return "评估质检处置建议。根据批次质检结果，评估推荐的处置方案（放行/条件放行/返工/报废/待定）。" +
                "适用场景：质检不合格时获取处置建议、评估批次处理方案。";
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
        log.info("执行质检处置评估 - 工厂ID: {}, 参数: {}", factoryId, params);

        Long productionBatchId = getLong(params, "productionBatchId");

        if (productionBatchId == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "请提供生产批次ID (productionBatchId) 以评估处置建议");
            return result;
        }

        Optional<QualityInspection> latestInspection =
                qualityInspectionRepository.findFirstByFactoryIdAndProductionBatchIdOrderByInspectionDateDesc(
                        factoryId, productionBatchId);

        if (latestInspection.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "该批次无质检记录，无法评估处置建议");
            return result;
        }

        QualityInspection inspection = latestInspection.get();
        DispositionResult dispositionResult = qualityDispositionRuleService.evaluateDisposition(factoryId, inspection);

        String recommendedActionDesc = getDispositionActionDesc(dispositionResult.getRecommendedAction().name());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productionBatchId", productionBatchId);
        data.put("inspectionId", inspection.getId());
        data.put("passRate", inspection.getPassRate());
        data.put("recommendedAction", dispositionResult.getRecommendedAction());
        data.put("recommendedActionDesc", recommendedActionDesc);
        data.put("requiresApproval", dispositionResult.isRequiresApproval());
        data.put("confidence", dispositionResult.getConfidence());
        data.put("reason", dispositionResult.getReason() != null ? dispositionResult.getReason() : "");

        return buildSimpleResult(
                "处置建议: " + recommendedActionDesc + " (置信度: " + dispositionResult.getConfidence() + "%)",
                data
        );
    }

    private String getDispositionActionDesc(String action) {
        if (action == null) return "未知";
        return switch (action.toUpperCase()) {
            case "RELEASE" -> "放行";
            case "CONDITIONAL_RELEASE" -> "条件放行";
            case "REWORK" -> "返工";
            case "SCRAP" -> "报废";
            case "SPECIAL_APPROVAL" -> "特批申请";
            case "HOLD" -> "待定";
            default -> action;
        };
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("productionBatchId".equals(paramName)) {
            return "请提供需要评估处置的生产批次ID";
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

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
