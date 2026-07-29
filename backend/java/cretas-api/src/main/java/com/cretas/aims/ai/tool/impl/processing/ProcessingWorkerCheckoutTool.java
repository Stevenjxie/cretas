package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 员工批次签出工具
 *
 * 记录员工完成批次工作并签出，用于统计员工工时和工作记录。
 * 支持记录实际工作时间和备注。
 *
 * 业务规则：
 * 1. 员工必须已分配到该批次
 * 2. 工作分钟数必须为正数（如果提供）
 * 3. 签出后员工状态变更为"已完成"
 *
 * Intent Code: PROCESSING_WORKER_CHECKOUT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class ProcessingWorkerCheckoutTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "processing_worker_checkout";
    }

    @Override
    public String getDescription() {
        return "记录员工完成批次工作并签出。用于统计员工工时和工作记录。" +
                "可选记录实际工作时间。" +
                "适用场景：员工完成工作签出、记录工时。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "integer");
        batchId.put("description", "员工所在的生产批次ID");
        properties.put("batchId", batchId);

        // workerId: 员工ID（必需）
        Map<String, Object> workerId = new HashMap<>();
        workerId.put("type", "integer");
        workerId.put("description", "要签出的员工ID");
        properties.put("workerId", workerId);

        // workMinutes: 工作分钟数（可选）
        Map<String, Object> workMinutes = new HashMap<>();
        workMinutes.put("type", "integer");
        workMinutes.put("description", "实际工作分钟数，用于工时统计。不填则自动计算");
        workMinutes.put("minimum", 1);
        properties.put("workMinutes", workMinutes);

        // notes: 备注（可选）
        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "签出备注，如工作内容说明");
        properties.put("notes", notes);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId", "workerId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId", "workerId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行员工批次签出 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 1. 提取必需参数
        Long batchId = getLong(params, "batchId");
        Long workerId = getLong(params, "workerId");

        // 参数验证
        if (batchId == null) {
            throw new IllegalArgumentException("批次ID不能为空");
        }
        if (workerId == null) {
            throw new IllegalArgumentException("员工ID不能为空");
        }

        // 2. 获取可选参数
        Integer workMinutes = getInteger(params, "workMinutes");
        String notes = getString(params, "notes");

        // 验证工作分钟数
        if (workMinutes != null && workMinutes <= 0) {
            throw new IllegalArgumentException("工作分钟数必须大于0");
        }

        log.info("员工签出批次: factoryId={}, batchId={}, workerId={}, workMinutes={}, notes={}",
                factoryId, batchId, workerId, workMinutes, notes);

        // 3. 调用服务执行签出
        Map<String, Object> checkoutResult = processingService.workerCheckout(
                factoryId, batchId, workerId, workMinutes, notes);

        // 4. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("workerId", workerId);
        result.put("checkoutResult", checkoutResult);
        if (workMinutes != null) {
            result.put("workMinutes", workMinutes);
        }
        if (notes != null) {
            result.put("notes", notes);
        }

        // 生成消息
        String message;
        if (workMinutes != null) {
            int hours = workMinutes / 60;
            int mins = workMinutes % 60;
            if (hours > 0) {
                message = String.format("员工 %d 已从批次 %d 签出，工作时长: %d小时%d分钟",
                        workerId, batchId, hours, mins);
            } else {
                message = String.format("员工 %d 已从批次 %d 签出，工作时长: %d分钟",
                        workerId, batchId, mins);
            }
        } else {
            message = String.format("员工 %d 已从批次 %d 签出", workerId, batchId);
        }

        return buildSimpleResult(message, result);
    }

    /**
     * 覆盖参数问题提示
     */
    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "batchId", "请问员工从哪个批次签出？请提供批次ID。",
                "workerId", "请问是哪位员工要签出？请提供员工ID。",
                "workMinutes", "请问员工实际工作了多少分钟？（可选，不填则自动计算）",
                "notes", "请问需要添加签出备注吗？（可选）"
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
                "workerId", "员工ID",
                "workMinutes", "工作分钟数",
                "notes", "签出备注"
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
