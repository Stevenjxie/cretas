package com.cretas.aims.ai.tool.impl.sales;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.sales.SalesNeedCreateRequest;
import com.cretas.aims.entity.enums.SalesNeedPriority;
import com.cretas.aims.entity.sales.SalesNeed;
import com.cretas.aims.service.sales.SalesNeedService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 4 W2 S-NEED-1 — 创建客户需求 Tool.
 */
@Slf4j
@Component
public class SalesNeedCreateTool extends AbstractBusinessTool {

    @Autowired
    private SalesNeedService salesNeedService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override
    public String getToolName() {
        return "sales_need_create";
    }

    @Override
    public String getDescription() {
        return "创建客户需求 (DRAFT 状态). 客户提的购买意向, 销售员后续可确认并转销售订单. "
                + "需要: 客户ID, 产品ID, 数量, 单位. 可选: 期望交货日, 优先级, 备注.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("customerId", Map.of("type", "string", "description", "客户ID"));
        properties.put("customerName", Map.of("type", "string", "description", "客户名称 (可选, 冗余显示)"));
        properties.put("productId", Map.of("type", "string", "description", "产品ID (ProductType.id)"));
        properties.put("productName", Map.of("type", "string", "description", "产品名称 (可选, 冗余显示)"));
        properties.put("qtyDemand", Map.of("type", "number", "description", "需求数量, 正数"));
        properties.put("unit", Map.of("type", "string", "description", "单位 (如 kg / 箱 / 件)"));
        properties.put("expectedDeliveryDate",
                Map.of("type", "string", "description", "期望交货日, YYYY-MM-DD"));
        properties.put("priority",
                Map.of("type", "string", "description", "优先级 LOW/NORMAL/HIGH/URGENT, 默认 NORMAL"));
        properties.put("remark", Map.of("type", "string", "description", "备注"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("customerId", "productId", "qtyDemand", "unit"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("customerId", "productId", "qtyDemand", "unit");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                              Map<String, Object> context) throws Exception {
        SalesNeedCreateRequest req = new SalesNeedCreateRequest();
        req.setCustomerId(getString(params, "customerId"));
        req.setCustomerName(getString(params, "customerName"));
        req.setProductId(getString(params, "productId"));
        req.setProductName(getString(params, "productName"));
        req.setQtyDemand(getBigDecimal(params, "qtyDemand"));
        req.setUnit(getString(params, "unit"));
        req.setRemark(getString(params, "remark"));

        String dateStr = getString(params, "expectedDeliveryDate");
        if (dateStr != null && !dateStr.isBlank()) {
            req.setExpectedDeliveryDate(LocalDate.parse(dateStr, DATE_FORMATTER));
        }

        String priorityStr = getString(params, "priority");
        if (priorityStr != null && !priorityStr.isBlank()) {
            try {
                req.setPriority(SalesNeedPriority.valueOf(priorityStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid priority {}, fallback NORMAL", priorityStr);
            }
        }

        Long userId = context.get("userId") != null
                ? ((Number) context.get("userId")).longValue() : 0L;

        SalesNeed created = salesNeedService.create(factoryId, req, userId);

        // 防呆 R2: result message 必带身份上下文 (客户名/产品名/数量+单位).
        String custLabel = created.getCustomerName() != null && !created.getCustomerName().isBlank()
                ? created.getCustomerName() : created.getCustomerId();
        String prodLabel = created.getProductName() != null && !created.getProductName().isBlank()
                ? created.getProductName() : created.getProductId();

        Map<String, Object> result = new HashMap<>();
        result.put("message", String.format("已创建客户需求: %s — %s %s %s (状态: DRAFT, 待销售员确认)",
                custLabel, prodLabel, created.getQtyDemand(), created.getUnit()));
        result.put("needId", created.getId());
        result.put("status", created.getStatus().name());
        result.put("customerId", created.getCustomerId());
        result.put("customerName", created.getCustomerName());
        result.put("productId", created.getProductId());
        result.put("productName", created.getProductName());
        result.put("qtyDemand", created.getQtyDemand());
        result.put("unit", created.getUnit());
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "customerId" -> "请问需求来自哪个客户? 请提供客户 ID 或名称.";
            case "productId" -> "请问客户要什么产品? 请提供产品 ID 或名称.";
            case "qtyDemand" -> "请问需求数量是多少?";
            case "unit" -> "请问数量的单位是什么? (如 kg / 箱 / 件)";
            default -> null;
        };
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        return Map.of(
                "customerId", "客户ID",
                "productId", "产品ID",
                "qtyDemand", "需求数量",
                "unit", "单位",
                "expectedDeliveryDate", "期望交货日",
                "priority", "优先级",
                "remark", "备注"
        ).getOrDefault(paramName, paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
