package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.service.inventory.PurchaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class PurchaseOrderListTool extends AbstractBusinessTool {

    @Autowired
    private PurchaseService purchaseService;

    @Override
    public String getToolName() {
        return "purchase_order_list";
    }

    @Override
    public String getDescription() {
        return "查询采购订单列表。支持分页和按状态筛选。" +
                "适用场景：查看采购订单、采购单列表、待审核采购单。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "采购订单状态筛选");
        status.put("enum", Arrays.asList("DRAFT", "SUBMITTED", "APPROVED", "RECEIVING", "COMPLETED", "CANCELLED"));
        properties.put("status", status);

        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从1开始");
        page.put("default", 1);
        properties.put("page", page);

        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 10);
        properties.put("size", size);

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
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 10);
        String statusStr = getString(params, "status");

        PageResponse<PurchaseOrder> result;
        if (statusStr != null && !statusStr.trim().isEmpty()) {
            PurchaseOrderStatus status = PurchaseOrderStatus.valueOf(statusStr);
            result = purchaseService.getPurchaseOrdersByStatus(factoryId, status, page - 1, size);
        } else {
            result = purchaseService.getPurchaseOrders(factoryId, page - 1, size);
        }

        return buildPageResult(
                result.getContent() != null ? result.getContent() : Collections.emptyList(),
                result.getTotalElements() != null ? result.getTotalElements() : 0L,
                result.getTotalPages() != null ? result.getTotalPages() : 0,
                page
        );
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
