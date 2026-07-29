package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.service.QualityDispositionRuleService;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 质检处置执行工具
 *
 * 执行处置动作（放行/返工/报废等）。
 *
 * Intent Code: QUALITY_DISPOSITION_EXECUTE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class QualityDispositionExecuteTool extends AbstractBusinessTool {

    @Autowired
    private QualityInspectionRepository qualityInspectionRepository;

    @Autowired
    private QualityDispositionRuleService qualityDispositionRuleService;

    private static final List<String> VALID_ACTIONS = List.of(
            "RELEASE", "CONDITIONAL_RELEASE", "REWORK", "SCRAP", "SPECIAL_APPROVAL", "HOLD"
    );

    @Override
    public String getToolName() {
        return "quality_disposition_execute";
    }

    @Override
    public String getDescription() {
        return "执行质检处置动作。对指定批次执行处置操作，如放行、返工、报废等。" +
                "适用场景：确认并执行质检处置决定。";
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

        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("description", "处置动作: RELEASE(放行), CONDITIONAL_RELEASE(条件放行), REWORK(返工), SCRAP(报废), HOLD(待定)");
        action.put("enum", VALID_ACTIONS);
        properties.put("action", action);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "处置原因说明");
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("productionBatchId", "action"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("productionBatchId", "action");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行质检处置 - 工厂ID: {}, 参数: {}", factoryId, params);

        Long productionBatchId = getLong(params, "productionBatchId");
        String actionCode = getString(params, "action");
        String reason = getString(params, "reason");

        if (productionBatchId == null || actionCode == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "请提供生产批次ID和处置动作。支持: RELEASE, CONDITIONAL_RELEASE, REWORK, SCRAP, HOLD");
            return result;
        }

        actionCode = actionCode.toUpperCase();

        if (!VALID_ACTIONS.contains(actionCode)) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "无效的处置动作: " + actionCode + "。支持: " + String.join(", ", VALID_ACTIONS));
            return result;
        }

        Optional<QualityInspection> latestInspection =
                qualityInspectionRepository.findFirstByFactoryIdAndProductionBatchIdOrderByInspectionDateDesc(
                        factoryId, productionBatchId);

        if (latestInspection.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "该批次无质检记录，无法执行处置");
            return result;
        }

        QualityInspection inspection = latestInspection.get();
        Long userId = getUserId(context);

        var executionResult = qualityDispositionRuleService.executeDisposition(
                factoryId, inspection, DispositionAction.valueOf(actionCode), userId, reason);

        String actionDesc = getDispositionActionDesc(actionCode);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productionBatchId", productionBatchId);
        data.put("action", actionCode);
        data.put("actionDesc", actionDesc);
        data.put("success", executionResult.isSuccess());
        data.put("resultMessage", executionResult.getMessage());
        if (reason != null) {
            data.put("reason", reason);
        }

        String message = executionResult.isSuccess()
                ? "处置执行成功: " + actionDesc
                : "处置执行失败: " + executionResult.getMessage();

        return buildSimpleResult(message, data);
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
        return switch (paramName) {
            case "productionBatchId" -> "请提供要处置的生产批次ID";
            case "action" -> "请选择处置动作: 放行(RELEASE), 条件放行(CONDITIONAL_RELEASE), 返工(REWORK), 报废(SCRAP), 待定(HOLD)";
            case "reason" -> "请说明处置原因";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        return switch (paramName) {
            case "productionBatchId" -> "生产批次ID";
            case "action" -> "处置动作";
            case "reason" -> "处置原因";
            default -> super.getParameterDisplayName(paramName);
        };
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "platform_admin".equals(userRole) ||
                "quality_inspector".equals(userRole) ||
                "quality_manager".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
