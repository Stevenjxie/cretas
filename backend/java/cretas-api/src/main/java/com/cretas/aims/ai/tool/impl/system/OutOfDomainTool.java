package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 域外输入处理工具
 *
 * 处理不在系统服务范围内的输入，引导用户回到正确的业务场景。
 * Intent Code: OUT_OF_DOMAIN
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class OutOfDomainTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "out_of_domain";
    }

    @Override
    public String getDescription() {
        return "处理不在系统服务范围内的用户输入，引导用户回到正确的业务场景。" +
                "当用户的请求与食品溯源系统无关时使用。";
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
        log.info("域外输入 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "我是白垩纪AI Agent助手，可以帮您查询库存、生产、质检、设备、发货等业务数据。请告诉我您需要什么帮助？");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
