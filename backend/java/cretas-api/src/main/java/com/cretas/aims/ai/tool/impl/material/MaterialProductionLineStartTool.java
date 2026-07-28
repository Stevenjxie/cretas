package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 产线启动工具
 *
 * 启动指定的生产产线。当前为工作存根，记录操作并返回描述性结果。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class MaterialProductionLineStartTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "material_production_line_start";
    }

    @Override
    public String getDescription() {
        return "启动指定的生产产线。" +
                "需要提供产线编号，系统将发出产线启动指令。" +
                "适用场景：开始新的生产任务、启动产线运转。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> lineId = new HashMap<>();
        lineId.put("type", "string");
        lineId.put("description", "产线编号/产线ID");
        properties.put("lineId", lineId);

        Map<String, Object> productName = new HashMap<>();
        productName.put("type", "string");
        productName.put("description", "生产产品名称（可选）");
        properties.put("productName", productName);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("lineId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("lineId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String lineId = getString(params, "lineId");
        String productName = getString(params, "productName");

        log.info("产线启动请求: factoryId={}, lineId={}, productName={}", factoryId, lineId, productName);

        // 当前为工作存根 - 产线启动功能待集成设备管理模块
        Map<String, Object> result = new HashMap<>();
        result.put("lineId", lineId);
        result.put("factoryId", factoryId);
        result.put("operation", "PRODUCTION_LINE_START");
        result.put("status", "PENDING");

        StringBuilder msg = new StringBuilder();
        msg.append("产线 ").append(lineId).append(" 启动指令已记录");
        if (productName != null) {
            msg.append("，生产产品: ").append(productName);
            result.put("productName", productName);
        }
        msg.append("。请通过设备管理系统确认产线状态。");

        result.put("message", msg.toString());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("lineId".equals(paramName)) {
            return "请问要启动哪条产线？请提供产线编号。";
        }
        if ("productName".equals(paramName)) {
            return "请问要生产什么产品？（可选）";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("lineId".equals(paramName)) {
            return "产线编号";
        }
        if ("productName".equals(paramName)) {
            return "产品名称";
        }
        return super.getParameterDisplayName(paramName);
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "workshop_supervisor".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
