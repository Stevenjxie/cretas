package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.service.inventory.PurchaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 采购订单创建工具
 *
 * 通过AI对话创建采购订单（草稿状态），需提供供应商和采购物品明细。
 * Intent Code: PURCHASE_ORDER_CREATE
 */
@Slf4j
@Component
public class PurchaseOrderCreateTool extends AbstractBusinessTool {

    @Autowired
    private PurchaseService purchaseService;

    @Override
    public String getToolName() {
        return "purchase_order_create";
    }

    @Override
    public String getDescription() {
        return "创建采购订单。需要提供供应商ID和采购物品（原料名称、数量、单位）。" +
                "创建后为草稿状态，需要单独提交和审批。" +
                "适用场景：新建采购单、下采购订单、采购原料。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> supplierId = new HashMap<>();
        supplierId.put("type", "string");
        supplierId.put("description", "供应商ID或名称");
        properties.put("supplierId", supplierId);

        Map<String, Object> materialName = new HashMap<>();
        materialName.put("type", "string");
        materialName.put("description", "采购原料名称");
        properties.put("materialName", materialName);

        Map<String, Object> materialTypeId = new HashMap<>();
        materialTypeId.put("type", "string");
        materialTypeId.put("description", "原料类型ID");
        properties.put("materialTypeId", materialTypeId);

        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "采购数量");
        properties.put("quantity", quantity);

        Map<String, Object> unit = new HashMap<>();
        unit.put("type", "string");
        unit.put("description", "计量单位，如kg、箱、袋");
        properties.put("unit", unit);

        Map<String, Object> unitPrice = new HashMap<>();
        unitPrice.put("type", "number");
        unitPrice.put("description", "单价");
        properties.put("unitPrice", unitPrice);

        Map<String, Object> expectedDeliveryDate = new HashMap<>();
        expectedDeliveryDate.put("type", "string");
        expectedDeliveryDate.put("description", "期望交货日期，格式YYYY-MM-DD");
        properties.put("expectedDeliveryDate", expectedDeliveryDate);

        Map<String, Object> purchaseType = new HashMap<>();
        purchaseType.put("type", "string");
        purchaseType.put("description", "采购类型");
        purchaseType.put("enum", Arrays.asList("DIRECT", "HQ_UNIFIED", "URGENT"));
        properties.put("purchaseType", purchaseType);

        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "备注");
        properties.put("remark", remark);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("supplierId", "materialTypeId", "quantity", "unit"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("supplierId", "materialTypeId", "quantity", "unit");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "supplierId" -> "请提供供应商ID或名称。";
            case "materialTypeId" -> "请提供要采购的原料类型ID。";
            case "quantity" -> "请提供采购数量。";
            case "unit" -> "请提供计量单位（如kg、箱、袋）。";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("创建采购订单 - 工厂ID: {}, 参数: {}", factoryId, params);

        String supplierId = getString(params, "supplierId");
        String materialTypeId = getString(params, "materialTypeId");
        String materialName = getString(params, "materialName");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String unit = getString(params, "unit");
        BigDecimal unitPrice = getBigDecimal(params, "unitPrice");
        String expectedDeliveryDateStr = getString(params, "expectedDeliveryDate");
        String purchaseType = getString(params, "purchaseType");
        String remark = getString(params, "remark");
        Long userId = getUserId(context);

        // 构建行项目
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId(materialTypeId);
        item.setMaterialName(materialName);
        item.setQuantity(quantity);
        item.setUnit(unit);
        if (unitPrice != null) item.setUnitPrice(unitPrice);

        // 构建请求
        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setSupplierId(supplierId);
        request.setOrderDate(LocalDate.now());
        request.setPurchaseType(purchaseType != null ? purchaseType : "DIRECT");
        request.setItems(List.of(item));
        if (remark != null) request.setRemark(remark);

        if (expectedDeliveryDateStr != null) {
            try {
                request.setExpectedDeliveryDate(LocalDate.parse(expectedDeliveryDateStr));
            } catch (DateTimeParseException ignored) {}
        }

        PurchaseOrder created = purchaseService.createPurchaseOrder(factoryId, request, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", created.getId());
        result.put("orderNumber", created.getOrderNumber());
        result.put("status", created.getStatus().name());
        result.put("supplierId", created.getSupplierId());
        result.put("totalAmount", created.getTotalAmount());
        result.put("message", String.format("采购订单创建成功！订单编号: %s，当前状态: 草稿。请提交后等待审批。",
                created.getOrderNumber()));

        log.info("采购订单创建完成 - orderId={}, orderNumber={}", created.getId(), created.getOrderNumber());
        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
