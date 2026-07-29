package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 生产批次创建工具
 *
 * 创建新的生产批次，支持指定产品类型、批次号、计划数量等信息。
 * 创建后默认状态为 PLANNED（已计划）。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class ProcessingBatchCreateTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String getToolName() {
        return "processing_batch_create";
    }

    @Override
    public String getDescription() {
        return "创建生产批次。需要指定产品类型ID、批次号、计划数量，可选指定负责人ID和开始时间。" +
                "适用场景：新建生产任务、创建加工批次、安排生产计划。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // productTypeId: 产品类型ID（必需）
        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description", "产品类型ID，关联ProductType表");
        properties.put("productTypeId", productTypeId);

        // batchNumber: 批次号（必需）
        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号，唯一标识生产批次");
        properties.put("batchNumber", batchNumber);

        // plannedQuantity: 计划数量（必需）
        Map<String, Object> plannedQuantity = new HashMap<>();
        plannedQuantity.put("type", "number");
        plannedQuantity.put("description", "计划生产数量");
        plannedQuantity.put("minimum", 0.001);
        properties.put("plannedQuantity", plannedQuantity);

        // supervisorId: 负责人ID（可选）
        Map<String, Object> supervisorId = new HashMap<>();
        supervisorId.put("type", "integer");
        supervisorId.put("description", "生产负责人ID");
        properties.put("supervisorId", supervisorId);

        // startTime: 计划开始时间（可选）
        Map<String, Object> startTime = new HashMap<>();
        startTime.put("type", "string");
        startTime.put("description", "计划开始时间，格式：yyyy-MM-dd HH:mm:ss");
        startTime.put("format", "date-time");
        properties.put("startTime", startTime);

        // unit: 单位（可选，默认kg）
        Map<String, Object> unit = new HashMap<>();
        unit.put("type", "string");
        unit.put("description", "计量单位，如：kg、件、箱");
        unit.put("default", "kg");
        properties.put("unit", unit);

        // notes: 备注（可选）
        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "批次备注信息");
        notes.put("maxLength", 500);
        properties.put("notes", notes);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("productTypeId", "batchNumber", "plannedQuantity"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("productTypeId", "batchNumber", "plannedQuantity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行创建生产批次 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 1. 解析必需参数
        String productTypeId = getString(params, "productTypeId");
        String batchNumber = getString(params, "batchNumber");
        BigDecimal plannedQuantity = getBigDecimal(params, "plannedQuantity");

        // 2. 解析可选参数
        Integer supervisorId = getInteger(params, "supervisorId");
        String startTimeStr = getString(params, "startTime");
        String unit = getString(params, "unit", "kg");
        String notes = getString(params, "notes");

        // 3. 构建生产批次实体
        ProductionBatch batch = ProductionBatch.builder()
                .factoryId(factoryId)
                .productTypeId(productTypeId)
                .batchNumber(batchNumber)
                .plannedQuantity(plannedQuantity)
                .quantity(plannedQuantity) // 初始数量等于计划数量
                .unit(unit)
                .status(ProductionBatchStatus.PLANNED)
                .notes(notes)
                .build();

        // 设置负责人
        if (supervisorId != null) {
            batch.setSupervisorId(supervisorId.longValue());
        }

        // 设置开始时间
        if (startTimeStr != null && !startTimeStr.trim().isEmpty()) {
            try {
                LocalDateTime startTime = LocalDateTime.parse(startTimeStr, DATETIME_FORMATTER);
                batch.setStartTime(startTime);
            } catch (DateTimeParseException e) {
                log.warn("开始时间格式解析失败: {}", startTimeStr);
            }
        }

        // 设置创建人
        Long userId = getUserId(context);
        batch.setCreatedBy(userId);

        // 4. 调用服务创建
        ProductionBatch created = processingService.createBatch(factoryId, batch);

        // 5. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", created.getId());
        result.put("batchNumber", created.getBatchNumber());
        result.put("productTypeId", created.getProductTypeId());
        result.put("productName", created.getProductName());
        result.put("plannedQuantity", created.getPlannedQuantity());
        result.put("unit", created.getUnit());
        result.put("status", created.getStatus().name());
        result.put("supervisorId", created.getSupervisorId());
        result.put("startTime", created.getStartTime() != null ? created.getStartTime().toString() : null);
        result.put("message", String.format("生产批次创建成功，批次号: %s，计划产量: %s %s",
                created.getBatchNumber(), created.getPlannedQuantity(), created.getUnit()));

        log.info("生产批次创建完成 - 批次ID: {}, 批次号: {}", created.getId(), created.getBatchNumber());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "productTypeId":
                return "请问要生产什么产品？请提供产品类型ID。";
            case "batchNumber":
                return "请问批次号是什么？";
            case "plannedQuantity":
                return "请问计划生产数量是多少？";
            case "supervisorId":
                return "请问由谁负责这个批次的生产？（可选）";
            case "startTime":
                return "请问计划什么时候开始生产？（格式：yyyy-MM-dd HH:mm:ss，可选）";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "productTypeId":
                return "产品类型ID";
            case "batchNumber":
                return "批次号";
            case "plannedQuantity":
                return "计划数量";
            case "supervisorId":
                return "负责人ID";
            case "startTime":
                return "开始时间";
            case "unit":
                return "单位";
            case "notes":
                return "备注";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "platform_admin".equals(userRole) ||
                "factory_admin".equals(userRole) ||
                "production_manager".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
