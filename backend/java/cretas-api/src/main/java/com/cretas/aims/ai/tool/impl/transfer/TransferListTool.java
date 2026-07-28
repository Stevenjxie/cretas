package com.cretas.aims.ai.tool.impl.transfer;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.service.inventory.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class TransferListTool extends AbstractBusinessTool {

    @Autowired
    private TransferService transferService;

    @Override
    public String getToolName() {
        return "transfer_list";
    }

    @Override
    public String getDescription() {
        return "查询调拨单列表。支持分页查询，返回内部调拨单信息。" +
                "适用场景：查看调拨记录、调拨单列表、库存调拨查询。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

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

        PageResponse<InternalTransfer> result = transferService.getTransfers(factoryId, page - 1, size);

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
