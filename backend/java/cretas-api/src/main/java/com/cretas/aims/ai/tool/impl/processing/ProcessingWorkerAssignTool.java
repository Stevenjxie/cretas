package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 批次员工分配工具
 *
 * 将员工分配到指定生产批次，用于记录员工参与哪个批次的生产工作。
 * 支持一次分配多个员工到同一批次。
 *
 * 业务规则：
 * 1. 批次必须存在
 * 2. 员工必须是工厂内有效员工
 * 3. 同一员工不能重复分配到同一批次
 *
 * Intent Code: PROCESSING_WORKER_ASSIGN
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class ProcessingWorkerAssignTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "processing_worker_assign";
    }

    @Override
    public String getDescription() {
        return "将员工分配到生产批次。支持一次分配多个员工。" +
                "用于记录员工参与哪个批次的生产工作。" +
                "适用场景：开始生产时分配工人、调整人员安排。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "integer");
        batchId.put("description", "要分配员工的生产批次ID");
        properties.put("batchId", batchId);

        // workerIds: 员工ID列表（必需）
        Map<String, Object> workerIds = new HashMap<>();
        workerIds.put("type", "array");
        workerIds.put("description", "要分配的员工ID列表");
        Map<String, Object> workerIdItem = new HashMap<>();
        workerIdItem.put("type", "integer");
        workerIds.put("items", workerIdItem);
        workerIds.put("minItems", 1);
        properties.put("workerIds", workerIds);

        // notes: 备注（可选）
        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "分配备注，如工作职责说明");
        properties.put("notes", notes);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId", "workerIds"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId", "workerIds");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行批次员工分配 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 1. 提取必需参数
        Long batchId = getLong(params, "batchId");
        List<?> rawWorkerIds = getList(params, "workerIds");

        // 参数验证
        if (batchId == null) {
            throw new IllegalArgumentException("批次ID不能为空");
        }
        if (rawWorkerIds == null || rawWorkerIds.isEmpty()) {
            throw new IllegalArgumentException("员工ID列表不能为空");
        }

        // 转换员工ID列表为List<Long>
        List<Long> workerIds = rawWorkerIds.stream()
                .map(id -> {
                    if (id instanceof Number) {
                        return ((Number) id).longValue();
                    }
                    return Long.parseLong(id.toString());
                })
                .collect(Collectors.toList());

        // 2. 获取可选参数
        String notes = getString(params, "notes");

        // 3. 从context获取分配人ID
        Object contextUserId = context.get("userId");
        Long assignedBy = null;
        if (contextUserId != null) {
            if (contextUserId instanceof Number) {
                assignedBy = ((Number) contextUserId).longValue();
            } else {
                try {
                    assignedBy = Long.parseLong(contextUserId.toString());
                } catch (NumberFormatException e) {
                    log.warn("无法解析分配人ID: {}", contextUserId);
                }
            }
        }

        log.info("分配员工到批次: factoryId={}, batchId={}, workerIds={}, assignedBy={}, notes={}",
                factoryId, batchId, workerIds, assignedBy, notes);

        // 4. 调用服务执行分配
        List<Map<String, Object>> assignments = processingService.assignWorkersToBatch(
                factoryId, batchId, workerIds, assignedBy, notes);

        // 5. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("assignedWorkerCount", workerIds.size());
        result.put("assignments", assignments);
        if (notes != null) {
            result.put("notes", notes);
        }

        return buildSimpleResult(
                String.format("已成功将 %d 名员工分配到批次 %d", workerIds.size(), batchId),
                result
        );
    }

    /**
     * 覆盖参数问题提示
     */
    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "batchId", "请问要分配员工到哪个批次？请提供批次ID。",
                "workerIds", "请问要分配哪些员工？请提供员工ID列表。",
                "notes", "请问需要添加分配备注吗？（可选）"
        );
        String question = questions.get(paramName);
        return question != null ? question : super.getParameterQuestion(paramName);
    }

    /**
     * 覆盖参数显示名称
     */
    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "batchId", "批次ID",
                "workerIds", "员工ID列表",
                "notes", "分配备注"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /**
     * 此工具需要权限验证
     */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    /**
     * 允许的角色：工厂管理员、生产主管
     */
    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "production_supervisor".equals(userRole) ||
                "platform_admin".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
