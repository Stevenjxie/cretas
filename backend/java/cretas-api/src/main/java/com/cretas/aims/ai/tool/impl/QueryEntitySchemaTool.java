package com.cretas.aims.ai.tool.impl;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Attribute;
import java.util.*;

/**
 * 查询实体Schema工具
 *
 * 当LLM需要了解某个实体的字段结构时，调用此工具获取完整的Schema信息。
 * 用于辅助生成数据操作意图、表单生成意图等。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Slf4j
@Component
public class QueryEntitySchemaTool extends AbstractTool {

    @Autowired
    private EntityManager entityManager;

    /**
     * 实体名称映射表（支持中文、英文、PascalCase）
     */
    private static final Map<String, String> ENTITY_ALIASES = new HashMap<>();

    static {
        // 原料批次
        ENTITY_ALIASES.put("原料批次", "MaterialBatch");
        ENTITY_ALIASES.put("materialbatch", "MaterialBatch");
        ENTITY_ALIASES.put("material_batch", "MaterialBatch");

        // 生产批次
        ENTITY_ALIASES.put("生产批次", "ProcessingBatch");
        ENTITY_ALIASES.put("processingbatch", "ProcessingBatch");
        ENTITY_ALIASES.put("processing_batch", "ProcessingBatch");

        // 产品类型
        ENTITY_ALIASES.put("产品类型", "ProductType");
        ENTITY_ALIASES.put("producttype", "ProductType");
        ENTITY_ALIASES.put("product_type", "ProductType");

        // 生产计划
        ENTITY_ALIASES.put("生产计划", "ProductionPlan");
        ENTITY_ALIASES.put("productionplan", "ProductionPlan");
        ENTITY_ALIASES.put("production_plan", "ProductionPlan");

        // 质检记录
        ENTITY_ALIASES.put("质检记录", "QualityCheckRecord");
        ENTITY_ALIASES.put("qualitycheckrecord", "QualityCheckRecord");
        ENTITY_ALIASES.put("quality_check_record", "QualityCheckRecord");

        // 出货记录
        ENTITY_ALIASES.put("出货记录", "ShipmentRecord");
        ENTITY_ALIASES.put("shipmentrecord", "ShipmentRecord");
        ENTITY_ALIASES.put("shipment_record", "ShipmentRecord");

        // 设备信息
        ENTITY_ALIASES.put("设备", "Equipment");
        ENTITY_ALIASES.put("equipment", "Equipment");

        // 考勤记录
        ENTITY_ALIASES.put("考勤", "AttendanceRecord");
        ENTITY_ALIASES.put("attendancerecord", "AttendanceRecord");
        ENTITY_ALIASES.put("attendance_record", "AttendanceRecord");
    }

    @Override
    public String getToolName() {
        return "query_entity_schema";
    }

    @Override
    public String getDescription() {
        return "查询指定实体的Schema信息，包括所有字段名称、类型、是否必填等。" +
                "用于辅助生成数据查询、数据操作、表单生成等意图。" +
                "支持中文、英文和下划线格式的实体名称。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // entityName: 实体名称（必需）
        Map<String, Object> entityName = new HashMap<>();
        entityName.put("type", "string");
        entityName.put("description", "实体名称，支持中文（如：原料批次）、英文（如：MaterialBatch）、下划线格式（如：material_batch）");
        properties.put("entityName", entityName);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("entityName"));

        return schema;
    }

    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
        logExecutionStart(toolCall, context);

        try {
            // 1. 解析参数
            Map<String, Object> arguments = parseArguments(toolCall);
            String entityName = getRequiredParam(arguments, "entityName");

            // 2. 标准化实体名称
            String normalizedEntityName = normalizeEntityName(entityName);
            if (normalizedEntityName == null) {
                return buildErrorResult("未识别的实体名称: " + entityName);
            }

            // 3. 获取实体Schema
            EntityType<?> entityType = findEntityType(normalizedEntityName);
            if (entityType == null) {
                return buildErrorResult("实体不存在: " + normalizedEntityName);
            }

            // 4. 构建Schema信息
            Map<String, Object> schemaInfo = buildSchemaInfo(entityType);

            String result = buildSuccessResult(schemaInfo);
            logExecutionSuccess(toolCall, result);

            return result;

        } catch (IllegalArgumentException e) {
            log.warn("⚠️  参数验证失败: {}", e.getMessage());
            return buildErrorResult("参数验证失败: " + e.getMessage());

        } catch (Exception e) {
            logExecutionFailure(toolCall, e);
            return buildErrorResult("查询Schema失败: " + e.getMessage());
        }
    }

    /**
     * 标准化实体名称
     */
    private String normalizeEntityName(String entityName) {
        // 直接匹配
        if (ENTITY_ALIASES.containsKey(entityName)) {
            return ENTITY_ALIASES.get(entityName);
        }

        // 转小写匹配
        String lowerName = entityName.toLowerCase();
        if (ENTITY_ALIASES.containsKey(lowerName)) {
            return ENTITY_ALIASES.get(lowerName);
        }

        // 假设已经是标准名称
        return entityName;
    }

    /**
     * 查找实体类型
     */
    private EntityType<?> findEntityType(String entityName) {
        Set<EntityType<?>> entities = entityManager.getMetamodel().getEntities();
        for (EntityType<?> entity : entities) {
            if (entity.getName().equals(entityName) ||
                    entity.getJavaType().getSimpleName().equals(entityName)) {
                return entity;
            }
        }
        return null;
    }

    /**
     * 构建Schema信息
     */
    private Map<String, Object> buildSchemaInfo(EntityType<?> entityType) {
        Map<String, Object> schemaInfo = new HashMap<>();
        schemaInfo.put("entityName", entityType.getName());
        schemaInfo.put("javaClass", entityType.getJavaType().getName());

        // 字段列表
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Attribute<?, ?> attribute : entityType.getAttributes()) {
            Map<String, Object> fieldInfo = new HashMap<>();
            fieldInfo.put("name", attribute.getName());
            fieldInfo.put("javaType", attribute.getJavaType().getSimpleName());
            fieldInfo.put("persistent", attribute.isAssociation() ? "association" : "basic");
            fieldInfo.put("collection", attribute.isCollection());

            fields.add(fieldInfo);
        }
        schemaInfo.put("fields", fields);
        schemaInfo.put("fieldCount", fields.size());

        return schemaInfo;
    }

    /**
     * 此工具不需要特殊权限
     */
    @Override
    public boolean requiresPermission() {
        return false;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
