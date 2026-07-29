package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.bom.*;
import com.cretas.aims.entity.bom.EngineeringChangeNotice.EcnReason;
import com.cretas.aims.service.bom.BomBatchOperationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * M-BOM-VER-1 — BOM 批量操作 (Sprint 3 Track-H).
 *
 * <p>operation = MODIFY / REPLACE / DELETE / ADD. 每批量自动 spawn 1 ECN DRAFT + N
 * BomVersion DRAFT. allInFactory=true 或 recipeIds 子集二选一.
 *
 * 适用场景: "把所有牛肉 BOM 的盐量加 1g" → MODIFY / "鸡精停产, 全部替换为味精" → REPLACE /
 * "全产品加 0.5g 防腐剂" → ADD / "白砂糖配方全废" → DELETE.
 */
@Slf4j
@Component
public class BomBatchOperationTool extends AbstractBusinessTool {

    @Autowired
    private BomBatchOperationService batchService;

    @Override
    public String getToolName() {
        return "bom_batch_operation";
    }

    @Override
    public String getDescription() {
        return "BOM 批量操作 (MODIFY/REPLACE/DELETE/ADD). 跨多 BOM 同一物料统一变更. "
             + "自动 spawn 1 ECN DRAFT + N BomVersion DRAFT (per affected recipe). "
             + "适用场景: 大规模物料调整 / 物料停产替换 / 全产品加新配方项 / 配方淘汰物料.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("operation", Map.of("type", "string",
                "enum", Arrays.asList("MODIFY", "REPLACE", "DELETE", "ADD"),
                "description", "批量操作类型"));
        properties.put("materialId", Map.of("type", "string",
                "description", "物料 ID (MODIFY/DELETE 为目标, REPLACE 为 oldMaterial, ADD 为 newMaterial)"));
        properties.put("newMaterialId", Map.of("type", "string",
                "description", "REPLACE 时的新物料 ID"));
        properties.put("newStandardQuantity", Map.of("type", "number",
                "description", "MODIFY/ADD 的新标准用量"));
        properties.put("newUnit", Map.of("type", "string", "description", "MODIFY/ADD 单位"));
        properties.put("yieldRate", Map.of("type", "number", "description", "ADD 的出成率 0-100 (默认 100)"));
        properties.put("materialCategory", Map.of("type", "string",
                "description", "ADD 的物料分类 RAW/AUXILIARY/PACKAGING (默认 RAW)"));
        properties.put("allInFactory", Map.of("type", "boolean",
                "description", "true = 工厂全 BOM; false 时需提供 recipeIds"));
        properties.put("recipeIds", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "BOM 配方 ID 子集 (allInFactory=false 时必填)"));
        properties.put("ecnReason", Map.of("type", "string",
                "enum", Arrays.asList("CUSTOMER_REQUEST", "MATERIAL_DISCONTINUED",
                        "COST_OPTIMIZATION", "QUALITY_DEFECT", "PROCESS_IMPROVEMENT"),
                "description", "ECN 5 类变更原因"));
        properties.put("ecnReasonDetail", Map.of("type", "string", "description", "ECN 原因详情"));
        properties.put("effectiveDate", Map.of("type", "string", "format", "date",
                "description", "ECN 生效日期 YYYY-MM-DD"));
        properties.put("createdBy", Map.of("type", "integer", "description", "创建者 ID"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("operation", "materialId", "ecnReason"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("operation", "materialId", "ecnReason");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String operation = getString(params, "operation");
        String materialId = getString(params, "materialId");
        EcnReason reason = EcnReason.valueOf(getString(params, "ecnReason").toUpperCase());
        String reasonDetail = getString(params, "ecnReasonDetail");
        Long createdBy = getLong(params, "createdBy");
        Boolean allInFactoryObj = (Boolean) params.get("allInFactory");
        boolean allInFactory = Boolean.TRUE.equals(allInFactoryObj);
        @SuppressWarnings("unchecked")
        List<String> recipeIds = (List<String>) params.get("recipeIds");
        String effectiveDateStr = getString(params, "effectiveDate");
        LocalDate effectiveDate = (effectiveDateStr == null || effectiveDateStr.isBlank())
                ? null : LocalDate.parse(effectiveDateStr);

        BatchResult result;
        switch (operation.toUpperCase()) {
            case "MODIFY": {
                BigDecimal qty = getBigDecimal(params, "newStandardQuantity");
                if (qty == null) {
                    throw new IllegalArgumentException("MODIFY 必须提供 newStandardQuantity");
                }
                result = batchService.batchModify(BatchModifyRequest.builder()
                        .factoryId(factoryId).materialId(materialId)
                        .newStandardQuantity(qty)
                        .newUnit(getString(params, "newUnit"))
                        .allInFactory(allInFactory).recipeIds(recipeIds)
                        .ecnReason(reason).ecnReasonDetail(reasonDetail)
                        .effectiveDate(effectiveDate).createdBy(createdBy).build());
                break;
            }
            case "REPLACE": {
                String newMatId = getString(params, "newMaterialId");
                if (newMatId == null || newMatId.isBlank()) {
                    throw new IllegalArgumentException("REPLACE 必须提供 newMaterialId");
                }
                result = batchService.batchReplace(BatchReplaceRequest.builder()
                        .factoryId(factoryId).oldMaterialId(materialId).newMaterialId(newMatId)
                        .allInFactory(allInFactory).recipeIds(recipeIds)
                        .ecnReason(reason).ecnReasonDetail(reasonDetail)
                        .effectiveDate(effectiveDate).createdBy(createdBy).build());
                break;
            }
            case "DELETE": {
                result = batchService.batchDelete(BatchDeleteRequest.builder()
                        .factoryId(factoryId).materialId(materialId)
                        .allInFactory(allInFactory).recipeIds(recipeIds)
                        .ecnReason(reason).ecnReasonDetail(reasonDetail)
                        .effectiveDate(effectiveDate).createdBy(createdBy).build());
                break;
            }
            case "ADD": {
                BigDecimal qty = getBigDecimal(params, "newStandardQuantity");
                String unit = getString(params, "newUnit");
                if (qty == null || unit == null || unit.isBlank()) {
                    throw new IllegalArgumentException("ADD 必须提供 newStandardQuantity + newUnit");
                }
                BigDecimal yieldRate = getBigDecimal(params, "yieldRate");
                result = batchService.batchAdd(BatchAddRequest.builder()
                        .factoryId(factoryId).materialId(materialId)
                        .standardQuantity(qty).yieldRate(yieldRate).unit(unit)
                        .materialCategory(getString(params, "materialCategory"))
                        .allInFactory(allInFactory).recipeIds(recipeIds)
                        .ecnReason(reason).ecnReasonDetail(reasonDetail)
                        .effectiveDate(effectiveDate).createdBy(createdBy).build());
                break;
            }
            default:
                throw new IllegalArgumentException("无效 operation: " + operation
                        + " (限 MODIFY/REPLACE/DELETE/ADD)");
        }
        log.info("Batch op via AI: factory={}, operation={}, affected={}, ecn={}",
                factoryId, operation, result.getAffectedRecipeIds().size(), result.getEcnNumber());
        return buildSimpleResult("批量 " + operation + " 完成: "
                + result.getAffectedRecipeIds().size() + " BOM 影响, ECN " + result.getEcnNumber(), result);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
