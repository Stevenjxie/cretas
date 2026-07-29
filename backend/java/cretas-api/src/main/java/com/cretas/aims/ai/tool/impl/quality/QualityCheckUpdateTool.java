package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.service.QualityInspectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 更新质检任务工具
 *
 * 用于更新现有质检任务的信息，包括检验结果、检验员、备注等。
 * 支持录入检验数据和更新任务状态。
 *
 * 业务规则：
 * 1. 必须指定质检任务ID (checkId)
 * 2. 更新结果时会自动计算合格率
 * 3. 仅质检员及以上权限可执行更新操作
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class QualityCheckUpdateTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private QualityInspectionService qualityInspectionService;

    @Override
    public String getToolName() {
        return "quality_check_update";
    }

    @Override
    public String getDescription() {
        return "更新质检任务信息。需要指定质检任务ID，可更新检验结果、检验员、样本数据等。" +
                "适用场景：录入质检结果、更新质检状态、修改检验员、添加备注。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // checkId: 质检任务ID（必需）
        Map<String, Object> checkId = new HashMap<>();
        checkId.put("type", "string");
        checkId.put("description", "质检任务ID，用于标识要更新的质检记录");
        properties.put("checkId", checkId);

        // status: 检验结果状态（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "检验结果状态");
        status.put("enum", Arrays.asList(
                "PENDING",      // 待检验
                "PASS",         // 合格
                "FAIL",         // 不合格
                "CONDITIONAL"   // 条件通过
        ));
        properties.put("status", status);

        // result: 检验结果（可选，与status同义）
        Map<String, Object> result = new HashMap<>();
        result.put("type", "string");
        result.put("description", "检验结果，与status参数作用相同");
        result.put("enum", Arrays.asList("PENDING", "PASS", "FAIL", "CONDITIONAL"));
        properties.put("result", result);

        // inspectorId: 检验员ID（可选）
        Map<String, Object> inspectorId = new HashMap<>();
        inspectorId.put("type", "integer");
        inspectorId.put("description", "更换检验员，提供新的检验员用户ID");
        properties.put("inspectorId", inspectorId);

        // sampleSize: 样本数量（可选）
        Map<String, Object> sampleSize = new HashMap<>();
        sampleSize.put("type", "number");
        sampleSize.put("description", "抽样数量");
        sampleSize.put("minimum", 1);
        properties.put("sampleSize", sampleSize);

        // passCount: 合格数量（可选）
        Map<String, Object> passCount = new HashMap<>();
        passCount.put("type", "number");
        passCount.put("description", "合格数量");
        passCount.put("minimum", 0);
        properties.put("passCount", passCount);

        // failCount: 不合格数量（可选）
        Map<String, Object> failCount = new HashMap<>();
        failCount.put("type", "number");
        failCount.put("description", "不合格数量");
        failCount.put("minimum", 0);
        properties.put("failCount", failCount);

        // notes: 备注（可选）
        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "质检任务备注信息，追加到现有备注");
        notes.put("maxLength", 500);
        properties.put("notes", notes);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("checkId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("checkId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 1. 解析参数
        String checkId = getString(params, "checkId");
        String status = getString(params, "status");
        String resultParam = getString(params, "result");
        Long inspectorId = getLong(params, "inspectorId");
        BigDecimal sampleSize = getBigDecimal(params, "sampleSize");
        BigDecimal passCount = getBigDecimal(params, "passCount");
        BigDecimal failCount = getBigDecimal(params, "failCount");
        String notes = getString(params, "notes");

        // 处理status和result参数（二者同义）
        String finalResult = resultParam != null ? resultParam : status;

        log.info("更新质检任务 - 工厂: {}, 任务ID: {}, 结果: {}, 检验员: {}",
                factoryId, checkId, finalResult, inspectorId);

        // 2. 获取现有质检记录
        QualityInspection existing = qualityInspectionService.getInspectionById(factoryId, checkId);
        if (existing == null) {
            throw new IllegalArgumentException("未找到指定的质检任务: " + checkId);
        }

        // 3. 更新字段
        List<String> updatedFields = new ArrayList<>();

        if (finalResult != null && !finalResult.trim().isEmpty()) {
            // R41 BUG-11 fix: normalize to canonical enum (PASS/FAIL/CONDITIONAL).
            // Previously toUpperCase() let "passed"/"failed" sneak in → DB pollution.
            String upper = finalResult.trim().toUpperCase();
            String canonical = switch (upper) {
                case "PASS", "PASSED" -> "PASS";
                case "FAIL", "FAILED" -> "FAIL";
                case "CONDITIONAL", "PARTIAL" -> "CONDITIONAL";
                default -> throw new IllegalArgumentException(
                        "检验结果只能是 PASS/FAIL/CONDITIONAL, 收到: " + finalResult);
            };
            existing.setResult(canonical);
            updatedFields.add("检验结果");
        }

        if (inspectorId != null) {
            existing.setInspectorId(inspectorId);
            updatedFields.add("检验员");
        }

        if (sampleSize != null) {
            existing.setSampleSize(sampleSize);
            updatedFields.add("样本数量");
        }

        if (passCount != null) {
            existing.setPassCount(passCount);
            updatedFields.add("合格数量");
        }

        if (failCount != null) {
            existing.setFailCount(failCount);
            updatedFields.add("不合格数量");
        }

        // 自动计算合格率
        if (existing.getSampleSize() != null &&
            existing.getSampleSize().compareTo(BigDecimal.ZERO) > 0 &&
            existing.getPassCount() != null) {
            BigDecimal passRate = existing.getPassCount()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(existing.getSampleSize(), 2, RoundingMode.HALF_UP);
            existing.setPassRate(passRate);
            updatedFields.add("合格率(自动计算)");
        }

        // 追加备注
        if (notes != null && !notes.trim().isEmpty()) {
            String existingNotes = existing.getNotes();
            if (existingNotes != null && !existingNotes.isEmpty()) {
                existing.setNotes(existingNotes + " | " + notes);
            } else {
                existing.setNotes(notes);
            }
            updatedFields.add("备注");
        }

        if (updatedFields.isEmpty()) {
            return buildSimpleResult("未提供任何更新字段", Map.of(
                    "inspectionId", checkId,
                    "updated", false
            ));
        }

        // 4. 调用服务更新
        QualityInspection updated = qualityInspectionService.updateInspection(factoryId, checkId, existing);

        // 5. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("inspectionId", updated.getId());
        result.put("productionBatchId", updated.getProductionBatchId());
        result.put("inspectorId", updated.getInspectorId());
        result.put("inspectionDate", updated.getInspectionDate() != null ?
                updated.getInspectionDate().toString() : null);
        result.put("sampleSize", updated.getSampleSize());
        result.put("passCount", updated.getPassCount());
        result.put("failCount", updated.getFailCount());
        result.put("passRate", updated.getPassRate());
        result.put("result", updated.getResult());
        result.put("qualityGrade", updated.getQualityGrade());
        result.put("notes", updated.getNotes());
        result.put("updatedFields", updatedFields);
        result.put("message", String.format("质检任务更新成功，已更新: %s",
                String.join("、", updatedFields)));

        log.info("质检任务更新完成 - ID: {}, 更新字段: {}", checkId, updatedFields);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "checkId" -> "请问要更新哪个质检任务？请提供质检任务ID。";
            case "status", "result" -> "请问检验结果是什么？可选：待检验(PENDING)、合格(PASS)、不合格(FAIL)、条件通过(CONDITIONAL)";
            case "inspectorId" -> "请问更换为哪位检验员？请提供检验员ID。（可选）";
            case "sampleSize" -> "请问抽样数量是多少？（可选）";
            case "passCount" -> "请问合格数量是多少？（可选）";
            case "failCount" -> "请问不合格数量是多少？（可选）";
            case "notes" -> "请问有什么备注信息要添加吗？（可选）";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        return switch (paramName) {
            case "checkId" -> "质检任务ID";
            case "status" -> "检验结果状态";
            case "result" -> "检验结果";
            case "inspectorId" -> "检验员ID";
            case "sampleSize" -> "样本数量";
            case "passCount" -> "合格数量";
            case "failCount" -> "不合格数量";
            case "notes" -> "备注";
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
                "factory_admin".equals(userRole) ||
                "quality_inspector".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
