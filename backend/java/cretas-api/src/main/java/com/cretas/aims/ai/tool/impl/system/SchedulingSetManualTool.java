package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.FactorySchedulingConfig;
import com.cretas.aims.service.scheduling.FactorySchedulingConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 调度模式设置为手动工具
 *
 * 将工厂的调度模式设置为手动模式。手动模式下生产计划和调度
 * 完全由人工控制，系统仅提供辅助功能。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class SchedulingSetManualTool extends AbstractBusinessTool {

    @Autowired
    private FactorySchedulingConfigService factorySchedulingConfigService;

    @Override
    public String getToolName() {
        return "scheduling_set_manual";
    }

    @Override
    public String getDescription() {
        return "将工厂的调度模式设置为手动模式。手动模式下生产计划和调度完全由人工控制，系统仅提供辅助功能。" +
                "适用场景：需要人工精细控制生产、特殊订单处理、调试生产流程。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // reason: 设置原因（可选）
        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "设置原因或备注");
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    /**
     * 无必需参数，操作作用于工厂级别
     */
    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String reason = getString(params, "reason", null);
        try {
            log.info("设置调度模式为手动 - 工厂ID: {}", factoryId);
            FactorySchedulingConfig config = factorySchedulingConfigService.getOrCreateConfig(factoryId);
            // Manual mode: scheduling is enabled but adaptive learning is disabled (human controls)
            config.setEnabled(true);
            config.setAdaptiveLearningEnabled(false);
            FactorySchedulingConfig saved = factorySchedulingConfigService.updateConfig(factoryId, config);

            Map<String, Object> result = new HashMap<>();
            result.put("factoryId", factoryId);
            result.put("schedulingMode", "MANUAL_CONFIRM");
            result.put("modeName", "人工确认");
            result.put("enabled", saved.getEnabled());
            result.put("adaptiveLearningEnabled", saved.getAdaptiveLearningEnabled());
            if (reason != null) result.put("reason", reason);
            return buildSimpleResult("调度模式已设置为人工确认（手动）", result);
        } catch (Exception e) {
            log.error("设置调度模式失败 - 工厂ID: {}", factoryId, e);
            throw e;
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
