package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 生产批次创建工具（数据操作版）
 *
 * 通过数据操作意图创建生产批次。
 * Intent Code: PROCESSING_BATCH_CREATE (via DATA_OP handler)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ProcessingBatchCreateToolDataOp extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "dataop_batch_create";
    }

    @Override
    public String getDescription() {
        return "创建新的生产批次。需要提供产品类型ID和计划产量。" +
                "适用场景：新建加工批次、创建生产批次。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description", "产品类型ID");
        properties.put("productTypeId", productTypeId);

        Map<String, Object> plannedQuantity = new HashMap<>();
        plannedQuantity.put("type", "number");
        plannedQuantity.put("description", "计划产量");
        properties.put("plannedQuantity", plannedQuantity);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("productTypeId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("productTypeId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String productTypeId = getString(params, "productTypeId");

        try {
            ProductionBatch batch = new ProductionBatch();
            batch.setFactoryId(factoryId);
            batch.setProductTypeId(productTypeId);

            Object plannedQtyObj = params.get("plannedQuantity");
            if (plannedQtyObj != null) {
                BigDecimal plannedQty;
                if (plannedQtyObj instanceof BigDecimal) {
                    plannedQty = (BigDecimal) plannedQtyObj;
                } else if (plannedQtyObj instanceof Number) {
                    plannedQty = BigDecimal.valueOf(((Number) plannedQtyObj).doubleValue());
                } else {
                    plannedQty = new BigDecimal(plannedQtyObj.toString());
                }
                batch.setPlannedQuantity(plannedQty);
            }

            ProductionBatch created = processingService.createBatch(factoryId, batch);
            return buildSimpleResult("生产批次创建成功", created);
        } catch (Exception e) {
            log.error("生产批次创建失败: factoryId={}, productTypeId={}", factoryId, productTypeId, e);
            throw e;
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
