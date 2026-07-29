package com.cretas.aims.ai.tool.impl.sales;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.inventory.CreateDeliveryRequest;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.service.inventory.SalesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
public class SalesCreateDeliveryTool extends AbstractBusinessTool {

    @Autowired
    private SalesService salesService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String getToolName() {
        return "sales_create_delivery";
    }

    @Override
    public String getDescription() {
        return "创建销售出库/发货单。需要客户ID、产品信息和数量。可关联销售订单。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> salesOrderId = new HashMap<>();
        salesOrderId.put("type", "string");
        salesOrderId.put("description", "关联的销售订单ID（可选，支持无单出库）");
        properties.put("salesOrderId", salesOrderId);

        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户ID");
        properties.put("customerId", customerId);

        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description", "产品类型ID");
        properties.put("productTypeId", productTypeId);

        Map<String, Object> productName = new HashMap<>();
        productName.put("type", "string");
        productName.put("description", "产品名称");
        properties.put("productName", productName);

        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "发货数量");
        properties.put("quantity", quantity);

        Map<String, Object> unit = new HashMap<>();
        unit.put("type", "string");
        unit.put("description", "单位（箱/kg/件）");
        properties.put("unit", unit);

        Map<String, Object> deliveryDate = new HashMap<>();
        deliveryDate.put("type", "string");
        deliveryDate.put("description", "发货日期，格式: yyyy-MM-dd，默认今天");
        properties.put("deliveryDate", deliveryDate);

        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "备注");
        properties.put("remark", remark);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("customerId", "productTypeId", "quantity", "unit"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("customerId", "productTypeId", "quantity", "unit");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        CreateDeliveryRequest request = new CreateDeliveryRequest();
        request.setCustomerId(getString(params, "customerId"));
        request.setSalesOrderId(getString(params, "salesOrderId"));

        String dateStr = getString(params, "deliveryDate");
        request.setDeliveryDate(dateStr != null ? LocalDate.parse(dateStr, DATE_FORMATTER) : LocalDate.now());
        request.setRemark(getString(params, "remark"));

        CreateDeliveryRequest.DeliveryItemDTO item = new CreateDeliveryRequest.DeliveryItemDTO();
        item.setProductTypeId(getString(params, "productTypeId"));
        item.setProductName(getString(params, "productName"));
        item.setDeliveredQuantity(getBigDecimal(params, "quantity"));
        item.setUnit(getString(params, "unit"));
        request.setItems(Collections.singletonList(item));

        Long userId = context.get("userId") != null ? ((Number) context.get("userId")).longValue() : 0L;
        SalesDeliveryRecord record = salesService.createDeliveryRecord(factoryId, request, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "发货单创建成功");
        result.put("deliveryId", record.getId());
        result.put("customerId", record.getCustomerId());
        result.put("deliveryDate", record.getDeliveryDate());
        result.put("status", record.getStatus());
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = new HashMap<>();
        questions.put("customerId", "请问出库给哪个客户？请提供客户ID。");
        questions.put("productTypeId", "请问出库哪个产品？请提供产品ID。");
        questions.put("quantity", "请问出库数量是多少？");
        questions.put("unit", "请问单位是什么？（箱/kg/件）");
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> names = Map.of(
            "customerId", "客户ID",
            "productTypeId", "产品ID",
            "quantity", "数量",
            "unit", "单位",
            "salesOrderId", "销售订单ID",
            "deliveryDate", "发货日期"
        );
        return names.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
