package com.cretas.aims.ai.tool.impl.form;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.FormTemplate;
import com.cretas.aims.service.FormTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 表单生成工具
 *
 * 根据实体类型和用途生成动态表单配置。
 * 支持多种表单类型：创建表单、编辑表单、查询表单等。
 *
 * Intent Code: FORM_GENERATION
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class FormGenerationTool extends AbstractBusinessTool {

    @Autowired
    private FormTemplateService formTemplateService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getToolName() {
        return "form_generation";
    }

    @Override
    public String getDescription() {
        return "根据实体类型和用途生成动态表单配置。支持创建、编辑、查询等多种表单类型。" +
                "适用场景：生成数据录入表单、创建查询筛选表单、生成审批表单。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // entityType: 实体类型（必需）
        Map<String, Object> entityType = new HashMap<>();
        entityType.put("type", "string");
        entityType.put("description", "实体类型");
        entityType.put("enum", Arrays.asList(
                "MATERIAL_BATCH",     // 原料批次
                "PRODUCT",            // 产品
                "QUALITY_CHECK",      // 质检记录
                "SHIPMENT",           // 出货单
                "SUPPLIER",           // 供应商
                "CUSTOMER",           // 客户
                "EQUIPMENT",          // 设备
                "USER",               // 用户
                "PRODUCTION_PLAN"     // 生产计划
        ));
        properties.put("entityType", entityType);

        // formPurpose: 表单用途（必需）
        Map<String, Object> formPurpose = new HashMap<>();
        formPurpose.put("type", "string");
        formPurpose.put("description", "表单用途");
        formPurpose.put("enum", Arrays.asList(
                "CREATE",    // 创建表单
                "EDIT",      // 编辑表单
                "VIEW",      // 查看表单
                "QUERY",     // 查询表单
                "APPROVAL",  // 审批表单
                "IMPORT"     // 导入表单
        ));
        properties.put("formPurpose", formPurpose);

        // includeFields: 包含字段（可选）
        Map<String, Object> includeFields = new HashMap<>();
        includeFields.put("type", "array");
        includeFields.put("description", "指定包含的字段列表，不指定则包含所有可用字段");
        Map<String, Object> fieldItem = new HashMap<>();
        fieldItem.put("type", "string");
        includeFields.put("items", fieldItem);
        properties.put("includeFields", includeFields);

        // excludeFields: 排除字段（可选）
        Map<String, Object> excludeFields = new HashMap<>();
        excludeFields.put("type", "array");
        excludeFields.put("description", "指定排除的字段列表");
        excludeFields.put("items", fieldItem);
        properties.put("excludeFields", excludeFields);

        // layout: 布局类型（可选）
        Map<String, Object> layout = new HashMap<>();
        layout.put("type", "string");
        layout.put("description", "表单布局类型");
        layout.put("enum", Arrays.asList("VERTICAL", "HORIZONTAL", "GRID", "INLINE"));
        layout.put("default", "VERTICAL");
        properties.put("layout", layout);

        // columns: 列数（可选）
        Map<String, Object> columns = new HashMap<>();
        columns.put("type", "integer");
        columns.put("description", "表单列数，用于GRID布局");
        columns.put("minimum", 1);
        columns.put("maximum", 4);
        columns.put("default", 1);
        properties.put("columns", columns);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("entityType", "formPurpose"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("entityType", "formPurpose");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String entityType = getString(params, "entityType");
        String formPurpose = getString(params, "formPurpose");
        String layout = getString(params, "layout", "VERTICAL");

        // Extract userId from context
        Long userId = null;
        Object userIdObj = context != null ? context.get("userId") : null;
        if (userIdObj instanceof Long) {
            userId = (Long) userIdObj;
        } else if (userIdObj instanceof Number) {
            userId = ((Number) userIdObj).longValue();
        } else if (userIdObj instanceof String) {
            try { userId = Long.parseLong((String) userIdObj); } catch (NumberFormatException ignored) {}
        }
        if (userId == null) userId = 0L;

        try {
            // Check if a custom template already exists for this factory+entityType
            Optional<FormTemplate> existing = formTemplateService.getByFactoryAndEntityType(factoryId, entityType);
            if (existing.isPresent() && "VIEW".equalsIgnoreCase(formPurpose)) {
                FormTemplate template = existing.get();
                Map<String, Object> result = new HashMap<>();
                result.put("templateId", template.getId());
                result.put("entityType", entityType);
                result.put("formPurpose", formPurpose);
                result.put("name", template.getName());
                result.put("schemaJson", template.getSchemaJson());
                result.put("source", "existing_template");
                return buildSimpleResult("已找到现有表单模板: " + template.getName(), result);
            }

            // Generate form fields based on entityType + formPurpose
            List<Map<String, Object>> fields = generateFormFields(entityType, formPurpose, null, null);
            Map<String, Object> schemaMap = new HashMap<>();
            schemaMap.put("entityType", entityType);
            schemaMap.put("formPurpose", formPurpose);
            schemaMap.put("layout", layout);
            schemaMap.put("fields", fields);

            String schemaJson = objectMapper.writeValueAsString(schemaMap);
            String templateName = getEntityTypeName(entityType) + " - " + getFormPurposeName(formPurpose);

            // Persist via FormTemplateService.createFromAI
            FormTemplate created = formTemplateService.createFromAI(
                    factoryId, entityType, templateName, schemaJson,
                    "AI生成表单: " + templateName, userId);

            Map<String, Object> result = new HashMap<>();
            result.put("templateId", created.getId());
            result.put("entityType", entityType);
            result.put("formPurpose", formPurpose);
            result.put("name", created.getName());
            result.put("layout", layout);
            result.put("fieldCount", fields.size());
            result.put("fields", fields);
            result.put("schemaJson", schemaJson);
            result.put("source", "ai_generated");

            return buildSimpleResult(templateName + " 表单已生成，共 " + fields.size() + " 个字段", result);
        } catch (Exception e) {
            log.error("生成表单失败 - 工厂ID: {}, 实体类型: {}", factoryId, entityType, e);
            throw e;
        }
    }

    /**
     * 生成表单字段配置（模拟实现）
     */
    private List<Map<String, Object>> generateFormFields(String entityType, String formPurpose,
            List<String> includeFields, List<String> excludeFields) {

        List<Map<String, Object>> fields = new ArrayList<>();

        // 根据实体类型生成基础字段
        switch (entityType.toUpperCase()) {
            case "MATERIAL_BATCH":
                fields.add(createField("batchNumber", "批次号", "text", true));
                fields.add(createField("materialTypeId", "原材料类型", "select", true));
                fields.add(createField("quantity", "数量", "number", true));
                fields.add(createField("unit", "单位", "select", true));
                fields.add(createField("supplierId", "供应商", "select", false));
                fields.add(createField("productionDate", "生产日期", "date", false));
                fields.add(createField("expiryDate", "过期日期", "date", false));
                fields.add(createField("storageLocation", "存储位置", "text", false));
                fields.add(createField("remark", "备注", "textarea", false));
                break;
            case "PRODUCT":
                fields.add(createField("productCode", "产品编码", "text", true));
                fields.add(createField("productName", "产品名称", "text", true));
                fields.add(createField("productTypeId", "产品类型", "select", true));
                fields.add(createField("specification", "规格", "text", false));
                fields.add(createField("unit", "单位", "select", true));
                fields.add(createField("description", "描述", "textarea", false));
                break;
            case "QUALITY_CHECK":
                fields.add(createField("checkType", "检验类型", "select", true));
                fields.add(createField("batchId", "批次", "select", true));
                fields.add(createField("checkItems", "检验项目", "multiselect", true));
                fields.add(createField("result", "检验结果", "select", false));
                fields.add(createField("inspector", "检验员", "select", false));
                fields.add(createField("checkDate", "检验日期", "datetime", false));
                fields.add(createField("remark", "备注", "textarea", false));
                break;
            case "USER":
                fields.add(createField("username", "用户名", "text", true));
                fields.add(createField("realName", "真实姓名", "text", false));
                fields.add(createField("role", "角色", "select", true));
                fields.add(createField("phone", "手机号", "text", false));
                fields.add(createField("email", "邮箱", "email", false));
                fields.add(createField("department", "部门", "select", false));
                break;
            default:
                fields.add(createField("name", "名称", "text", true));
                fields.add(createField("code", "编码", "text", false));
                fields.add(createField("description", "描述", "textarea", false));
        }

        // 根据用途调整字段
        if ("VIEW".equals(formPurpose.toUpperCase())) {
            for (Map<String, Object> field : fields) {
                field.put("readonly", true);
            }
        } else if ("QUERY".equals(formPurpose.toUpperCase())) {
            for (Map<String, Object> field : fields) {
                field.put("required", false);
            }
        }

        // 应用includeFields过滤
        if (includeFields != null && !includeFields.isEmpty()) {
            fields.removeIf(field -> !includeFields.contains(field.get("name")));
        }

        // 应用excludeFields过滤
        if (excludeFields != null && !excludeFields.isEmpty()) {
            fields.removeIf(field -> excludeFields.contains(field.get("name")));
        }

        return fields;
    }

    /**
     * 创建字段配置
     */
    private Map<String, Object> createField(String name, String label, String type, boolean required) {
        Map<String, Object> field = new HashMap<>();
        field.put("name", name);
        field.put("label", label);
        field.put("type", type);
        field.put("required", required);
        field.put("readonly", false);
        return field;
    }

    /**
     * 获取实体类型的中文名称
     */
    private String getEntityTypeName(String entityType) {
        Map<String, String> typeNames = Map.of(
            "MATERIAL_BATCH", "原料批次",
            "PRODUCT", "产品",
            "QUALITY_CHECK", "质检记录",
            "SHIPMENT", "出货单",
            "SUPPLIER", "供应商",
            "CUSTOMER", "客户",
            "EQUIPMENT", "设备",
            "USER", "用户",
            "PRODUCTION_PLAN", "生产计划"
        );
        return typeNames.getOrDefault(entityType.toUpperCase(), entityType);
    }

    /**
     * 获取表单用途的中文名称
     */
    private String getFormPurposeName(String purpose) {
        Map<String, String> purposeNames = Map.of(
            "CREATE", "创建表单",
            "EDIT", "编辑表单",
            "VIEW", "查看表单",
            "QUERY", "查询表单",
            "APPROVAL", "审批表单",
            "IMPORT", "导入表单"
        );
        return purposeNames.getOrDefault(purpose.toUpperCase(), purpose);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "entityType", "请问要为哪种数据类型生成表单？（原料批次/产品/质检记录/出货单/供应商/客户/设备/用户/生产计划）",
            "formPurpose", "请问表单的用途是什么？（创建/编辑/查看/查询/审批/导入）"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "entityType", "实体类型",
            "formPurpose", "表单用途",
            "includeFields", "包含字段",
            "excludeFields", "排除字段",
            "layout", "布局类型",
            "columns", "列数"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
