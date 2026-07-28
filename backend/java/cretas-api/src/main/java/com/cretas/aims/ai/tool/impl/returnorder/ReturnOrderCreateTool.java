package com.cretas.aims.ai.tool.impl.returnorder;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.service.inventory.ReturnOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 退货单创建工具
 *
 * 创建退货单（草稿状态），支持采购退货和销售退货。
 * Intent Code: RETURN_ORDER_CREATE
 */
@Slf4j
@Component
public class ReturnOrderCreateTool extends AbstractBusinessTool {

    @Autowired
    private ReturnOrderService returnOrderService;

    @Override
    public String getToolName() {
        return "return_order_create";
    }

    @Override
    public String getDescription() {
        return "创建退货单。支持采购退货（退给供应商）和销售退货（客户退货）。" +
                "需要提供退货类型、交易对手和退货物品。" +
                "适用场景：退货给供应商、处理客户退货、创建退货申请。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> returnType = new HashMap<>();
        returnType.put("type", "string");
        returnType.put("description", "退货类型");
        returnType.put("enum", Arrays.asList("PURCHASE_RETURN", "SALES_RETURN"));
        properties.put("returnType", returnType);

        Map<String, Object> counterpartyId = new HashMap<>();
        counterpartyId.put("type", "string");
        counterpartyId.put("description", "交易对手ID（供应商ID或客户ID）");
        properties.put("counterpartyId", counterpartyId);

        Map<String, Object> sourceOrderId = new HashMap<>();
        sourceOrderId.put("type", "string");
        sourceOrderId.put("description", "原始订单ID（可选）");
        properties.put("sourceOrderId", sourceOrderId);

        Map<String, Object> itemName = new HashMap<>();
        itemName.put("type", "string");
        itemName.put("description", "退货物品名称");
        properties.put("itemName", itemName);

        Map<String, Object> materialTypeId = new HashMap<>();
        materialTypeId.put("type", "string");
        materialTypeId.put("description", "原料类型ID（采购退货时提供）");
        properties.put("materialTypeId", materialTypeId);

        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description", "产品类型ID（销售退货时提供）");
        properties.put("productTypeId", productTypeId);

        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "退货数量");
        properties.put("quantity", quantity);

        Map<String, Object> unitPrice = new HashMap<>();
        unitPrice.put("type", "number");
        unitPrice.put("description", "单价");
        properties.put("unitPrice", unitPrice);

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号");
        properties.put("batchNumber", batchNumber);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "退货原因");
        properties.put("reason", reason);

        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "备注");
        properties.put("remark", remark);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("returnType", "counterpartyId", "quantity"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("returnType", "counterpartyId", "quantity");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "returnType" -> "请选择退货类型：采购退货(PURCHASE_RETURN) 还是 销售退货(SALES_RETURN)？";
            case "counterpartyId" -> "请提供交易对手ID（采购退货提供供应商ID，销售退货提供客户ID）。";
            case "quantity" -> "请提供退货数量。";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("创建退货单 - 工厂ID: {}, 参数: {}", factoryId, params);

        String returnType = getString(params, "returnType");
        String counterpartyId = getString(params, "counterpartyId");
        String sourceOrderId = getString(params, "sourceOrderId");
        String itemName = getString(params, "itemName");
        String materialTypeId = getString(params, "materialTypeId");
        String productTypeId = getString(params, "productTypeId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        BigDecimal unitPrice = getBigDecimal(params, "unitPrice");
        String batchNumber = getString(params, "batchNumber");
        String reason = getString(params, "reason");
        String remark = getString(params, "remark");
        Long userId = getUserId(context);

        // 构建行项目
        CreateReturnOrderRequest.ReturnOrderItemDTO item = new CreateReturnOrderRequest.ReturnOrderItemDTO();
        item.setItemName(itemName);
        item.setQuantity(quantity);
        if (materialTypeId != null) item.setMaterialTypeId(materialTypeId);
        if (productTypeId != null) item.setProductTypeId(productTypeId);
        if (unitPrice != null) item.setUnitPrice(unitPrice);
        if (batchNumber != null) item.setBatchNumber(batchNumber);
        if (reason != null) item.setReason(reason);

        // 构建请求
        CreateReturnOrderRequest request = new CreateReturnOrderRequest();
        request.setReturnType(returnType);
        request.setCounterpartyId(counterpartyId);
        request.setReturnDate(LocalDate.now());
        request.setItems(List.of(item));
        if (sourceOrderId != null) request.setSourceOrderId(sourceOrderId);
        if (reason != null) request.setReason(reason);
        if (remark != null) request.setRemark(remark);

        ReturnOrder created = returnOrderService.createReturnOrder(factoryId, request, userId);

        String returnTypeName = "PURCHASE_RETURN".equals(returnType) ? "采购退货" : "销售退货";

        Map<String, Object> result = new HashMap<>();
        result.put("returnOrderId", created.getId());
        result.put("returnNumber", created.getReturnNumber());
        result.put("returnType", returnTypeName);
        result.put("status", created.getStatus().name());
        result.put("totalAmount", created.getTotalAmount());
        result.put("message", String.format("退货单创建成功！编号: %s，类型: %s，当前状态: 草稿。请提交后等待审批。",
                created.getReturnNumber(), returnTypeName));

        log.info("退货单创建完成 - returnOrderId={}, returnNumber={}", created.getId(), created.getReturnNumber());
        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
