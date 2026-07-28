package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 域外操作处理工具
 *
 * 处理导航、购物车、媒体播放等超出系统范围的操作请求。
 * Intent Code: NAVIGATE_TO_CITY / NAVIGATE_TO_LOCATION / SHOPPING_CART_CLEAR / MEDIA_PLAY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class NavigateOutOfDomainTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "navigate_out_of_domain";
    }

    @Override
    public String getDescription() {
        return "处理导航、购物车、媒体播放等超出食品溯源系统服务范围的操作请求。" +
                "适用场景：用户请求地图导航、清空购物车、播放媒体等。";
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
        log.info("域外操作请求 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "抱歉，该功能不在系统服务范围内。我是白垩纪AI Agent助手，可以帮您查询库存、生产、质检、设备、发货等业务数据。");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
