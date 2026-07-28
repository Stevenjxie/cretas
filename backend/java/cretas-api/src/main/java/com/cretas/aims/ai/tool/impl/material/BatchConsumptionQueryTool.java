package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.BatchConsumptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BatchConsumptionQueryTool extends AbstractBusinessTool {

    @Autowired
    private BatchConsumptionService batchConsumptionService;
    @Autowired
    private ProductionBatchRepository productionBatchRepository;

    @Override
    public String getToolName() {
        return "batch_consumption_query";
    }

    @Override
    public String getDescription() {
        return "查询生产批次的物料消耗明细和BOM达成率。当用户问'这批用了多少料'、'物料消耗情况'、'BOM达成率'时调用";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "batchNumber", Map.of("type", "string", "description", "生产批次号"),
                        "batchId", Map.of("type", "integer", "description", "生产批次ID")
                ),
                "required", List.of()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of(); // 至少需要batchNumber或batchId之一
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        Long batchId = getLong(params, "batchId");
        if (batchId == null) {
            String batchNumber = getString(params, "batchNumber");
            if (batchNumber != null) {
                batchId = productionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                        .map(ProductionBatch::getId)
                        .orElseThrow(() -> new IllegalArgumentException("找不到批次: " + batchNumber));
            } else {
                throw new IllegalArgumentException("请提供批次号或批次ID");
            }
        }

        Map<String, Object> summary = batchConsumptionService.getConsumptionSummary(factoryId, batchId);
        return buildSimpleResult("查询成功", summary);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "batchNumber" -> "请问要查询哪个生产批次的物料消耗？";
            default -> super.getParameterQuestion(paramName);
        };
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
