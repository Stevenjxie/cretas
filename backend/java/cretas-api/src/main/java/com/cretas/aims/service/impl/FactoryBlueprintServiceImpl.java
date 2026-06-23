package com.cretas.aims.service.impl;

import com.cretas.aims.dto.blueprint.*;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.config.BlueprintApplication;
import com.cretas.aims.entity.config.FactoryTypeBlueprint;
import com.cretas.aims.entity.config.FormTemplate;
import com.cretas.aims.entity.rules.DroolsRule;
import com.cretas.aims.repository.DroolsRuleRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.config.BlueprintApplicationRepository;
import com.cretas.aims.repository.config.FactoryTypeBlueprintRepository;
import com.cretas.aims.repository.FormTemplateRepository;
import com.cretas.aims.service.FactoryBlueprintService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cretas.aims.exception.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工厂蓝图服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactoryBlueprintServiceImpl implements FactoryBlueprintService {

    private final FactoryTypeBlueprintRepository blueprintRepository;
    private final BlueprintApplicationRepository applicationRepository;
    private final FormTemplateRepository formTemplateRepository;
    private final ProductTypeRepository productTypeRepository;
    private final DroolsRuleRepository droolsRuleRepository;
    private final ObjectMapper objectMapper;

    @Override
    public List<FactoryTypeBlueprint> getAllBlueprints() {
        return blueprintRepository.findByIsActiveTrueAndDeletedAtIsNull();
    }

    @Override
    public List<FactoryTypeBlueprint> getBlueprintsByIndustryType(String industryType) {
        return blueprintRepository.findByIndustryTypeAndDeletedAtIsNull(industryType);
    }

    @Override
    public FactoryTypeBlueprint getBlueprintById(String id) {
        return blueprintRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new EntityNotFoundException("蓝图不存在: " + id));
    }

    @Override
    @Transactional
    public FactoryTypeBlueprint createBlueprint(CreateBlueprintRequest request) {
        log.info("创建蓝图: {}", request.getName());

        try {
            FactoryTypeBlueprint blueprint = new FactoryTypeBlueprint();
            blueprint.setName(request.getName());
            blueprint.setDescription(request.getDescription());
            blueprint.setIndustryType(request.getIndustryType());

            // 转换为JSON字符串
            if (request.getDefaultConfig() != null) {
                blueprint.setDefaultConfig(objectMapper.writeValueAsString(request.getDefaultConfig()));
            }
            if (request.getFormTemplates() != null) {
                blueprint.setFormTemplates(objectMapper.writeValueAsString(request.getFormTemplates()));
            }
            if (request.getRuleTemplates() != null) {
                blueprint.setRuleTemplates(objectMapper.writeValueAsString(request.getRuleTemplates()));
            }
            if (request.getProductTypeTemplates() != null) {
                blueprint.setProductTypeTemplates(objectMapper.writeValueAsString(request.getProductTypeTemplates()));
            }
            if (request.getDepartmentTemplates() != null) {
                blueprint.setDepartmentTemplates(objectMapper.writeValueAsString(request.getDepartmentTemplates()));
            }

            blueprint.setIsActive(request.getIsActive());

            return blueprintRepository.save(blueprint);
        } catch (Exception e) {
            log.error("创建蓝图失败", e);
            throw new RuntimeException("创建蓝图失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public FactoryTypeBlueprint updateBlueprint(String id, CreateBlueprintRequest request) {
        log.info("更新蓝图: {}", id);

        try {
            FactoryTypeBlueprint blueprint = getBlueprintById(id);
            blueprint.setName(request.getName());
            blueprint.setDescription(request.getDescription());
            blueprint.setIndustryType(request.getIndustryType());

            // 转换为JSON字符串
            if (request.getDefaultConfig() != null) {
                blueprint.setDefaultConfig(objectMapper.writeValueAsString(request.getDefaultConfig()));
            }
            if (request.getFormTemplates() != null) {
                blueprint.setFormTemplates(objectMapper.writeValueAsString(request.getFormTemplates()));
            }
            if (request.getRuleTemplates() != null) {
                blueprint.setRuleTemplates(objectMapper.writeValueAsString(request.getRuleTemplates()));
            }
            if (request.getProductTypeTemplates() != null) {
                blueprint.setProductTypeTemplates(objectMapper.writeValueAsString(request.getProductTypeTemplates()));
            }
            if (request.getDepartmentTemplates() != null) {
                blueprint.setDepartmentTemplates(objectMapper.writeValueAsString(request.getDepartmentTemplates()));
            }

            blueprint.setIsActive(request.getIsActive());
            blueprint.setVersion(blueprint.getVersion() + 1);

            return blueprintRepository.save(blueprint);
        } catch (Exception e) {
            log.error("更新蓝图失败", e);
            throw new RuntimeException("更新蓝图失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void deleteBlueprint(String id) {
        log.info("删除蓝图: {}", id);

        FactoryTypeBlueprint blueprint = getBlueprintById(id);
        blueprint.setDeletedAt(LocalDateTime.now());
        blueprintRepository.save(blueprint);
    }

    @Override
    @Transactional
    public BlueprintApplicationResult applyBlueprintToFactory(String blueprintId, ApplyBlueprintRequest request) {
        log.info("应用蓝图 {} 到工厂 {}", blueprintId, request.getFactoryId());

        FactoryTypeBlueprint blueprint = getBlueprintById(blueprintId);
        String factoryId = request.getFactoryId();
        boolean isPreview = request.getPreview() != null && request.getPreview();

        BlueprintApplicationResult result = BlueprintApplicationResult.builder()
                .blueprintId(blueprintId)
                .blueprintName(blueprint.getName())
                .factoryId(factoryId)
                .appliedAt(LocalDateTime.now())
                .status("IN_PROGRESS")
                .success(false)
                .build();

        try {
            // 创建应用记录
            BlueprintApplication application = null;
            if (!isPreview) {
                application = new BlueprintApplication();
                application.setBlueprintId(blueprintId);
                application.setFactoryId(factoryId);
                application.setAppliedBy(request.getAppliedBy());
                application.setStatus(BlueprintApplication.ApplicationStatus.IN_PROGRESS);
                application = applicationRepository.save(application);
                result.setApplicationId(application.getId());
            }

            // 1. 应用表单模板
            int formTemplatesCreated = applyFormTemplates(blueprint, factoryId, result, isPreview);
            result.setFormTemplatesCreated(formTemplatesCreated);

            // 2. 应用产品类型
            int productTypesCreated = applyProductTypes(blueprint, factoryId, result, isPreview);
            result.setProductTypesCreated(productTypesCreated);

            // 3. 应用规则配置
            int rulesCreated = applyRules(blueprint, factoryId, result, isPreview);
            result.setRulesCreated(rulesCreated);

            // 4. 应用部门结构（预留）
            result.setDepartmentsCreated(0);
            result.addLog("部门结构应用功能待实现");

            // 更新应用状态
            result.setSuccess(true);
            result.setStatus("COMPLETED");
            result.setSummary(String.format(
                    "成功应用蓝图 '%s' 到工厂 %s。创建了 %d 个表单模板，%d 个产品类型，%d 条规则。",
                    blueprint.getName(), factoryId, formTemplatesCreated, productTypesCreated, rulesCreated
            ));

            if (!isPreview && application != null) {
                application.setStatus(BlueprintApplication.ApplicationStatus.COMPLETED);
                application.setResultSummary(result.getSummary());
                applicationRepository.save(application);
            }

        } catch (Exception e) {
            log.error("应用蓝图失败", e);
            result.setSuccess(false);
            result.setStatus("FAILED");
            result.addError("应用蓝图失败: " + e.getMessage());
            result.setSummary("应用蓝图失败");
        }

        return result;
    }

    @Override
    public BlueprintApplicationResult previewBlueprintApplication(String blueprintId, String factoryId) {
        ApplyBlueprintRequest request = new ApplyBlueprintRequest();
        request.setFactoryId(factoryId);
        request.setPreview(true);
        return applyBlueprintToFactory(blueprintId, request);
    }

    @Override
    @Transactional
    public FactoryTypeBlueprint generateBlueprintFromFactory(GenerateBlueprintFromFactoryRequest request) {
        log.info("从工厂 {} 生成蓝图: {}", request.getFactoryId(), request.getBlueprintName());

        try {
            String factoryId = request.getFactoryId();

            // 收集工厂配置
            Map<String, Object> defaultConfig = new HashMap<>();
            defaultConfig.put("sourceFactoryId", factoryId);
            defaultConfig.put("generatedAt", LocalDateTime.now().toString());

            // 创建蓝图
            FactoryTypeBlueprint blueprint = new FactoryTypeBlueprint();
            blueprint.setName(request.getBlueprintName());
            blueprint.setDescription(request.getDescription() != null
                    ? request.getDescription()
                    : "从工厂 " + factoryId + " 生成");
            blueprint.setIndustryType(request.getIndustryType());
            blueprint.setDefaultConfig(objectMapper.writeValueAsString(defaultConfig));

            // 收集表单模板
            if (request.getIncludeFormTemplates()) {
                List<FormTemplate> templates = formTemplateRepository.findByFactoryIdAndDeletedAtIsNull(factoryId);
                String formTemplatesJson = convertFormTemplatesToJSON(templates);
                blueprint.setFormTemplates(formTemplatesJson);
            }

            // 收集产品类型
            if (request.getIncludeProductTypes()) {
                List<ProductType> productTypes = productTypeRepository.findByFactoryIdAndIsActiveTrue(factoryId);
                String productTypesJson = convertProductTypesToJSON(productTypes);
                blueprint.setProductTypeTemplates(productTypesJson);
            }

            // 收集规则配置
            if (request.getIncludeRules()) {
                List<DroolsRule> rules = droolsRuleRepository.findByFactoryIdAndEnabledTrue(factoryId);
                String rulesJson = convertRulesToJSON(rules);
                blueprint.setRuleTemplates(rulesJson);
            }

            blueprint.setIsActive(true);

            return blueprintRepository.save(blueprint);
        } catch (Exception e) {
            log.error("从工厂生成蓝图失败", e);
            throw new RuntimeException("从工厂生成蓝图失败: " + e.getMessage());
        }
    }

    @Override
    public List<BlueprintApplicationResult> getFactoryApplicationHistory(String factoryId) {
        List<BlueprintApplication> applications = applicationRepository.findByFactoryIdAndDeletedAtIsNull(factoryId);

        return applications.stream().map(app -> {
            FactoryTypeBlueprint blueprint = blueprintRepository.findById(app.getBlueprintId()).orElse(null);

            return BlueprintApplicationResult.builder()
                    .applicationId(app.getId())
                    .blueprintId(app.getBlueprintId())
                    .blueprintName(blueprint != null ? blueprint.getName() : "未知蓝图")
                    .factoryId(app.getFactoryId())
                    .status(app.getStatus().name())
                    .appliedAt(app.getAppliedAt())
                    .summary(app.getResultSummary())
                    .build();
        }).collect(Collectors.toList());
    }

    /**
     * 应用表单模板
     */
    private int applyFormTemplates(FactoryTypeBlueprint blueprint, String factoryId,
                                   BlueprintApplicationResult result, boolean isPreview) {
        if (blueprint.getFormTemplates() == null || blueprint.getFormTemplates().isEmpty()) {
            return 0;
        }

        try {
            List<Map<String, Object>> templates = objectMapper.readValue(
                    blueprint.getFormTemplates(),
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            int count = 0;
            for (Map<String, Object> templateData : templates) {
                String name = (String) templateData.get("name");
                String type = (String) templateData.get("type");

                result.addLog(String.format("处理表单模板: %s (%s)", name, type));

                if (!isPreview) {
                    FormTemplate formTemplate = FormTemplate.builder()
                            .factoryId(factoryId)
                            .name(name)
                            .entityType(type)
                            .description("从蓝图 " + blueprint.getName() + " 应用")
                            .schemaJson(objectMapper.writeValueAsString(templateData.get("fields")))
                            .isActive(true)
                            .source("IMPORT")
                            .sourcePackageId(blueprint.getId())
                            .build();

                    formTemplateRepository.save(formTemplate);
                }

                count++;
            }

            result.addLog(String.format("成功应用 %d 个表单模板", count));
            return count;

        } catch (Exception e) {
            log.error("应用表单模板失败", e);
            result.addError("应用表单模板失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 应用产品类型
     */
    private int applyProductTypes(FactoryTypeBlueprint blueprint, String factoryId,
                                  BlueprintApplicationResult result, boolean isPreview) {
        if (blueprint.getProductTypeTemplates() == null || blueprint.getProductTypeTemplates().isEmpty()) {
            return 0;
        }

        try {
            List<Map<String, Object>> templates = objectMapper.readValue(
                    blueprint.getProductTypeTemplates(),
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            int count = 0;
            for (Map<String, Object> productData : templates) {
                String name = (String) productData.get("name");
                String category = (String) productData.get("category");

                result.addLog(String.format("处理产品类型: %s (%s)", name, category));

                if (!isPreview) {
                    ProductType productType = new ProductType();
                    productType.setFactoryId(factoryId);
                    productType.setCode(generateProductCode(factoryId, name));
                    productType.setName(name);
                    productType.setCategory(category);
                    productType.setUnit("kg"); // 默认单位
                    productType.setIsActive(true);
                    productType.setCreatedBy(1L); // 系统用户

                    // 设置加工步骤
                    if (productData.containsKey("processSteps")) {
                        productType.setProcessingSteps(objectMapper.writeValueAsString(productData.get("processSteps")));
                    }

                    productTypeRepository.save(productType);
                }

                count++;
            }

            result.addLog(String.format("成功应用 %d 个产品类型", count));
            return count;

        } catch (Exception e) {
            log.error("应用产品类型失败", e);
            result.addError("应用产品类型失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 应用规则配置
     */
    private int applyRules(FactoryTypeBlueprint blueprint, String factoryId,
                           BlueprintApplicationResult result, boolean isPreview) {
        if (blueprint.getRuleTemplates() == null || blueprint.getRuleTemplates().isEmpty()) {
            result.addLog("蓝图未包含规则配置");
            return 0;
        }

        try {
            List<Map<String, Object>> rules = objectMapper.readValue(
                    blueprint.getRuleTemplates(),
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            int count = 0;
            for (Map<String, Object> ruleData : rules) {
                String ruleName = (String) ruleData.get("name");
                String ruleGroup = (String) ruleData.getOrDefault("group", "validation");
                String ruleContent = (String) ruleData.get("content");

                result.addLog(String.format("处理规则: %s (%s)", ruleName, ruleGroup));

                // 检查是否已存在
                boolean exists = droolsRuleRepository.existsByFactoryIdAndRuleGroupAndRuleName(
                        factoryId, ruleGroup, ruleName);

                if (exists) {
                    result.addLog(String.format("规则已存在，跳过: %s", ruleName));
                    continue;
                }

                if (!isPreview) {
                    DroolsRule droolsRule = DroolsRule.builder()
                            .id(UUID.randomUUID().toString())
                            .factoryId(factoryId)
                            .ruleGroup(ruleGroup)
                            .ruleName(ruleName)
                            .ruleDescription((String) ruleData.get("description"))
                            .ruleContent(ruleContent != null ? ruleContent : generateDefaultRuleContent(ruleName, ruleData))
                            .version(1)
                            .priority(ruleData.containsKey("priority") ?
                                    ((Number) ruleData.get("priority")).intValue() : 0)
                            .enabled(true)
                            .createdBy(1L) // 系统用户
                            .build();

                    droolsRuleRepository.save(droolsRule);
                }

                count++;
            }

            result.addLog(String.format("成功应用 %d 条规则", count));
            return count;

        } catch (Exception e) {
            log.error("应用规则配置失败", e);
            result.addError("应用规则配置失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 生成默认规则内容
     */
    private String generateDefaultRuleContent(String ruleName, Map<String, Object> ruleData) {
        String ruleType = (String) ruleData.getOrDefault("type", "validation");
        Number threshold = (Number) ruleData.get("threshold");

        StringBuilder drl = new StringBuilder();
        drl.append("package com.cretas.rules\n\n");
        drl.append("// 自动生成的规则: ").append(ruleName).append("\n");
        drl.append("rule \"").append(ruleName).append("\"\n");
        drl.append("    when\n");

        if ("temperature".equals(ruleType) && threshold != null) {
            drl.append("        $temp : Number(doubleValue > ").append(threshold).append(")\n");
        } else {
            drl.append("        // 自定义条件\n");
        }

        drl.append("    then\n");
        drl.append("        // 规则动作\n");
        drl.append("        System.out.println(\"Rule triggered: ").append(ruleName).append("\");\n");
        drl.append("end\n");

        return drl.toString();
    }

    /**
     * 转换表单模板为JSON字符串
     */
    private String convertFormTemplatesToJSON(List<FormTemplate> templates) {
        try {
            List<Map<String, Object>> data = templates.stream().map(template -> {
                Map<String, Object> map = new HashMap<>();
                map.put("name", template.getName());
                map.put("type", template.getEntityType());
                map.put("schemaJson", template.getSchemaJson());
                map.put("description", template.getDescription());
                return map;
            }).collect(Collectors.toList());

            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.error("转换表单模板失败", e);
            return "[]";
        }
    }

    /**
     * 转换产品类型为JSON字符串
     */
    private String convertProductTypesToJSON(List<ProductType> productTypes) {
        try {
            List<Map<String, Object>> data = productTypes.stream().map(product -> {
                Map<String, Object> map = new HashMap<>();
                map.put("name", product.getName());
                map.put("category", product.getCategory());
                map.put("code", product.getCode());
                map.put("unit", product.getUnit());
                map.put("processingSteps", product.getProcessingSteps());
                return map;
            }).collect(Collectors.toList());

            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.error("转换产品类型失败", e);
            return "[]";
        }
    }

    /**
     * 转换规则为JSON字符串
     */
    private String convertRulesToJSON(List<DroolsRule> rules) {
        try {
            List<Map<String, Object>> data = rules.stream().map(rule -> {
                Map<String, Object> map = new HashMap<>();
                map.put("name", rule.getRuleName());
                map.put("group", rule.getRuleGroup());
                map.put("description", rule.getRuleDescription());
                map.put("content", rule.getRuleContent());
                map.put("priority", rule.getPriority());
                return map;
            }).collect(Collectors.toList());

            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.error("转换规则失败", e);
            return "[]";
        }
    }

    /**
     * 生成产品编码
     */
    private String generateProductCode(String factoryId, String name) {
        String prefix = factoryId.substring(0, Math.min(3, factoryId.length())).toUpperCase();
        String timestamp = String.valueOf(System.currentTimeMillis() % 10000);
        return prefix + "-" + timestamp;
    }
}
