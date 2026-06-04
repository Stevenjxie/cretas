package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.restaurant.WastageRecord;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 记录食材损耗工具（写操作）
 *
 * 创建损耗记录（草稿状态），待审批通过后扣减库存。
 * 对应意图: RESTAURANT_WASTAGE_RECORD
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class RestaurantWastageRecordTool extends AbstractBusinessTool {

    @Autowired
    private WastageRecordRepository wastageRecordRepository;

    @Override
    public String getToolName() {
        return "restaurant_wastage_record";
    }

    @Override
    public String getDescription() {
        return "记录食材损耗（创建草稿）。需要提供食材类型ID、数量和损耗类型。" +
                "适用场景：记录过期、损坏、变质等食材损耗。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> rawMaterialTypeId = new HashMap<>();
        rawMaterialTypeId.put("type", "string");
        rawMaterialTypeId.put("description", "食材类型ID（必需）");
        properties.put("rawMaterialTypeId", rawMaterialTypeId);

        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "损耗数量（必需，大于0）");
        quantity.put("minimum", 0.01);
        properties.put("quantity", quantity);

        Map<String, Object> type = new HashMap<>();
        type.put("type", "string");
        type.put("description", "损耗类型：EXPIRED(过期)、DAMAGED(损坏)、SPOILED(变质)、PROCESSING(加工损耗)、OTHER(其他)");
        type.put("enum", List.of("EXPIRED", "DAMAGED", "SPOILED", "PROCESSING", "OTHER"));
        type.put("default", "OTHER");
        properties.put("type", type);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "损耗原因说明（可选）");
        properties.put("reason", reason);

        Map<String, Object> unit = new HashMap<>();
        unit.put("type", "string");
        unit.put("description", "单位（可选），如：kg、个、份");
        properties.put("unit", unit);

        Map<String, Object> materialBatchId = new HashMap<>();
        materialBatchId.put("type", "string");
        materialBatchId.put("description", "关联的食材批次ID（可选）");
        properties.put("materialBatchId", materialBatchId);

        // Wave2 损耗按人/档口责任制
        Map<String, Object> operatorId = new HashMap<>();
        operatorId.put("type", "integer");
        operatorId.put("description", "责任人用户ID（可选）。损耗发生时的操作责任人，用于按人归因。");
        properties.put("operatorId", operatorId);

        Map<String, Object> sectionCode = new HashMap<>();
        sectionCode.put("type", "string");
        sectionCode.put("description", "档口（可选）：SEAFOOD(海鲜)、COLD_DISH(冷菜)、HOT_DISH(热菜)、FRONT_HOUSE(前厅)、OTHER(其他)");
        sectionCode.put("enum", com.cretas.aims.entity.enums.WastageSection.codes());
        properties.put("sectionCode", sectionCode);

        schema.put("properties", properties);
        schema.put("required", List.of("rawMaterialTypeId", "quantity"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("rawMaterialTypeId", "quantity");
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行记录损耗 - 工厂ID: {}, 参数: {}", factoryId, params);

        String rawMaterialTypeId = getString(params, "rawMaterialTypeId");
        BigDecimal quantity = getBigDecimal(params, "quantity");

        if (rawMaterialTypeId == null || rawMaterialTypeId.trim().isEmpty()) {
            throw new IllegalArgumentException("请提供食材类型ID（rawMaterialTypeId）");
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("请提供有效的损耗数量（quantity），必须大于0");
        }

        // 解析损耗类型
        WastageRecord.WastageType wastageType = WastageRecord.WastageType.OTHER;
        String typeStr = getString(params, "type");
        if (typeStr != null) {
            try {
                wastageType = WastageRecord.WastageType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("损耗类型无效: {}, 使用默认 OTHER", typeStr);
            }
        }

        Long userId = getUserId(context);

        // 生成损耗单号
        long todayCount = wastageRecordRepository.countByFactoryIdAndDate(factoryId, LocalDate.now());
        String wastageNumber = String.format("WST-%s-%03d", LocalDate.now().toString().replace("-", ""), todayCount + 1);

        WastageRecord record = new WastageRecord();
        record.setFactoryId(factoryId);
        record.setWastageNumber(wastageNumber);
        record.setWastageDate(LocalDate.now());
        record.setType(wastageType);
        record.setStatus(WastageRecord.Status.DRAFT);
        record.setRawMaterialTypeId(rawMaterialTypeId.trim());
        record.setQuantity(quantity);
        record.setReportedBy(userId);

        String reason = getString(params, "reason");
        if (reason != null) {
            record.setReason(reason);
        }
        String unit = getString(params, "unit");
        if (unit != null) {
            record.setUnit(unit);
        }
        String materialBatchId = getString(params, "materialBatchId");
        if (materialBatchId != null) {
            record.setMaterialBatchId(materialBatchId);
        }

        // Wave2 损耗按人/档口责任制
        Long operatorId = getLong(params, "operatorId");
        if (operatorId != null) {
            record.setOperatorId(operatorId);
        }
        String sectionStr = getString(params, "sectionCode");
        if (sectionStr != null && !sectionStr.trim().isEmpty()) {
            com.cretas.aims.entity.enums.WastageSection section =
                    com.cretas.aims.entity.enums.WastageSection.fromCode(sectionStr);
            if (section == null) {
                throw new IllegalArgumentException("无效的档口编码: " + sectionStr
                        + "（可选: " + String.join(" / ", com.cretas.aims.entity.enums.WastageSection.codes()) + "）");
            }
            record.setSectionCode(section.name());
        }

        wastageRecordRepository.save(record);
        log.info("记录损耗成功: factoryId={}, wastageNumber={}, id={}", factoryId, wastageNumber, record.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("损耗单号", wastageNumber);
        result.put("id", record.getId());
        result.put("食材ID", rawMaterialTypeId);
        result.put("数量", quantity);
        result.put("类型", wastageType.name());
        if (record.getSectionCode() != null) {
            result.put("档口", com.cretas.aims.entity.enums.WastageSection.labelOf(record.getSectionCode()));
        }
        if (record.getOperatorId() != null) {
            result.put("责任人ID", record.getOperatorId());
        }
        result.put("状态", "草稿");
        result.put("下一步", "损耗记录已创建（草稿），提交后由管理员审批，审批通过将自动扣减库存。");
        result.put("message", String.format("损耗记录「%s」已创建（%s，数量 %s），待提交审批。",
                wastageNumber, wastageType.name(), quantity));

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "rawMaterialTypeId", "请问损耗的是哪种食材？请提供食材类型ID。",
            "quantity", "请问损耗数量是多少？",
            "type", "请问损耗类型是什么？可选：EXPIRED(过期)、DAMAGED(损坏)、SPOILED(变质)、PROCESSING(加工损耗)、OTHER(其他)",
            "reason", "请说明损耗原因（可选）。",
            "unit", "请问单位是什么？如：kg、个、份（可选）",
            "materialBatchId", "请问关联的食材批次ID是什么？（可选）"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "rawMaterialTypeId", "食材类型ID",
            "quantity", "损耗数量",
            "type", "损耗类型",
            "reason", "损耗原因",
            "unit", "单位",
            "materialBatchId", "食材批次ID",
            "operatorId", "责任人ID",
            "sectionCode", "档口"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }
}
