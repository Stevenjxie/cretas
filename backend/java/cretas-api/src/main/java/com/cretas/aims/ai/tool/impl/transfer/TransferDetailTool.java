package com.cretas.aims.ai.tool.impl.transfer;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.service.inventory.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class TransferDetailTool extends AbstractBusinessTool {

    @Autowired
    private TransferService transferService;

    @Override
    public String getToolName() {
        return "transfer_detail";
    }

    @Override
    public String getDescription() {
        return "查询调拨单详情。根据调拨单ID获取完整信息，包括调出/调入仓库、物料明细、审批状态等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> transferId = new HashMap<>();
        transferId.put("type", "string");
        transferId.put("description", "调拨单ID");
        properties.put("transferId", transferId);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("transferId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("transferId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String transferId = getString(params, "transferId");
        InternalTransfer transfer = transferService.getTransferById(factoryId, transferId);

        Map<String, Object> result = new HashMap<>();
        result.put("transfer", transfer);
        result.put("message", "调拨单详情查询成功");
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
