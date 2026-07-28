package com.cretas.aims.ai.tool.impl.transfer;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.inventory.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class TransferStatsTool extends AbstractBusinessTool {

    @Autowired
    private TransferService transferService;

    @Override
    public String getToolName() {
        return "transfer_statistics";
    }

    @Override
    public String getDescription() {
        return "查询调拨统计数据。返回调拨单总数、各状态数量等统计信息。" +
                "适用场景：调拨概况、调拨分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        Map<String, Object> stats = transferService.getTransferStatistics(factoryId);
        Map<String, Object> result = new HashMap<>(stats);
        result.put("message", "调拨统计查询成功");
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
