package com.cretas.aims.ai.tool.impl.sales;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.service.sales.SalesNeedService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 4 W2 S-NEED-1 — 客户需求转销售订单 Tool.
 * 状态机要求 needId 必须为 CONFIRMED, 否则报错.
 */
@Slf4j
@Component
public class SalesNeedConvertTool extends AbstractBusinessTool {

    @Autowired
    private SalesNeedService salesNeedService;

    @Override
    public String getToolName() {
        return "sales_need_convert";
    }

    @Override
    public String getDescription() {
        return "将客户需求 (CONFIRMED 状态) 一键转为销售订单. 自动建 SalesOrder + 关联 needId.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("needId", Map.of("type", "string", "description", "客户需求 ID"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("needId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("needId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                              Map<String, Object> context) throws Exception {
        String needId = getString(params, "needId");
        Long userId = context.get("userId") != null
                ? ((Number) context.get("userId")).longValue() : 0L;

        SalesOrder created = salesNeedService.convertToSalesOrder(factoryId, needId, userId);

        // 防呆 R2: result message 必带身份上下文 (订单号 + 客户名).
        String custLabel = created.getCustomerName() != null && !created.getCustomerName().isBlank()
                ? created.getCustomerName() : created.getCustomerId();

        Map<String, Object> result = new HashMap<>();
        result.put("message", String.format("已转为销售订单 %s (客户: %s, 状态: %s)",
                created.getOrderNumber(), custLabel, created.getStatus()));
        result.put("needId", needId);
        result.put("salesOrderId", created.getId());
        result.put("orderNumber", created.getOrderNumber());
        result.put("customerName", created.getCustomerName());
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("needId".equals(paramName)) {
            return "请提供要转换的客户需求 ID.";
        }
        return null;
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        return "needId".equals(paramName) ? "客户需求ID" : paramName;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
