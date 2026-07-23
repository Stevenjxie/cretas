package com.cretas.aims.ai.tool.impl.returnorder;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.service.inventory.ReturnOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class ReturnOrderListTool extends AbstractBusinessTool {

    @Autowired
    private ReturnOrderService returnOrderService;

    @Override
    public String getToolName() {
        return "return_order_list";
    }

    @Override
    public String getDescription() {
        return "查询退货单列表。支持按退货类型和状态筛选。" +
                "适用场景：查看退货记录、退货单列表、采购退货、销售退货查询。";
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

        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "退货单状态");
        status.put("enum", Arrays.asList("DRAFT", "SUBMITTED", "APPROVED", "PROCESSING", "COMPLETED", "REJECTED", "CANCELLED"));
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
        String returnTypeStr = getString(params, "returnType");
        String statusStr = getString(params, "status");

        ReturnType returnType = returnTypeStr != null ? ReturnType.valueOf(returnTypeStr) : null;
        ReturnOrderStatus status = statusStr != null ? ReturnOrderStatus.valueOf(statusStr) : null;

        PageResponse<ReturnOrder> result = returnOrderService.getReturnOrders(factoryId, returnType, status, page - 1, size);

        long total = result.getTotalElements() != null ? result.getTotalElements() : 0L;
        if (total == 0) {
            // 诚实空态 (2026-07-24 工厂读电池): 无 message 会被兜底成
            // "本次查询没有获得可展示的结果" 通用文案, 用户不知道是没数据还是查坏了。
            return buildSimpleResult("当前没有退货单记录。如需创建退货, 可以说「创建退货单」。",
                    Map.of("content", Collections.emptyList(), "totalElements", 0));
        }
        return buildPageResult(
                result.getContent() != null ? result.getContent() : Collections.emptyList(),
                total,
                result.getTotalPages() != null ? result.getTotalPages() : 0,
                page
        );
    }
}
