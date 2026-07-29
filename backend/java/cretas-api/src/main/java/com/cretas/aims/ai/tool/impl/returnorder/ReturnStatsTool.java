package com.cretas.aims.ai.tool.impl.returnorder;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.inventory.ReturnOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class ReturnStatsTool extends AbstractBusinessTool {

    @Autowired
    private ReturnOrderService returnOrderService;

    @Override
    public String getToolName() {
        return "return_order_statistics";
    }

    @Override
    public String getDescription() {
        return "查询退货统计数据。返回退货单总数、各状态/类型数量等统计信息。" +
                "适用场景：退货概况、退货分析、退货率。";
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
        Map<String, Object> stats = returnOrderService.getReturnOrderStatistics(factoryId);
        Map<String, Object> result = new HashMap<>(stats);
        result.put("message", "退货统计查询成功");
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
