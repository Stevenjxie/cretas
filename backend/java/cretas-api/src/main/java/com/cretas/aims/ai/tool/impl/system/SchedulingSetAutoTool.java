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
 * 调度模式设置为自动工具
 *
 * 将工厂的调度模式设置为自动模式。自动模式下系统会根据
 * 订单、库存和产能自动生成生产计划和调度。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class SchedulingSetAutoTool extends AbstractBusinessTool {

    @Autowired
    private FactorySchedulingConfigService factorySchedulingConfigService;

    @Override
    public String getToolName() {
        return "scheduling_set_auto";
    }

    @Override
    public String getDescription() {
        return "将工厂的调度模式设置为自动模式。自动模式下系统会根据订单、库存和产能自动生成生产计划和调度。" +
                "适用场景：启用智能调度、自动化生产排程、提高调度效率。";
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
            log.info("设置调度模式为自动 - 工厂ID: {}", factoryId);
            FactorySchedulingConfig config = factorySchedulingConfigService.getOrCreateConfig(factoryId);
            config.setEnabled(true);
            config.setAdaptiveLearningEnabled(true);
            FactorySchedulingConfig saved = factorySchedulingConfigService.updateConfig(factoryId, config);

            Map<String, Object> result = new HashMap<>();
            result.put("factoryId", factoryId);
            result.put("schedulingMode", "FULLY_AUTO");
            result.put("modeName", "全自动");
            result.put("enabled", saved.getEnabled());
            result.put("adaptiveLearningEnabled", saved.getAdaptiveLearningEnabled());
            if (reason != null) result.put("reason", reason);
            return buildSimpleResult("调度模式已设置为全自动", result);
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
