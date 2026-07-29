package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 筛选/切换工具
 *
 * 处理用户请求筛选条件或切换视图的场景。
 * Intent Code: EXECUTE_SWITCH / CONDITION_SWITCH / EXCLUDE_SELECTED /
 *              FILTER_EXCLUDE_SELECTED / SYSTEM_FILTER_EXCLUDE_SELECTED / UI_EXCLUDE_SELECTED
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ExecuteSwitchTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "execute_switch";
    }

    @Override
    public String getDescription() {
        return "处理筛选条件切换或排除选项的请求，引导用户指定具体的筛选条件。" +
                "适用场景：用户说'只看进行中的'、'排除已完成的'、'切换到'等。";
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
        log.info("筛选/切换 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "请告诉我您想切换或筛选什么条件？例如：「只看进行中的批次」「排除已完成的订单」");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
