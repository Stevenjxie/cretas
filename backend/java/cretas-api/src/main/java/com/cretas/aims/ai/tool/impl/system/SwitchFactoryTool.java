package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 切换工厂工具
 *
 * 引导用户切换到目标工厂。
 * Intent Code: SYSTEM_SWITCH_FACTORY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class SwitchFactoryTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "switch_factory";
    }

    @Override
    public String getDescription() {
        return "引导用户切换到目标工厂，切换后数据将自动刷新为对应工厂的数据。" +
                "适用场景：用户说'切换工厂'、'换一个工厂'等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("切换工厂引导 - 当前工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "请前往【我的】-> 【工厂切换】选择目标工厂。切换后数据将自动刷新为对应工厂的数据。");
        result.put("currentFactoryId", factoryId);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
