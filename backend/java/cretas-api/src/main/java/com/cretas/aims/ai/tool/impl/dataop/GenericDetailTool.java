package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 通用详情查询工具
 *
 * 查询指定实体的详细信息。
 * Intent Code: QUERY_GENERIC_DETAIL
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class GenericDetailTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "generic_detail";
    }

    @Override
    public String getDescription() {
        return "查询指定实体的详细信息。支持订单、批次、设备等各类实体。" +
                "适用场景：查看详情、查看具体信息。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> entityType = new HashMap<>();
        entityType.put("type", "string");
        entityType.put("description", "实体类型，如ORDER、BATCH、EQUIPMENT");
        properties.put("entityType", entityType);

        Map<String, Object> entityId = new HashMap<>();
        entityId.put("type", "string");
        entityId.put("description", "实体ID或编号");
        properties.put("entityId", entityId);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("通用详情查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String entityType = getString(params, "entityType");
        String entityId = getString(params, "entityId");

        Map<String, Object> result = new HashMap<>();

        if (entityType == null && entityId == null) {
            result.put("message", "请指定要查看详情的对象类型和编号。\n例如：\n" +
                    "- 查看订单 ORD-001 的详情\n" +
                    "- 查看批次 MB-2024-001 的详情\n" +
                    "- 查看设备 EQ-003 的详情");
            result.put("status", "NEED_MORE_INFO");
        } else {
            String msg = "已接收详情查询请求";
            if (entityType != null) msg += "（类型: " + entityType + "）";
            if (entityId != null) msg += "（编号: " + entityId + "）";
            msg += "\n请在对应的管理页面查看完整详情。";
            result.put("message", msg);
            if (entityType != null) result.put("entityType", entityType);
            if (entityId != null) result.put("entityId", entityId);
        }

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
