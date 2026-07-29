package com.cretas.aims.ai.tool.impl.transfer;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.service.inventory.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 调拨单创建工具
 *
 * 创建内部调拨申请（草稿状态），需提供调入方和物品明细。
 * Intent Code: TRANSFER_CREATE
 */
@Slf4j
@Component
public class TransferCreateTool extends AbstractBusinessTool {

    @Autowired
    private TransferService transferService;

    @Override
    public String getToolName() {
        return "transfer_create";
    }

    @Override
    public String getDescription() {
        return "创建内部调拨单。需要提供调入方、物品名称和数量。" +
                "适用场景：申请调拨、内部转移、从总部调货、分部之间调货。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> targetFactoryId = new HashMap<>();
        targetFactoryId.put("type", "string");
        targetFactoryId.put("description", "调入方工厂/仓库ID");
        properties.put("targetFactoryId", targetFactoryId);

        Map<String, Object> transferType = new HashMap<>();
        transferType.put("type", "string");
        transferType.put("description", "调拨类型");
        transferType.put("enum", Arrays.asList("HQ_TO_BRANCH", "BRANCH_TO_BRANCH", "BRANCH_TO_HQ"));
        properties.put("transferType", transferType);

        Map<String, Object> itemType = new HashMap<>();
        itemType.put("type", "string");
        itemType.put("description", "物品类型");
        itemType.put("enum", Arrays.asList("RAW_MATERIAL", "FINISHED_GOODS"));
        properties.put("itemType", itemType);

        Map<String, Object> itemId = new HashMap<>();
        itemId.put("type", "string");
        itemId.put("description", "物品ID（原料类型ID或产品类型ID）");
        properties.put("itemId", itemId);

        Map<String, Object> itemName = new HashMap<>();
        itemName.put("type", "string");
        itemName.put("description", "物品名称");
        properties.put("itemName", itemName);

        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "调拨数量");
        properties.put("quantity", quantity);

        Map<String, Object> unit = new HashMap<>();
        unit.put("type", "string");
        unit.put("description", "计量单位");
        properties.put("unit", unit);

        Map<String, Object> expectedArrivalDate = new HashMap<>();
        expectedArrivalDate.put("type", "string");
        expectedArrivalDate.put("description", "预计到达日期，格式YYYY-MM-DD");
        properties.put("expectedArrivalDate", expectedArrivalDate);

        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "备注");
        properties.put("remark", remark);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("targetFactoryId", "transferType", "itemId", "quantity", "unit"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("targetFactoryId", "transferType", "itemId", "quantity", "unit");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "targetFactoryId" -> "请提供调入方工厂/仓库ID。";
            case "transferType" -> "请选择调拨类型：总部→分部(HQ_TO_BRANCH)、分部↔分部(BRANCH_TO_BRANCH)、分部→总部(BRANCH_TO_HQ)。";
            case "itemId" -> "请提供要调拨的物品ID。";
            case "quantity" -> "请提供调拨数量。";
            case "unit" -> "请提供计量单位。";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("创建调拨单 - 工厂ID: {}, 参数: {}", factoryId, params);

        String targetFactoryId = getString(params, "targetFactoryId");
        String transferType = getString(params, "transferType");
        String itemType = getString(params, "itemType");
        String itemId = getString(params, "itemId");
        String itemName = getString(params, "itemName");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String unit = getString(params, "unit");
        String expectedArrivalDateStr = getString(params, "expectedArrivalDate");
        String remark = getString(params, "remark");
        Long userId = getUserId(context);

        // 默认物品类型
        if (itemType == null) itemType = "RAW_MATERIAL";

        // 构建行项目
        CreateTransferRequest.TransferItemDTO item = new CreateTransferRequest.TransferItemDTO();
        item.setItemType(itemType);
        if ("RAW_MATERIAL".equals(itemType)) {
            item.setMaterialTypeId(itemId);
        } else {
            item.setProductTypeId(itemId);
        }
        item.setItemName(itemName);
        item.setQuantity(quantity);
        item.setUnit(unit);

        // 构建请求
        CreateTransferRequest request = new CreateTransferRequest();
        request.setTransferType(transferType);
        request.setTargetFactoryId(targetFactoryId);
        request.setTransferDate(LocalDate.now());
        request.setItems(List.of(item));
        if (remark != null) request.setRemark(remark);

        if (expectedArrivalDateStr != null) {
            try {
                request.setExpectedArrivalDate(LocalDate.parse(expectedArrivalDateStr));
            } catch (DateTimeParseException ignored) {}
        }

        InternalTransfer created = transferService.createTransfer(factoryId, request, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("transferId", created.getId());
        result.put("transferNumber", created.getTransferNumber());
        result.put("status", created.getStatus().name());
        result.put("transferType", created.getTransferType().name());
        result.put("message", String.format("调拨单创建成功！编号: %s，类型: %s，当前状态: 草稿。请提交审批。",
                created.getTransferNumber(), created.getTransferType().name()));

        log.info("调拨单创建完成 - transferId={}, transferNumber={}", created.getId(), created.getTransferNumber());
        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
