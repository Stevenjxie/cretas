package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.service.QualityInspectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 创建质检任务工具
 *
 * 用于创建新的质量检验任务记录。支持指定检验批次、检验类型、检验员等信息。
 * 创建后的质检任务可用于跟踪和管理产品质量检验流程。
 *
 * 业务规则：
 * 1. 必须指定生产批次ID
 * 2. 必须指定质检类型
 * 3. 检验员ID为可选，如未指定则使用当前用户
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class QualityCheckCreateTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private QualityInspectionService qualityInspectionService;

    @Override
    public String getToolName() {
        return "quality_check_create";
    }

    @Override
    public String getDescription() {
        return "创建质检任务。需要指定生产批次ID和质检类型，可选指定检验员和计划时间。" +
                "适用场景：新建质检任务、安排质检计划、记录质检需求。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 生产批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "生产批次ID，用于关联要进行质检的批次");
        properties.put("batchId", batchId);

        // checkType: 质检类型（必需）
        Map<String, Object> checkType = new HashMap<>();
        checkType.put("type", "string");
        checkType.put("description", "质检类型，如：感官检验、理化检验、微生物检验等");
        checkType.put("enum", Arrays.asList(
                "SENSORY",          // 感官检验
                "PHYSICAL",         // 物理检验
                "CHEMICAL",         // 化学检验
                "MICROBIOLOGICAL",  // 微生物检验
                "MATERIAL"          // 原材料检验
        ));
        properties.put("checkType", checkType);

        // inspectorId: 检验员ID（可选）
        Map<String, Object> inspectorId = new HashMap<>();
        inspectorId.put("type", "integer");
        inspectorId.put("description", "检验员用户ID，不指定则使用当前用户");
        properties.put("inspectorId", inspectorId);

        // scheduledTime: 计划检验时间（可选）
        Map<String, Object> scheduledTime = new HashMap<>();
        scheduledTime.put("type", "string");
        scheduledTime.put("description", "计划检验时间，格式：yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss，不指定则默认今天");
        properties.put("scheduledTime", scheduledTime);

        // notes: 备注（可选）
        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "质检任务备注信息");
        notes.put("maxLength", 500);
        properties.put("notes", notes);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId", "checkType"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId", "checkType");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 1. 解析参数
        String batchIdStr = getString(params, "batchId");
        String checkType = getString(params, "checkType");
        Long inspectorId = getLong(params, "inspectorId");
        String scheduledTimeStr = getString(params, "scheduledTime");
        String notes = getString(params, "notes");

        // 2. 参数验证
        Long productionBatchId;
        try {
            productionBatchId = Long.parseLong(batchIdStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("批次ID格式无效，请提供有效的数字ID");
        }

        // 检验员ID：如果未指定，从context获取当前用户ID
        if (inspectorId == null) {
            Object userIdObj = context.get("userId");
            if (userIdObj != null) {
                if (userIdObj instanceof Long) {
                    inspectorId = (Long) userIdObj;
                } else {
                    try {
                        inspectorId = Long.parseLong(userIdObj.toString());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("无法获取检验员ID");
                    }
                }
            } else {
                throw new IllegalArgumentException("请指定检验员ID (inspectorId)");
            }
        }

        // 解析计划检验时间
        LocalDate inspectionDate = LocalDate.now();
        if (scheduledTimeStr != null && !scheduledTimeStr.trim().isEmpty()) {
            inspectionDate = parseDate(scheduledTimeStr);
        }

        log.info("创建质检任务 - 工厂: {}, 批次ID: {}, 类型: {}, 检验员: {}, 计划日期: {}",
                factoryId, productionBatchId, checkType, inspectorId, inspectionDate);

        // 3. 构建质检记录实体
        QualityInspection inspection = QualityInspection.builder()
                .factoryId(factoryId)
                .productionBatchId(productionBatchId)
                .inspectorId(inspectorId)
                .inspectionDate(inspectionDate)
                .sampleSize(BigDecimal.ZERO)  // 待填写
                .passCount(BigDecimal.ZERO)   // 待填写
                .failCount(BigDecimal.ZERO)   // 待填写
                .result("PENDING")            // 待检验
                .notes(buildNotes(checkType, notes))
                .build();

        // 4. 调用服务创建
        QualityInspection created = qualityInspectionService.createInspection(factoryId, inspection);

        // 5. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("inspectionId", created.getId());
        result.put("productionBatchId", created.getProductionBatchId());
        result.put("checkType", checkType);
        result.put("inspectorId", created.getInspectorId());
        result.put("inspectionDate", created.getInspectionDate().toString());
        result.put("status", "PENDING");
        result.put("message", String.format("质检任务创建成功，检验ID: %s，批次: %s，类型: %s，计划日期: %s",
                created.getId(),
                created.getProductionBatchId(),
                getCheckTypeDisplayName(checkType),
                created.getInspectionDate()));

        log.info("质检任务创建完成 - ID: {}", created.getId());

        return result;
    }

    /**
     * 解析日期字符串
     */
    private LocalDate parseDate(String dateStr) {
        // 尝试多种格式
        String[] patterns = {
                "yyyy-MM-dd",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy/MM/dd",
                "yyyyMMdd"
        };

        for (String pattern : patterns) {
            try {
                if (pattern.contains("HH")) {
                    return LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern(pattern)).toLocalDate();
                } else {
                    return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern(pattern));
                }
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式
            }
        }

        throw new IllegalArgumentException("日期格式无效，请使用 yyyy-MM-dd 格式");
    }

    /**
     * 构建备注信息
     */
    private String buildNotes(String checkType, String userNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("[质检类型: ").append(getCheckTypeDisplayName(checkType)).append("]");
        if (userNotes != null && !userNotes.trim().isEmpty()) {
            sb.append(" ").append(userNotes);
        }
        return sb.toString();
    }

    /**
     * 获取质检类型显示名称
     */
    private String getCheckTypeDisplayName(String checkType) {
        if (checkType == null) return "未知";
        return switch (checkType.toUpperCase()) {
            case "SENSORY" -> "感官检验";
            case "PHYSICAL" -> "物理检验";
            case "CHEMICAL" -> "化学检验";
            case "MICROBIOLOGICAL" -> "微生物检验";
            case "MATERIAL" -> "原材料检验";
            default -> checkType;
        };
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "batchId" -> "请问您要对哪个生产批次进行质检？请提供批次ID。";
            case "checkType" -> "请问进行什么类型的质检？可选：感官检验(SENSORY)、物理检验(PHYSICAL)、化学检验(CHEMICAL)、微生物检验(MICROBIOLOGICAL)、原材料检验(MATERIAL)";
            case "inspectorId" -> "请问由哪位检验员负责？请提供检验员ID。（可选，不指定则使用当前用户）";
            case "scheduledTime" -> "请问计划什么时候进行检验？格式：yyyy-MM-dd（可选，不指定则默认今天）";
            case "notes" -> "请问有什么备注信息吗？（可选）";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        return switch (paramName) {
            case "batchId" -> "生产批次ID";
            case "checkType" -> "质检类型";
            case "inspectorId" -> "检验员ID";
            case "scheduledTime" -> "计划检验时间";
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
