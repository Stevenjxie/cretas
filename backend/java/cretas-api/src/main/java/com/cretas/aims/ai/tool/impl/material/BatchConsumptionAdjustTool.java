package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.BatchConsumptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BatchConsumptionAdjustTool extends AbstractBusinessTool {

    @Autowired
    private BatchConsumptionService batchConsumptionService;
    @Autowired
    private ProductionBatchRepository productionBatchRepository;
    @Autowired
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @Override
    public String getToolName() {
        return "batch_consumption_adjust";
    }

    @Override
    public String getDescription() {
        return "调整生产批次的实际物料消耗量，支持差异报告。当工人说'这批多用了XX'或'实际用量是XX'时调用此工具";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "batchNumber", Map.of("type", "string", "description", "生产批次号，如PB-20260318-001"),
                        "batchId", Map.of("type", "integer", "description", "生产批次ID"),
                        "materialName", Map.of("type", "string", "description", "物料名称，如辣椒、盐"),
                        "actualQuantity", Map.of("type", "number", "description", "实际使用量(kg)"),
                        "reason", Map.of("type", "string", "description", "调整原因")
                ),
                "required", List.of("actualQuantity")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("actualQuantity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        BigDecimal actualQuantity = getBigDecimal(params, "actualQuantity");
        String reason = getString(params, "reason");
        if (reason == null) reason = "AI语音调整";

        // 解析批次
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

        // 解析物料类型
        String materialName = getString(params, "materialName");
        if (materialName == null) {
            throw new IllegalArgumentException("请告诉我要调整哪种物料");
        }

        List<RawMaterialType> types = rawMaterialTypeRepository.findByFactoryIdAndIsActive(factoryId, true);
        String materialTypeId = types.stream()
                .filter(t -> t.getName().contains(materialName) || materialName.contains(t.getName()))
                .findFirst()
                .map(RawMaterialType::getId)
                .orElseThrow(() -> new IllegalArgumentException("找不到物料: " + materialName));

        batchConsumptionService.adjustConsumption(factoryId, batchId, materialTypeId, actualQuantity, reason);

        return buildSimpleResult("调整成功", Map.of(
                "batchId", batchId,
                "materialName", materialName,
                "actualQuantity", actualQuantity,
                "reason", reason
        ));
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "actualQuantity" -> "实际使用了多少？";
            case "materialName" -> "请问要调整哪种物料？";
            case "batchNumber" -> "请问是哪个生产批次？";
            default -> super.getParameterQuestion(paramName);
        };
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
