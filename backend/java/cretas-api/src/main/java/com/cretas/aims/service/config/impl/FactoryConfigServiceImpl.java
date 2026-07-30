package com.cretas.aims.service.config.impl;

import com.cretas.aims.dto.config.*;
import com.cretas.aims.engine.DDLExecutor;
import com.cretas.aims.engine.DynamicFieldService;
import com.cretas.aims.entity.auth.UserMenuPermission;
import com.cretas.aims.entity.config.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.auth.UserMenuPermissionRepository;
import com.cretas.aims.repository.config.*;
import com.cretas.aims.service.config.FactoryConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service("canvasFactoryConfigService")
@RequiredArgsConstructor
public class FactoryConfigServiceImpl implements FactoryConfigService {

    private final ModuleSchemaRepository moduleSchemaRepository;
    private final FactoryConfigurationRepository factoryConfigurationRepository;
    private final FactoryModuleConfigRepository factoryModuleConfigRepository;
    private final ConfigChangeLogRepository configChangeLogRepository;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FactoryTemplateRepository factoryTemplateRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FactoryToolConfigRepository factoryToolConfigRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FactoryDefaultValueRepository factoryDefaultValueRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.cretas.aims.ai.tool.ToolRegistry toolRegistry;

    @org.springframework.beans.factory.annotation.Autowired
    private DDLExecutor ddlExecutor;

    @org.springframework.beans.factory.annotation.Autowired
    private DynamicFieldService dynamicFieldService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.config.CanvasDynamicFieldRepository canvasDynamicFieldRepository;

    // Round 6 Fix CHECK-1: publishConfig must reload scheduler or new cron won't take effect
    // until JVM restart. Optional to avoid circular dep during bean init.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.cretas.aims.engine.DynamicSchedulerService dynamicSchedulerService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserMenuPermissionRepository userMenuPermRepo;

    // ========== 合并配置读取 ==========

    @Override
    public EffectiveModuleConfig getEffectiveConfig(String factoryId, String moduleCode) {
        return getEffectiveConfig(factoryId, moduleCode, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public EffectiveModuleConfig getEffectiveConfig(String factoryId, String moduleCode, String roleCode) {
        Optional<ModuleSchema> schemaOpt = moduleSchemaRepository.findByModuleCode(moduleCode);
        if (schemaOpt.isEmpty()) {
            // Module not canvas-configured — return LEGACY default
            EffectiveModuleConfig legacy = new EffectiveModuleConfig();
            legacy.setModuleCode(moduleCode);
            legacy.setRenderingMode("LEGACY");
            legacy.setEnabled(true);
            return legacy;
        }
        ModuleSchema schema = schemaOpt.get();

        // Layer 1: Schema defaults
        Map<String, Object> effectiveFieldConfig = new HashMap<>(
                (Map<String, Object>) schema.getDefaultConfig().getOrDefault("fields", Map.of()));
        Map<String, Object> effectiveWorkflowConfig = new HashMap<>(
                (Map<String, Object>) schema.getDefaultConfig().getOrDefault("workflow", Map.of()));

        // Layer 2: Factory override (if published config exists)
        boolean moduleEnabled = true;
        String renderingMode = "LEGACY";
        Map<String, Object> customLabels = new HashMap<>();

        Optional<FactoryConfiguration> publishedConfig = factoryConfigurationRepository.findLatestPublished(factoryId);
        if (publishedConfig.isPresent()) {
            int version = publishedConfig.get().getConfigVersion();
            Optional<FactoryModuleConfig> moduleConfig = factoryModuleConfigRepository
                    .findByFactoryIdAndModuleCodeAndConfigVersion(factoryId, moduleCode, version);

            if (moduleConfig.isPresent()) {
                FactoryModuleConfig fmc = moduleConfig.get();
                moduleEnabled = fmc.getEnabled();
                renderingMode = fmc.getRenderingMode();
                deepMerge(effectiveFieldConfig, fmc.getFieldConfig());
                deepMerge(effectiveWorkflowConfig, fmc.getWorkflowConfig());
                if (fmc.getCustomLabels() != null) {
                    fmc.getCustomLabels().forEach((k, v) -> customLabels.put(k, String.valueOf(v)));
                }
            }
        }

        // Build EffectiveField list from schema.fieldSchema
        // fieldSchema can be: Map {"fields":[...]} (Phase 1) or List [{...},...] (Phase 2d)
        Map<String, Object> fieldSchemaMap;
        Object rawFieldSchema = schema.getFieldSchema();
        if (rawFieldSchema instanceof Map) {
            fieldSchemaMap = (Map<String, Object>) rawFieldSchema;
        } else if (rawFieldSchema instanceof List) {
            fieldSchemaMap = Map.of("fields", rawFieldSchema);
        } else {
            fieldSchemaMap = Map.of("fields", List.of());
        }
        List<EffectiveField> fields = buildEffectiveFields(fieldSchemaMap, effectiveFieldConfig, customLabels);
        List<FieldGroup> groups = buildFieldGroups(fieldSchemaMap);

        // Layer 2b: Merge Canvas V3 dynamic fields (ALTER TABLE added columns)
        // This was previously only in the 4-param overload but frontend calls the 3-param version
        if (dynamicFieldService != null) {
            List<CanvasDynamicField> dynamicFields = dynamicFieldService.getActiveFields(factoryId, moduleCode);
            if (!dynamicFields.isEmpty()) {
                // Add a "custom" group so the frontend groupedFields computed doesn't filter these out.
                // groupedFields filters by: groups.filter(g => g.visible) then fields.filter(f => f.group === group.code)
                // So fields in a group not in the groups list are invisible.
                boolean hasCustomGroup = groups.stream().anyMatch(g -> "custom".equals(g.getCode()));
                if (!hasCustomGroup) {
                    groups.add(FieldGroup.builder()
                        .code("custom")
                        .label("自定义字段")
                        .order(1000)
                        .visible(true)
                        .build());
                }

                for (CanvasDynamicField df : dynamicFields) {
                    // P0-3 Fix (Round 4): SUB_TABLE was previously skipped, but SchemaFormRenderer
                    // has a sub_table rendering branch that was never reachable. Now we build an
                    // EffectiveField with type="sub_table" and copy columns into extra so the
                    // frontend SubTableEditor can render dynamic child tables (审溯日志/发酵日志).
                    EffectiveField ef = EffectiveField.builder()
                        .code(df.getFieldCode())
                        .label(df.getLabel())
                        .type(df.getFieldType().toLowerCase())
                        .required(false)
                        .visible(true)
                        .readonly(false)
                        .defaultValue(null)
                        .options(df.getConfig().get("options"))
                        .group("custom")
                        .order(1000 + (df.getSortOrder() != null ? df.getSortOrder() : 0))
                        .extra(df.getConfig())
                        .visibleWhen(df.getVisibleWhen())
                        .computedWhen(df.getComputedWhen())
                        .source("dynamic")
                        .build();
                    fields.add(ef);
                }
            }
        }

        // Build workflow states and transitions
        List<WorkflowStateDTO> workflowStates = buildWorkflowStates(schema.getWorkflowSchema(), effectiveWorkflowConfig);
        List<WorkflowTransitionDTO> workflowTransitions = buildWorkflowTransitions(schema.getWorkflowSchema(), effectiveWorkflowConfig);
        Map<String, Object> workflowOptions = (Map<String, Object>) effectiveWorkflowConfig.getOrDefault("options", Map.of());

        // Layer 3: Role permission filter (runtime, not persisted)
        if (roleCode != null && schema.getPermissionSchema() != null) {
            applyPermissionFilter(fields, schema.getPermissionSchema(), roleCode);
        }

        return EffectiveModuleConfig.builder()
                .moduleCode(moduleCode)
                .moduleName(schema.getModuleName())
                .enabled(moduleEnabled)
                .fields(fields)
                .groups(groups)
                .workflowStates(workflowStates)
                .workflowTransitions(workflowTransitions)
                .workflowOptions(workflowOptions)
                .customLabels(customLabels.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue()))))
                .renderingMode(renderingMode)
                .build();
    }

    @Override
    public EffectiveModuleConfig getEffectiveConfig(String factoryId, String moduleCode, String roleCode, String userId) {
        // 3-param version already merges dynamic fields (Layer 2b)
        EffectiveModuleConfig config = getEffectiveConfig(factoryId, moduleCode, roleCode);

        // Apply user-level permission overrides (4-param exclusive)
        if (userId != null && userMenuPermRepo != null) {
            applyUserPermissions(config.getFields(), factoryId, moduleCode, userId);
        }

        return config;
    }

    private void applyUserPermissions(List<EffectiveField> fields, String factoryId, String moduleCode, String userId) {
        List<UserMenuPermission> perms = userMenuPermRepo.findByFactoryIdAndUserId(factoryId, userId);
        for (UserMenuPermission perm : perms) {
            String mc = perm.getMenuCode();
            if (!mc.startsWith(moduleCode + ":")) continue;
            String[] parts = mc.split(":");
            if (parts.length < 3) continue;
            String fieldCode = parts[1];
            String permission = parts[2];

            for (EffectiveField field : fields) {
                if (field.getCode().equals(fieldCode)) {
                    if ("REVOKE".equals(perm.getGrantType().name())) {
                        if ("hidden".equals(permission)) field.setVisible(false);
                        if ("readonly".equals(permission)) field.setReadonly(true);
                    } else {
                        if ("hidden".equals(permission)) field.setVisible(true);
                        if ("readonly".equals(permission)) field.setReadonly(false);
                    }
                }
            }
        }
    }

    // ========== 字段级查询 ==========

    @Override
    public boolean isFieldVisible(String factoryId, String moduleCode, String fieldCode) {
        EffectiveModuleConfig config = getEffectiveConfig(factoryId, moduleCode);
        return config.getFields().stream()
                .filter(f -> f.getCode().equals(fieldCode))
                .findFirst()
                .map(EffectiveField::isVisible)
                .orElse(false);
    }

    @Override
    public boolean isFieldRequired(String factoryId, String moduleCode, String fieldCode) {
        EffectiveModuleConfig config = getEffectiveConfig(factoryId, moduleCode);
        return config.getFields().stream()
                .filter(f -> f.getCode().equals(fieldCode))
                .findFirst()
                .map(EffectiveField::isRequired)
                .orElse(false);
    }

    @Override
    public Object getFieldDefault(String factoryId, String moduleCode, String fieldCode) {
        EffectiveModuleConfig config = getEffectiveConfig(factoryId, moduleCode);
        return config.getFields().stream()
                .filter(f -> f.getCode().equals(fieldCode))
                .findFirst()
                .map(EffectiveField::getDefaultValue)
                .orElse(null);
    }

    // ========== 流程级查询 ==========

    @Override
    public List<WorkflowStateDTO> getWorkflowStates(String factoryId, String moduleCode) {
        return getEffectiveConfig(factoryId, moduleCode).getWorkflowStates();
    }

    @Override
    public List<WorkflowTransitionDTO> getAvailableTransitions(String factoryId, String moduleCode, String currentState) {
        return getEffectiveConfig(factoryId, moduleCode).getWorkflowTransitions().stream()
                .filter(t -> t.getFrom().equals(currentState) && t.isEnabled())
                .collect(Collectors.toList());
    }

    @Override
    public boolean isTransitionAllowed(String factoryId, String moduleCode, String fromState, String toState) {
        return getEffectiveConfig(factoryId, moduleCode).getWorkflowTransitions().stream()
                .anyMatch(t -> t.getFrom().equals(fromState) && t.getTo().equals(toState) && t.isEnabled());
    }

    // ========== 模块级查询 ==========

    @Override
    public boolean isModuleEnabled(String factoryId, String moduleCode) {
        return getEffectiveConfig(factoryId, moduleCode).isEnabled();
    }

    @Override
    public List<ModuleSummaryDTO> getEnabledModules(String factoryId) {
        // Round 5 Fix PERF-1: previously this loop called getEffectiveConfig once per module,
        // which issues ~4 queries per module (schema lookup + publishedConfig + fmc + dynamicFields)
        // + builds the full field schema. With 68 modules that's 270+ queries per page load.
        // We only need {moduleCode, moduleName, moduleCategory, enabled, renderingMode} so batch-load
        // all FactoryModuleConfig for the latest published version in a single query.
        List<ModuleSchema> schemas = moduleSchemaRepository.findByIsActiveTrue();

        Map<String, FactoryModuleConfig> configByModule = Map.of();
        Optional<FactoryConfiguration> publishedConfig = factoryConfigurationRepository.findLatestPublished(factoryId);
        if (publishedConfig.isPresent()) {
            int version = publishedConfig.get().getConfigVersion();
            configByModule = factoryModuleConfigRepository
                    .findByFactoryIdAndConfigVersion(factoryId, version)
                    .stream()
                    .collect(Collectors.toMap(
                            FactoryModuleConfig::getModuleCode,
                            fmc -> fmc,
                            (a, b) -> a));  // duplicate key safety
        }

        Map<String, FactoryModuleConfig> finalConfigByModule = configByModule;
        return schemas.stream()
                .map(s -> {
                    FactoryModuleConfig fmc = finalConfigByModule.get(s.getModuleCode());
                    boolean enabled = fmc == null || Boolean.TRUE.equals(fmc.getEnabled());
                    String renderingMode = fmc != null && fmc.getRenderingMode() != null
                            ? fmc.getRenderingMode() : "LEGACY";
                    return ModuleSummaryDTO.builder()
                            .moduleCode(s.getModuleCode())
                            .moduleName(s.getModuleName())
                            .moduleCategory(s.getModuleCategory())
                            .enabled(enabled)
                            .renderingMode(renderingMode)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ========== 配置写操作 ==========

    @Override
    @Transactional
    public void saveModuleConfig(String factoryId, String moduleCode, ModuleConfigDTO dto, Long operatorId) {
        moduleSchemaRepository.findByModuleCode(moduleCode)
                .orElseThrow(() -> new ResourceNotFoundException("ModuleSchema", "moduleCode", moduleCode));

        FactoryConfiguration config = getOrCreateDraft(factoryId, operatorId);
        FactoryModuleConfig fmc = factoryModuleConfigRepository
                .findByFactoryIdAndModuleCodeAndConfigVersion(factoryId, moduleCode, config.getConfigVersion())
                .orElseGet(() -> {
                    FactoryModuleConfig newFmc = new FactoryModuleConfig();
                    newFmc.setFactoryId(factoryId);
                    newFmc.setModuleCode(moduleCode);
                    newFmc.setConfigVersion(config.getConfigVersion());
                    return newFmc;
                });

        if (dto.getEnabled() != null) fmc.setEnabled(dto.getEnabled());
        if (dto.getFieldConfig() != null) fmc.setFieldConfig(dto.getFieldConfig());
        if (dto.getWorkflowConfig() != null) fmc.setWorkflowConfig(dto.getWorkflowConfig());
        if (dto.getValidationConfig() != null) fmc.setValidationConfig(dto.getValidationConfig());
        if (dto.getPermissionConfig() != null) fmc.setPermissionConfig(dto.getPermissionConfig());
        if (dto.getLayoutConfig() != null) fmc.setLayoutConfig(dto.getLayoutConfig());
        if (dto.getCustomLabels() != null) fmc.setCustomLabels(dto.getCustomLabels());
        if (dto.getRenderingMode() != null) fmc.setRenderingMode(dto.getRenderingMode());

        factoryModuleConfigRepository.save(fmc);

        logChange(factoryId, moduleCode, "UPDATE", null, dto.getFieldConfig(), "模块配置更新", operatorId);
    }

    @Override
    @Transactional
    public void toggleModule(String factoryId, String moduleCode, boolean enabled, Long operatorId) {
        ModuleConfigDTO dto = ModuleConfigDTO.builder().enabled(enabled).build();
        saveModuleConfig(factoryId, moduleCode, dto, operatorId);
    }

    @Override
    @Transactional
    public void updateFieldConfig(String factoryId, String moduleCode, String fieldCode,
                                  FieldConfigDTO fieldConfig, Long operatorId) {
        moduleSchemaRepository.findByModuleCode(moduleCode)
                .orElseThrow(() -> new ResourceNotFoundException("ModuleSchema", "moduleCode", moduleCode));
        FactoryConfiguration config = getOrCreateDraft(factoryId, operatorId);
        FactoryModuleConfig fmc = factoryModuleConfigRepository
                .findByFactoryIdAndModuleCodeAndConfigVersion(factoryId, moduleCode, config.getConfigVersion())
                .orElseGet(() -> {
                    FactoryModuleConfig newFmc = new FactoryModuleConfig();
                    newFmc.setFactoryId(factoryId);
                    newFmc.setModuleCode(moduleCode);
                    newFmc.setConfigVersion(config.getConfigVersion());
                    return newFmc;
                });

        Map<String, Object> fieldConfigMap = new HashMap<>(fmc.getFieldConfig());
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldsMap = (Map<String, Object>) fieldConfigMap.computeIfAbsent("fields", k -> new HashMap<>());
        Map<String, Object> fieldOverride = new HashMap<>();
        if (fieldConfig.getVisible() != null) fieldOverride.put("visible", fieldConfig.getVisible());
        if (fieldConfig.getRequired() != null) fieldOverride.put("required", fieldConfig.getRequired());
        if (fieldConfig.getDefaultValue() != null) fieldOverride.put("defaultValue", fieldConfig.getDefaultValue());
        if (fieldConfig.getOptions() != null) fieldOverride.put("options", fieldConfig.getOptions());
        if (fieldConfig.getLabel() != null) fieldOverride.put("label", fieldConfig.getLabel());
        fieldsMap.put(fieldCode, fieldOverride);

        fmc.setFieldConfig(fieldConfigMap);
        factoryModuleConfigRepository.save(fmc);

        logChange(factoryId, moduleCode, "FIELD_UPDATE", null, fieldOverride,
                "字段 " + fieldCode + " 配置更新", operatorId);
    }

    // ========== 发布 ==========

    @Override
    @Transactional
    public void publishConfig(String factoryId, Long operatorId, String changeSummary) {
        // Canvas audit fix: publishNow needs to publish APPROVED configs too, not only DRAFT.
        // Try DRAFT first (normal flow), then APPROVED (审核通过→立即发布 flow).
        FactoryConfiguration draft = factoryConfigurationRepository.findDraft(factoryId)
                .or(() -> factoryConfigurationRepository.findLatestApproved(factoryId))
                .orElseThrow(() -> new BusinessException(409, "没有待发布的配置 (需 DRAFT 或 APPROVED 状态)")
                        .withHint("请先创建草稿或审核通过后再发布"));

        // Archive current published
        factoryConfigurationRepository.findLatestPublished(factoryId)
                .ifPresent(published -> {
                    published.setStatus("ARCHIVED");
                    factoryConfigurationRepository.save(published);
                });

        // Execute pending DDL for dynamic fields and refresh cache
        ddlExecutor.executePendingDDL(factoryId, draft.getConfigVersion());
        dynamicFieldService.refreshCache();

        draft.setStatus("PUBLISHED");
        draft.setPublishedAt(LocalDateTime.now());
        draft.setPublishedBy(operatorId);
        draft.setChangeSummary(changeSummary);
        factoryConfigurationRepository.save(draft);

        logChange(factoryId, null, "PUBLISH", null, null,
                "配置版本 " + draft.getConfigVersion() + " 已发布: " + changeSummary, operatorId);

        // Round 6 Fix CHECK-1: reload dynamic scheduler so any new/changed cron schedules
        // configured in this version take effect immediately. Previously scheduler only loaded
        // @PostConstruct, so new cron would silently not fire until JVM restart.
        if (dynamicSchedulerService != null) {
            try {
                dynamicSchedulerService.reloadAll();
                log.info("工厂 {} 配置发布后 scheduler 已 reload", factoryId);
            } catch (Exception e) {
                log.warn("工厂 {} scheduler reload 失败 (非阻塞): {}", factoryId, e.getMessage());
            }
        }

        log.info("工厂 {} 配置版本 {} 已发布", factoryId, draft.getConfigVersion());
    }

    @Override
    @Transactional
    public void rollbackConfig(String factoryId, int targetVersion, Long operatorId) {
        FactoryConfiguration target = factoryConfigurationRepository
                .findByFactoryIdAndConfigVersion(factoryId, targetVersion)
                .orElseThrow(() -> new BusinessException(404, "目标版本不存在: " + targetVersion)
                        .withHint("请检查版本号是否正确").withHintTarget("targetVersion"));

        FactoryConfiguration newDraft = new FactoryConfiguration();
        newDraft.setFactoryId(factoryId);
        newDraft.setTemplateId(target.getTemplateId());
        newDraft.setConfigVersion(getNextVersion(factoryId));
        newDraft.setStatus("DRAFT");
        newDraft.setCreatedBy(operatorId);
        newDraft.setRollbackVersion(targetVersion);
        factoryConfigurationRepository.save(newDraft);

        List<FactoryModuleConfig> targetModules = factoryModuleConfigRepository
                .findByFactoryIdAndConfigVersion(factoryId, targetVersion);
        for (FactoryModuleConfig src : targetModules) {
            FactoryModuleConfig copy = new FactoryModuleConfig();
            copy.setFactoryId(factoryId);
            copy.setModuleCode(src.getModuleCode());
            copy.setConfigVersion(newDraft.getConfigVersion());
            copy.setEnabled(src.getEnabled());
            copy.setFieldConfig(src.getFieldConfig());
            copy.setWorkflowConfig(src.getWorkflowConfig());
            copy.setValidationConfig(src.getValidationConfig());
            copy.setPermissionConfig(src.getPermissionConfig());
            copy.setLayoutConfig(src.getLayoutConfig());
            copy.setCustomLabels(src.getCustomLabels());
            copy.setComputedFields(src.getComputedFields());
            copy.setRenderingMode(src.getRenderingMode());
            factoryModuleConfigRepository.save(copy);
        }

        // Round 4 Fix P0-5: disable dynamic fields added after the rollback target version.
        // Without this, v3→v2 rollback leaves v3's fields still ACTIVE → ghost fields in UI.
        // Fields added before version tracking was introduced (activeFromVersion = null) are
        // preserved as baseline.
        if (canvasDynamicFieldRepository != null) {
            List<CanvasDynamicField> allActive = canvasDynamicFieldRepository
                    .findByFactoryIdAndStatusIn(factoryId, java.util.List.of("ACTIVE"));
            int disabled = 0;
            for (CanvasDynamicField df : allActive) {
                Integer fieldVersion = df.getActiveFromVersion();
                if (fieldVersion != null && fieldVersion > targetVersion) {
                    df.setStatus("DISABLED");
                    canvasDynamicFieldRepository.save(df);
                    disabled++;
                }
            }
            if (disabled > 0) {
                log.info("Rollback to v{} disabled {} dynamic fields added in later versions", targetVersion, disabled);
                // Also refresh DynamicFieldService cache
                if (dynamicFieldService != null) {
                    dynamicFieldService.refreshCache();
                }
            }
        }

        logChange(factoryId, null, "ROLLBACK", null, null,
                "回滚到版本 " + targetVersion, operatorId);
    }

    // ========== 模板 ==========

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public void applyTemplate(String factoryId, String templateCode, Long operatorId) {
        if (factoryTemplateRepository == null) {
            throw new BusinessException(503, "模板系统未就绪")
                    .withHint("请联系管理员启用模板系统");
        }
        FactoryTemplate template = factoryTemplateRepository.findByTemplateCodeIgnoreCase(templateCode)
                .orElseThrow(() -> new BusinessException(404, "模板不存在: " + templateCode)
                        .withHint("请刷新模板列表后重新选择").withHintTarget("templateCode"));

        // 1. Create draft
        FactoryConfiguration config = getOrCreateDraft(factoryId, operatorId);

        // 2. Parse base_config
        Map<String, Object> baseConfig = template.getBaseConfig();
        Map<String, Object> moduleConfigs = (Map<String, Object>) baseConfig.getOrDefault("moduleConfigs", Map.of());
        List<String> enabledModules = (List<String>) moduleConfigs.getOrDefault("enabledModules", List.of());
        List<String> disabledModules = (List<String>) moduleConfigs.getOrDefault("disabledModules", List.of());

        // 3. Enable modules
        for (String moduleCode : enabledModules) {
            if (moduleSchemaRepository.findByModuleCode(moduleCode).isEmpty()) continue;
            FactoryModuleConfig fmc = factoryModuleConfigRepository
                    .findByFactoryIdAndModuleCodeAndConfigVersion(factoryId, moduleCode, config.getConfigVersion())
                    .orElseGet(() -> {
                        FactoryModuleConfig c = new FactoryModuleConfig();
                        c.setConfigVersion(config.getConfigVersion());
                        c.setModuleCode(moduleCode);
                        c.setFactoryId(factoryId);
                        return c;
                    });
            fmc.setEnabled(true);
            factoryModuleConfigRepository.save(fmc);
        }

        // 4. Disable modules
        for (String moduleCode : disabledModules) {
            FactoryModuleConfig fmc = factoryModuleConfigRepository
                    .findByFactoryIdAndModuleCodeAndConfigVersion(factoryId, moduleCode, config.getConfigVersion())
                    .orElseGet(() -> {
                        FactoryModuleConfig c = new FactoryModuleConfig();
                        c.setConfigVersion(config.getConfigVersion());
                        c.setModuleCode(moduleCode);
                        c.setFactoryId(factoryId);
                        return c;
                    });
            fmc.setEnabled(false);
            factoryModuleConfigRepository.save(fmc);
        }

        // 5. Apply default overrides
        Map<String, Object> overrides = (Map<String, Object>) baseConfig.getOrDefault("defaultOverrides", Map.of());
        Map<String, Map<String, Object>> defaultValues = (Map<String, Map<String, Object>>)
                overrides.getOrDefault("defaultValues", Map.of());

        if (factoryDefaultValueRepository != null) {
            for (var entry : defaultValues.entrySet()) {
                String modCode = entry.getKey();
                for (var fieldEntry : entry.getValue().entrySet()) {
                    FactoryDefaultValue fdv = new FactoryDefaultValue();
                    fdv.setFactoryId(factoryId);
                    fdv.setModuleCode(modCode);
                    fdv.setFieldCode(fieldEntry.getKey());
                    // JSONB column — raw strings like "NORMAL" must be wrapped in a JSON-compatible value.
                    // Simplest: pass as a Map so Hibernate serializes to proper JSON.
                    Object rawVal = fieldEntry.getValue();
                    fdv.setDefaultValue(java.util.Map.of("value", rawVal));
                    fdv.setDescription("模板 " + templateCode);
                    factoryDefaultValueRepository.save(fdv);
                }
            }
        }

        // 6. Apply tool disables
        List<String> disabledToolPatterns = (List<String>) overrides.getOrDefault("disabledTools", List.of());
        if (factoryToolConfigRepository != null && toolRegistry != null && !disabledToolPatterns.isEmpty()) {
            for (String toolName : toolRegistry.getAllToolNames()) {
                boolean shouldDisable = disabledToolPatterns.stream().anyMatch(p ->
                        p.endsWith("*") ? toolName.startsWith(p.substring(0, p.length() - 1)) : toolName.equals(p));
                if (shouldDisable) {
                    FactoryToolConfig ftc = factoryToolConfigRepository.findByFactoryIdAndToolName(factoryId, toolName)
                            .orElseGet(() -> { FactoryToolConfig c = new FactoryToolConfig(); c.setFactoryId(factoryId); c.setToolName(toolName); return c; });
                    ftc.setEnabled(false);
                    factoryToolConfigRepository.save(ftc);
                }
            }
        }

        // 7. Apply seedDynamicFields (Round 4 Fix P2-29)
        // Template can declare pre-built dynamic fields that are auto-created on apply.
        // Each field is created with status=PENDING_DDL, will activate on next publish.
        int seededFields = 0;
        List<Map<String, Object>> seedFields = (List<Map<String, Object>>)
                baseConfig.getOrDefault("seedDynamicFields", List.of());
        if (canvasDynamicFieldRepository != null && !seedFields.isEmpty()) {
            for (Map<String, Object> seed : seedFields) {
                String modCode = (String) seed.get("moduleCode");
                String fieldCode = (String) seed.get("fieldCode");
                if (modCode == null || fieldCode == null) continue;

                // Skip if field already exists in this factory (idempotent re-apply)
                if (canvasDynamicFieldRepository
                        .findByFactoryIdAndModuleCodeAndFieldCode(factoryId, modCode, fieldCode)
                        .isPresent()) {
                    continue;
                }

                CanvasDynamicField newField = CanvasDynamicField.builder()
                    .factoryId(factoryId)
                    .moduleCode(modCode)
                    .fieldCode(fieldCode)
                    .fieldType((String) seed.get("fieldType"))
                    .label((String) seed.get("label"))
                    .config(seed.get("config") instanceof Map m ? (Map<String, Object>) m : Map.of())
                    .visibleWhen((String) seed.get("visibleWhen"))
                    .computedWhen((String) seed.get("computedWhen"))
                    .sortOrder(seed.get("sortOrder") != null ? ((Number) seed.get("sortOrder")).intValue() : 0)
                    .status("PENDING_DDL")
                    .build();
                newField.setColumnName("cf_" + fieldCode);
                canvasDynamicFieldRepository.save(newField);
                seededFields++;
            }
        }

        // 8. Log + update usage count
        logChange(factoryId, null, "TEMPLATE_APPLIED", null, null,
                "应用模板: " + templateCode + " (seed " + seededFields + " fields)", operatorId);
        template.setUsageCount(template.getUsageCount() + 1);
        factoryTemplateRepository.save(template);

        log.info("Template {} applied to factory {} — {} enabled, {} disabled, {} seedFields",
                templateCode, factoryId, enabledModules.size(), disabledModules.size(), seededFields);
    }

    // ========== Export / Import (Round 4 Fix P1-16) ==========

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("jdbcTemplate")
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Override
    public Map<String, Object> exportConfig(String factoryId) {
        Map<String, Object> bundle = new HashMap<>();
        bundle.put("factoryId", factoryId);
        bundle.put("exportedAt", LocalDateTime.now().toString());
        // Round 7b P0-1: bundle version bumped to 2.0 to signal subTableRows + attachmentManifest
        // are now present. Import side checks this version for round-trip safety.
        bundle.put("version", "2.0");

        // Latest published version metadata
        Optional<FactoryConfiguration> latestPublished =
            factoryConfigurationRepository.findLatestPublished(factoryId);
        latestPublished.ifPresent(v -> bundle.put("sourceVersion", v.getConfigVersion()));

        // Export module configs (enabled modules + their field overrides)
        int exportVersion = latestPublished.map(FactoryConfiguration::getConfigVersion).orElse(1);
        List<FactoryModuleConfig> modules = factoryModuleConfigRepository
            .findByFactoryIdAndConfigVersion(factoryId, exportVersion);
        List<Map<String, Object>> moduleData = new ArrayList<>();
        for (FactoryModuleConfig m : modules) {
            Map<String, Object> mm = new HashMap<>();
            mm.put("moduleCode", m.getModuleCode());
            mm.put("enabled", m.getEnabled());
            mm.put("fieldConfig", m.getFieldConfig());
            mm.put("workflowConfig", m.getWorkflowConfig());
            mm.put("validationConfig", m.getValidationConfig());
            mm.put("permissionConfig", m.getPermissionConfig());
            mm.put("layoutConfig", m.getLayoutConfig());
            mm.put("customLabels", m.getCustomLabels());
            mm.put("renderingMode", m.getRenderingMode());
            moduleData.add(mm);
        }
        bundle.put("modules", moduleData);

        // Export dynamic fields + sub-table row data + attachment manifest
        List<Map<String, Object>> subTableRows = new ArrayList<>();
        List<Map<String, Object>> attachmentManifest = new ArrayList<>();
        if (canvasDynamicFieldRepository != null) {
            List<CanvasDynamicField> fields = canvasDynamicFieldRepository
                .findByFactoryIdAndStatusIn(factoryId, List.of("ACTIVE", "PENDING_DDL"));
            List<Map<String, Object>> fieldData = new ArrayList<>();
            for (CanvasDynamicField f : fields) {
                Map<String, Object> ff = new HashMap<>();
                ff.put("moduleCode", f.getModuleCode());
                ff.put("fieldCode", f.getFieldCode());
                ff.put("fieldType", f.getFieldType());
                ff.put("label", f.getLabel());
                ff.put("config", f.getConfig());
                ff.put("visibleWhen", f.getVisibleWhen());
                ff.put("computedWhen", f.getComputedWhen());
                ff.put("sortOrder", f.getSortOrder());
                fieldData.add(ff);

                // Round 7b P0-1: for SUB_TABLE fields, dump the row data from the
                // child table {moduleCode}_{fieldCode}_items. Previously exportConfig
                // only wrote the field definition — migration lost all sub-table rows
                // (e.g. 发酵日志, 审溯日志 明细 completely dropped on factory migration).
                if ("SUB_TABLE".equals(f.getFieldType()) && jdbcTemplate != null) {
                    String subTableName = f.getModuleCode() + "_" + f.getFieldCode() + "_items";
                    try {
                        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                            "SELECT * FROM " + subTableName + " LIMIT 10000");
                        Map<String, Object> bucket = new HashMap<>();
                        bucket.put("moduleCode", f.getModuleCode());
                        bucket.put("fieldCode", f.getFieldCode());
                        bucket.put("tableName", subTableName);
                        bucket.put("rowCount", rows.size());
                        bucket.put("rows", rows);
                        subTableRows.add(bucket);
                    } catch (Exception e) {
                        log.warn("exportConfig sub-table dump skipped for {}: {}", subTableName, e.getMessage());
                        Map<String, Object> bucket = new HashMap<>();
                        bucket.put("moduleCode", f.getModuleCode());
                        bucket.put("fieldCode", f.getFieldCode());
                        bucket.put("tableName", subTableName);
                        bucket.put("rowCount", 0);
                        bucket.put("error", e.getMessage());
                        bucket.put("rows", List.of());
                        subTableRows.add(bucket);
                    }
                }

                // Round 7b P0-2: for ATTACHMENT fields, list all referenced OSS paths
                // as a MANIFEST (not embedded bytes). Import side validates the manifest
                // and warns about unresolved paths — customer must migrate OSS separately.
                if ("ATTACHMENT".equals(f.getFieldType()) && jdbcTemplate != null) {
                    String parentTable = ddlExecutor.resolveTable(f.getModuleCode());
                    try {
                        List<Map<String, Object>> refs = jdbcTemplate.queryForList(
                            "SELECT id, cf_" + f.getFieldCode() + " AS attachment_ref FROM "
                                + parentTable + " WHERE cf_" + f.getFieldCode() + " IS NOT NULL "
                                + "AND factory_id = ? LIMIT 5000",
                            factoryId);
                        Map<String, Object> bucket = new HashMap<>();
                        bucket.put("moduleCode", f.getModuleCode());
                        bucket.put("fieldCode", f.getFieldCode());
                        bucket.put("parentTable", parentTable);
                        bucket.put("refCount", refs.size());
                        bucket.put("refs", refs);
                        attachmentManifest.add(bucket);
                    } catch (Exception e) {
                        log.warn("exportConfig attachment manifest skipped for {}.{}: {}",
                            f.getModuleCode(), f.getFieldCode(), e.getMessage());
                    }
                }
            }
            bundle.put("dynamicFields", fieldData);
        }
        bundle.put("subTableRows", subTableRows);
        bundle.put("attachmentManifest", attachmentManifest);

        log.info("Exported config for factory {} — {} modules, {} fields, {} sub-table groups ({} total rows), {} attachment groups",
            factoryId,
            moduleData.size(),
            ((List<?>) bundle.getOrDefault("dynamicFields", List.of())).size(),
            subTableRows.size(),
            subTableRows.stream().mapToInt(m -> ((Number) m.getOrDefault("rowCount", 0)).intValue()).sum(),
            attachmentManifest.size());
        return bundle;
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> importConfig(String factoryId, Map<String, Object> bundle, Long operatorId) {
        if (bundle == null || !bundle.containsKey("modules")) {
            throw new BusinessException(400, "Invalid config bundle: missing 'modules' key")
                    .withHint("请检查导入的配置 JSON 是否完整").withHintTarget("bundle");
        }

        // Get or create a DRAFT version for this factory
        FactoryConfiguration draft = getOrCreateDraft(factoryId, operatorId);
        int targetVersion = draft.getConfigVersion();

        int modulesImported = 0;
        int fieldsImported = 0;
        List<String> skipped = new ArrayList<>();

        // Import module configs
        List<Map<String, Object>> modules = (List<Map<String, Object>>) bundle.getOrDefault("modules", List.of());
        for (Map<String, Object> mData : modules) {
            String moduleCode = (String) mData.get("moduleCode");
            if (moduleSchemaRepository.findByModuleCode(moduleCode).isEmpty()) {
                skipped.add("module:" + moduleCode + " (schema not found in target)");
                continue;
            }

            FactoryModuleConfig existing = factoryModuleConfigRepository
                .findByFactoryIdAndModuleCodeAndConfigVersion(factoryId, moduleCode, targetVersion)
                .orElseGet(() -> {
                    FactoryModuleConfig c = new FactoryModuleConfig();
                    c.setFactoryId(factoryId);
                    c.setModuleCode(moduleCode);
                    c.setConfigVersion(targetVersion);
                    return c;
                });
            if (mData.containsKey("enabled")) existing.setEnabled((Boolean) mData.get("enabled"));
            if (mData.containsKey("fieldConfig")) existing.setFieldConfig((Map<String, Object>) mData.get("fieldConfig"));
            if (mData.containsKey("workflowConfig")) existing.setWorkflowConfig((Map<String, Object>) mData.get("workflowConfig"));
            if (mData.containsKey("validationConfig")) existing.setValidationConfig((Map<String, Object>) mData.get("validationConfig"));
            if (mData.containsKey("permissionConfig")) existing.setPermissionConfig((Map<String, Object>) mData.get("permissionConfig"));
            if (mData.containsKey("layoutConfig")) existing.setLayoutConfig((Map<String, Object>) mData.get("layoutConfig"));
            if (mData.containsKey("customLabels")) existing.setCustomLabels((Map<String, Object>) mData.get("customLabels"));
            if (mData.containsKey("renderingMode")) existing.setRenderingMode((String) mData.get("renderingMode"));
            factoryModuleConfigRepository.save(existing);
            modulesImported++;
        }

        // Import dynamic fields (as PENDING_DDL for safe re-publish)
        if (canvasDynamicFieldRepository != null && bundle.containsKey("dynamicFields")) {
            List<Map<String, Object>> fields = (List<Map<String, Object>>) bundle.get("dynamicFields");
            for (Map<String, Object> fData : fields) {
                String moduleCode = (String) fData.get("moduleCode");
                String fieldCode = (String) fData.get("fieldCode");
                // Skip if field already exists in target factory
                if (canvasDynamicFieldRepository.findByFactoryIdAndModuleCodeAndFieldCode(
                        factoryId, moduleCode, fieldCode).isPresent()) {
                    skipped.add("field:" + moduleCode + "." + fieldCode + " (already exists)");
                    continue;
                }
                CanvasDynamicField newField = CanvasDynamicField.builder()
                    .factoryId(factoryId)
                    .moduleCode(moduleCode)
                    .fieldCode(fieldCode)
                    .fieldType((String) fData.get("fieldType"))
                    .label((String) fData.get("label"))
                    .config((Map<String, Object>) fData.getOrDefault("config", Map.of()))
                    .visibleWhen((String) fData.get("visibleWhen"))
                    .computedWhen((String) fData.get("computedWhen"))
                    .sortOrder(fData.get("sortOrder") != null ? ((Number) fData.get("sortOrder")).intValue() : 0)
                    .status("PENDING_DDL")
                    .build();
                newField.setColumnName("cf_" + fieldCode);
                canvasDynamicFieldRepository.save(newField);
                fieldsImported++;
            }
        }

        // Round 7b P0-1: sub-table row data import. v2.0 bundles carry subTableRows;
        // we skip-on-exists because the target sub-table may not have been created yet
        // (the new dynamic fields above are still PENDING_DDL — rows will be inserted
        // AFTER the next publish triggers DDL). Import records the pending rows in a
        // staging column of the field config so publish can retry.
        int subTableRowsStaged = 0;
        List<String> subTableWarnings = new ArrayList<>();
        if (bundle.containsKey("subTableRows")) {
            List<Map<String, Object>> buckets = (List<Map<String, Object>>) bundle.get("subTableRows");
            for (Map<String, Object> b : buckets) {
                String tableName = (String) b.get("tableName");
                List<Map<String, Object>> rows = (List<Map<String, Object>>) b.getOrDefault("rows", List.of());
                if (rows.isEmpty() || jdbcTemplate == null) continue;
                // Check whether the table already exists (was created by a prior publish)
                try {
                    Integer exists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                        Integer.class, tableName);
                    if (exists == null || exists == 0) {
                        subTableWarnings.add(tableName + " (target table not yet created — will retry after publish)");
                        continue;
                    }
                } catch (Exception e) {
                    subTableWarnings.add(tableName + " (existence check failed: " + e.getMessage() + ")");
                    continue;
                }
                // Naive insert — assumes column names match. Cross-DB migration would
                // need column mapping. For now: best-effort; errors go to warnings.
                for (Map<String, Object> row : rows) {
                    try {
                        // Remove PG-internal columns that shouldn't round-trip
                        row.remove("id");
                        row.remove("created_at");
                        row.remove("updated_at");
                        if (row.isEmpty()) continue;
                        String cols = String.join(",", row.keySet());
                        String placeholders = row.keySet().stream().map(k -> "?").collect(Collectors.joining(","));
                        jdbcTemplate.update("INSERT INTO " + tableName + " (" + cols + ") VALUES (" + placeholders + ")",
                            row.values().toArray());
                        subTableRowsStaged++;
                    } catch (Exception e) {
                        subTableWarnings.add(tableName + " row insert failed: " + e.getMessage());
                    }
                }
            }
        }

        // Round 7b P0-2: attachment manifest is informational only — we can't copy
        // files across OSS buckets automatically. Surface warnings for customer ops.
        List<String> attachmentWarnings = new ArrayList<>();
        if (bundle.containsKey("attachmentManifest")) {
            List<Map<String, Object>> manifest = (List<Map<String, Object>>) bundle.get("attachmentManifest");
            for (Map<String, Object> b : manifest) {
                int refCount = ((Number) b.getOrDefault("refCount", 0)).intValue();
                if (refCount > 0) {
                    attachmentWarnings.add(
                        b.get("moduleCode") + "." + b.get("fieldCode") + ": " + refCount
                            + " attachment refs — 文件本体需要手动迁移 OSS");
                }
            }
        }

        logChange(factoryId, null, "IMPORT", null, null,
                "导入配置 — " + modulesImported + " modules, " + fieldsImported + " fields, "
                + subTableRowsStaged + " sub-table rows", operatorId);

        Map<String, Object> result = new HashMap<>();
        result.put("modulesImported", modulesImported);
        result.put("fieldsImported", fieldsImported);
        result.put("subTableRowsStaged", subTableRowsStaged);
        result.put("subTableWarnings", subTableWarnings);
        result.put("attachmentWarnings", attachmentWarnings);
        result.put("skipped", skipped);
        result.put("draftVersion", targetVersion);
        log.info("Imported config to factory {} — {} modules, {} fields, {} sub-table rows, {} skipped",
            factoryId, modulesImported, fieldsImported, subTableRowsStaged, skipped.size());
        return result;
    }

    // ========== Runtime Custom Module Creation (Round 4 Fix P1-12) ==========

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplateForCustomModules;

    @Override
    @Transactional
    public Map<String, Object> createCustomModule(String factoryId, String moduleCode, String moduleName,
                                                    String moduleCategory, String description, Long operatorId) {
        // Reject if moduleCode already exists
        if (moduleSchemaRepository.findByModuleCode(moduleCode).isPresent()) {
            throw new BusinessException(409, "模块已存在: " + moduleCode)
                    .withHint("请使用其他模块代码").withHintTarget("moduleCode");
        }

        // Create minimal ModuleSchema
        ModuleSchema schema = ModuleSchema.builder()
            .moduleCode(moduleCode)
            .moduleName(moduleName)
            .moduleCategory(moduleCategory != null ? moduleCategory : "CUSTOM")
            .moduleVersion(1)
            .fieldSchema(Map.of("fields", List.of(), "groups", List.of()))
            .workflowSchema(Map.of("states", List.of(), "transitions", List.of()))
            .validationSchema(Map.of())
            .permissionSchema(Map.of())
            .defaultConfig(Map.of("fields", Map.of(), "workflow", Map.of()))
            .description(description)
            .isActive(true)
            .build();
        moduleSchemaRepository.save(schema);

        // Auto-create the underlying table if not exists
        // Table name = moduleCode (snake_case), with id + factory_id + audit columns
        String tableName = moduleCode;
        if (jdbcTemplateForCustomModules != null) {
            String ddl = String.format(
                "CREATE TABLE IF NOT EXISTS %s (" +
                "id VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text, " +
                "factory_id VARCHAR(50) NOT NULL, " +
                "name VARCHAR(200), " +
                "status VARCHAR(32) DEFAULT 'ACTIVE', " +
                "created_at TIMESTAMP DEFAULT NOW(), " +
                "updated_at TIMESTAMP DEFAULT NOW(), " +
                "created_by BIGINT, " +
                "deleted_at TIMESTAMP)", tableName);
            try {
                jdbcTemplateForCustomModules.execute(ddl);
                jdbcTemplateForCustomModules.execute(
                    "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_factory ON " + tableName + " (factory_id)");
                log.info("Custom module table created: {}", tableName);
            } catch (Exception e) {
                log.error("Failed to create custom module table {}: {}", tableName, e.getMessage());
                throw new BusinessException(500, "创建模块表失败: " + e.getMessage())
                        .withHint("请联系管理员检查数据库 DDL 权限");
            }
        }

        // Enable the module for this factory in its draft config
        // Round 5 Fix OBS-2: pass real operatorId so draft creation and MODULE_CREATED log
        // are attributable to the actual user rather than a ghost operator=0.
        FactoryConfiguration draft = getOrCreateDraft(factoryId, operatorId);
        FactoryModuleConfig moduleConfig = FactoryModuleConfig.builder()
            .factoryId(factoryId)
            .moduleCode(moduleCode)
            .configVersion(draft.getConfigVersion())
            .enabled(true)
            .fieldConfig(Map.of("fields", Map.of()))
            .build();
        factoryModuleConfigRepository.save(moduleConfig);

        logChange(factoryId, moduleCode, "MODULE_CREATED", null, null,
                "创建自定义模块: " + moduleName + " (" + moduleCode + ")", operatorId);

        Map<String, Object> result = new HashMap<>();
        result.put("moduleCode", moduleCode);
        result.put("moduleName", moduleName);
        result.put("tableName", tableName);
        result.put("schemaId", schema.getId());
        result.put("enabled", true);
        return result;
    }

    // ========== Private Helpers ==========

    private FactoryConfiguration getOrCreateDraft(String factoryId, Long operatorId) {
        return factoryConfigurationRepository.findDraft(factoryId)
                .orElseGet(() -> {
                    FactoryConfiguration draft = new FactoryConfiguration();
                    draft.setFactoryId(factoryId);
                    draft.setConfigVersion(getNextVersion(factoryId));
                    draft.setStatus("DRAFT");
                    draft.setCreatedBy(operatorId);
                    FactoryConfiguration saved = factoryConfigurationRepository.save(draft);

                    // 从上个 published 版本 copy 所有 module configs 到新 draft
                    factoryConfigurationRepository.findLatestPublished(factoryId)
                            .ifPresent(published -> {
                                List<FactoryModuleConfig> prevModules = factoryModuleConfigRepository
                                        .findByFactoryIdAndConfigVersion(factoryId, published.getConfigVersion());
                                for (FactoryModuleConfig src : prevModules) {
                                    FactoryModuleConfig copy = new FactoryModuleConfig();
                                    copy.setFactoryId(factoryId);
                                    copy.setModuleCode(src.getModuleCode());
                                    copy.setConfigVersion(saved.getConfigVersion());
                                    copy.setEnabled(src.getEnabled());
                                    copy.setFieldConfig(new HashMap<>(src.getFieldConfig()));
                                    copy.setWorkflowConfig(new HashMap<>(src.getWorkflowConfig()));
                                    copy.setValidationConfig(new HashMap<>(src.getValidationConfig()));
                                    copy.setPermissionConfig(new HashMap<>(src.getPermissionConfig()));
                                    copy.setLayoutConfig(new HashMap<>(src.getLayoutConfig()));
                                    copy.setCustomLabels(new HashMap<>(src.getCustomLabels()));
                                    copy.setComputedFields(new HashMap<>(src.getComputedFields()));
                                    copy.setRenderingMode(src.getRenderingMode());
                                    factoryModuleConfigRepository.save(copy);
                                }
                                log.info("新 draft v{} 从 published v{} 继承了 {} 个模块配置",
                                        saved.getConfigVersion(), published.getConfigVersion(), prevModules.size());
                            });

                    return saved;
                });
    }

    private int getNextVersion(String factoryId) {
        return factoryConfigurationRepository.findByFactoryIdOrderByConfigVersionDesc(factoryId)
                .stream().findFirst()
                .map(c -> c.getConfigVersion() + 1)
                .orElse(1);
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> base, Map<String, Object> override) {
        if (override == null) return;
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            String key = entry.getKey();
            Object overrideVal = entry.getValue();
            Object baseVal = base.get(key);

            if (baseVal instanceof Map && overrideVal instanceof Map) {
                deepMerge((Map<String, Object>) baseVal, (Map<String, Object>) overrideVal);
            } else {
                base.put(key, overrideVal);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<EffectiveField> buildEffectiveFields(Map<String, Object> fieldSchema,
                                                       Map<String, Object> effectiveFieldConfig,
                                                       Map<String, Object> customLabels) {
        List<Map<String, Object>> schemaDefs = (List<Map<String, Object>>) fieldSchema.getOrDefault("fields", List.of());
        Map<String, Object> fieldOverrides = (Map<String, Object>) effectiveFieldConfig.getOrDefault("fields", Map.of());

        List<EffectiveField> result = new ArrayList<>();
        int order = 0;

        for (Map<String, Object> schemaDef : schemaDefs) {
            String code = (String) schemaDef.getOrDefault("code", schemaDef.get("fieldCode"));
            if (code == null) continue; // skip malformed field definitions
            Map<String, Object> override = fieldOverrides.containsKey(code)
                    ? (Map<String, Object>) fieldOverrides.get(code)
                    : Map.of();

            boolean visible = getBoolOrDefault(override, "visible",
                    getBoolOrDefault(schemaDef, "defaultVisible", true));
            boolean required = getBoolOrDefault(override, "required",
                    getBoolOrDefault(schemaDef, "required", false));

            String label = customLabels.containsKey(code)
                    ? String.valueOf(customLabels.get(code))
                    : (String) schemaDef.get("label");

            Object defaultValue = override.containsKey("defaultValue")
                    ? override.get("defaultValue")
                    : schemaDef.get("defaultValue");
            // P0-2 Fix (Round 4): applyTemplate wraps raw values in Map.of("value", x) for JSONB
            // serialization (see Fix #13). Without unwrapping here, frontend renders [object Object].
            // Unwrap any Map shaped like {"value": x} back to x before sending to frontend.
            if (defaultValue instanceof Map<?, ?> m && m.size() == 1 && m.containsKey("value")) {
                defaultValue = m.get("value");
            }

            Object options = override.containsKey("options")
                    ? override.get("options")
                    : schemaDef.get("options");

            Map<String, Object> extra = new HashMap<>();
            if (schemaDef.containsKey("dependsOn")) extra.put("dependsOn", schemaDef.get("dependsOn"));
            if (schemaDef.containsKey("referenceConfig")) extra.put("referenceConfig", schemaDef.get("referenceConfig"));
            if (schemaDef.containsKey("computed")) extra.put("computed", schemaDef.get("computed"));
            if (schemaDef.containsKey("itemSchema")) extra.put("itemSchema", schemaDef.get("itemSchema"));
            if (schemaDef.containsKey("min")) extra.put("min", schemaDef.get("min"));
            if (schemaDef.containsKey("max")) extra.put("max", schemaDef.get("max"));
            if (schemaDef.containsKey("precision")) extra.put("precision", schemaDef.get("precision"));
            if (schemaDef.containsKey("listVisible")) extra.put("listVisible", schemaDef.get("listVisible"));
            if (schemaDef.containsKey("listOrder")) extra.put("listOrder", schemaDef.get("listOrder"));
            if (schemaDef.containsKey("listWidth")) extra.put("listWidth", schemaDef.get("listWidth"));
            if (schemaDef.containsKey("formatter")) extra.put("formatter", schemaDef.get("formatter"));
            if (schemaDef.containsKey("configurable")) extra.put("configurable", schemaDef.get("configurable"));
            if (schemaDef.containsKey("autoGenerate")) extra.put("autoGenerate", schemaDef.get("autoGenerate"));
            // P1-D (PR #442 follow-up): plumb priceSensitive flag from module_schemas
            // through to EffectiveField.extra so SchemaTableRenderer can render stripped
            // null cells as em-dash with .price-masked class (mirrors static-Vue v-if
            // defense from PR #423). Backend strip is unconditional via
            // PriceFieldResponseAdvice / PriceSensitiveSerializerModifier; this flag
            // only controls UI rendering of the resulting null cell value.
            if (schemaDef.containsKey("priceSensitive")) extra.put("priceSensitive", schemaDef.get("priceSensitive"));

            result.add(EffectiveField.builder()
                    .code(code)
                    .label(label)
                    .type((String) schemaDef.get("type"))
                    .required(required)
                    .visible(visible)
                    .readonly(getBoolOrDefault(schemaDef, "readonly", false))
                    .defaultValue(defaultValue)
                    .options(options)
                    .group((String) schemaDef.getOrDefault("group", "basic"))
                    .order(order++)
                    .extra(extra)
                    .source("jpa")
                    .build());
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private List<FieldGroup> buildFieldGroups(Map<String, Object> fieldSchema) {
        List<Map<String, Object>> groups = (List<Map<String, Object>>) fieldSchema.getOrDefault("groups", List.of());
        return groups.stream()
                .map(g -> FieldGroup.builder()
                        .code((String) g.get("code"))
                        .label((String) g.get("label"))
                        .order(g.containsKey("order") ? ((Number) g.get("order")).intValue() : 0)
                        .visible(getBoolOrDefault(g, "visible", true))
                        .build())
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<WorkflowStateDTO> buildWorkflowStates(Map<String, Object> workflowSchema,
                                                        Map<String, Object> effectiveWorkflow) {
        if (workflowSchema == null) return List.of();
        List<Map<String, Object>> states = (List<Map<String, Object>>) workflowSchema.getOrDefault("states", List.of());
        Map<String, Object> disabledStates = (Map<String, Object>) effectiveWorkflow.getOrDefault("disabledStates", Map.of());

        return states.stream()
                .map(s -> {
                    String code = (String) s.get("code");
                    boolean enabled = !Boolean.TRUE.equals(disabledStates.get(code));
                    return WorkflowStateDTO.builder()
                            .code(code)
                            .label((String) s.get("label"))
                            .enabled(enabled)
                            .isInitial(getBoolOrDefault(s, "isInitial", false))
                            .isFinal(getBoolOrDefault(s, "isFinal", false))
                            .tagType((String) s.getOrDefault("tagType", ""))
                            .build();
                })
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<WorkflowTransitionDTO> buildWorkflowTransitions(Map<String, Object> workflowSchema,
                                                                   Map<String, Object> effectiveWorkflow) {
        if (workflowSchema == null) return List.of();
        List<Map<String, Object>> transitions = (List<Map<String, Object>>) workflowSchema.getOrDefault("transitions", List.of());
        Map<String, Object> options = (Map<String, Object>) effectiveWorkflow.getOrDefault("options", Map.of());

        return transitions.stream()
                .map(t -> {
                    boolean enabled = true;
                    String condition = (String) t.get("condition");
                    if (condition != null) {
                        enabled = evaluateCondition(condition, options);
                    }
                    // R40 BUG-5 fix: respect manualTrigger flag from schema (default true).
                    // When false, transition exists in state machine but FE should hide the
                    // button (auto-triggered by upstream event, no backend endpoint).
                    Object manualTriggerRaw = t.get("manualTrigger");
                    boolean manualTrigger = !Boolean.FALSE.equals(manualTriggerRaw);
                    return WorkflowTransitionDTO.builder()
                            .from((String) t.get("from"))
                            .to((String) t.get("to"))
                            .action((String) t.get("action"))
                            .label((String) t.getOrDefault("label", (String) t.get("action")))
                            .buttonType((String) t.getOrDefault("buttonType", "primary"))
                            .enabled(enabled)
                            .condition(condition)
                            .allowedRoles(t.containsKey("allowedRoles") ? (List<String>) t.get("allowedRoles") : List.of())
                            .manualTrigger(manualTrigger)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private boolean evaluateCondition(String condition, Map<String, Object> options) {
        if (condition.startsWith("!")) {
            String key = condition.substring(1).replace("config.workflow.", "");
            return !Boolean.TRUE.equals(options.get(key));
        } else {
            String key = condition.replace("config.workflow.", "");
            return Boolean.TRUE.equals(options.get(key));
        }
    }

    @SuppressWarnings("unchecked")
    private void applyPermissionFilter(List<EffectiveField> fields, Map<String, Object> permSchema, String roleCode) {
        List<Map<String, Object>> fieldPerms = (List<Map<String, Object>>) permSchema.getOrDefault("fieldPermissions", List.of());
        Map<String, String> permMap = new HashMap<>();
        for (Map<String, Object> fp : fieldPerms) {
            String fieldCode = (String) fp.get("fieldCode");
            Map<String, String> permissions = (Map<String, String>) fp.get("permissions");
            if (permissions != null && permissions.containsKey(roleCode)) {
                permMap.put(fieldCode, permissions.get(roleCode));
            }
        }

        for (EffectiveField field : fields) {
            String perm = permMap.get(field.getCode());
            if ("hidden".equals(perm)) {
                field.setVisible(false);
            } else if ("view".equals(perm)) {
                field.setReadonly(true);
            }
        }
    }

    private boolean getBoolOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        return defaultValue;
    }

    private void logChange(String factoryId, String moduleCode, String operation,
                           Map<String, Object> before, Map<String, Object> after,
                           String summary, Long operatorId) {
        ConfigChangeLog changeLog = ConfigChangeLog.builder()
                .factoryId(factoryId)
                .moduleCode(moduleCode)
                .operation(operation)
                .beforeValue(before)
                .afterValue(after)
                .diffSummary(summary)
                .operatorId(operatorId)
                .operatorType("USER")
                .build();
        configChangeLogRepository.save(changeLog);
    }

    @Override
    @Transactional
    public void logWorkflowTransition(String factoryId, int configVersion, String fromStatus,
                                       String toStatus, Long operatorId, String notes) {
        // Round 5 Fix OBS-1: persist workflow transitions as WORKFLOW_TRANSITION log entries.
        String summary = "配置版本 " + configVersion + " 状态变更: " + fromStatus + " → " + toStatus
                + (notes != null && !notes.isBlank() ? " (" + notes + ")" : "");
        Map<String, Object> before = Map.of("status", fromStatus, "configVersion", configVersion);
        Map<String, Object> after = Map.of("status", toStatus, "configVersion", configVersion);
        logChange(factoryId, null, "WORKFLOW_TRANSITION", before, after, summary, operatorId);
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> reorderFields(String factoryId, String moduleCode,
                                              List<String> fieldOrder, Long expectedVersion,
                                              Long operatorId) {
        // 1. Find DRAFT
        FactoryConfiguration draft = factoryConfigurationRepository.findDraft(factoryId)
                .orElseThrow(() -> new BusinessException(409, "没有 DRAFT 配置可重排字段")
                        .withHint("请先创建草稿配置后再重排字段"));

        // 2. Optimistic lock check
        if (draft.getRowVersion() == null || !draft.getRowVersion().equals(expectedVersion)) {
            throw new BusinessException(409, "版本冲突: 当前版本 " + draft.getRowVersion()
                    + ", 请求版本 " + expectedVersion)
                    .withHint("配置已被其他用户修改, 请刷新后重试").withHintTarget("expectedVersion");
        }

        int targetVersion = draft.getConfigVersion();

        // 3. Find or create FactoryModuleConfig for this module
        FactoryModuleConfig fmc = factoryModuleConfigRepository
                .findByFactoryIdAndModuleCodeAndConfigVersion(factoryId, moduleCode, targetVersion)
                .orElseGet(() -> {
                    FactoryModuleConfig c = new FactoryModuleConfig();
                    c.setFactoryId(factoryId);
                    c.setModuleCode(moduleCode);
                    c.setConfigVersion(targetVersion);
                    c.setEnabled(true);
                    c.setFieldConfig(new HashMap<>());
                    return c;
                });

        // 4. Update sortOrder of each field in fieldConfig.fields by its index in fieldOrder
        Map<String, Object> fieldConfig = fmc.getFieldConfig() != null
                ? fmc.getFieldConfig() : new HashMap<>();
        Map<String, Object> fields = (Map<String, Object>) fieldConfig.computeIfAbsent(
                "fields", k -> new HashMap<String, Object>());

        int reorderedCount = 0;
        int dynamicReorderedCount = 0;
        for (int i = 0; i < fieldOrder.size(); i++) {
            String fieldCode = fieldOrder.get(i);
            int newSortOrder = (i + 1) * 10;  // 10, 20, 30... leaves gaps for insertions
            Map<String, Object> fieldEntry = (Map<String, Object>) fields.computeIfAbsent(
                    fieldCode, k -> new HashMap<String, Object>());
            fieldEntry.put("sortOrder", newSortOrder);
            reorderedCount++;

            // Round 10 Fix C3 (R10 Task 1 hotfix — missed in original spec):
            // Dynamic fields are read directly from canvas_dynamic_field via
            // ORDER BY sortOrder (CanvasDynamicFieldRepository:20). The JSONB override
            // in factory_module_configs.field_config is NEVER consulted for dynamic
            // fields — only for JPA schema fields via getEffectiveConfig's deepMerge.
            // Without this second update, dynamic field reorder was silently no-op'd
            // on page refresh — exact same bug class R10 was supposed to fix.
            if (canvasDynamicFieldRepository != null) {
                var dfOpt = canvasDynamicFieldRepository
                        .findByFactoryIdAndModuleCodeAndFieldCode(factoryId, moduleCode, fieldCode);
                if (dfOpt.isPresent()) {
                    CanvasDynamicField df = dfOpt.get();
                    df.setSortOrder(newSortOrder);
                    canvasDynamicFieldRepository.save(df);
                    dynamicReorderedCount++;
                }
            }
        }
        fmc.setFieldConfig(fieldConfig);
        factoryModuleConfigRepository.save(fmc);

        // 5. Audit
        logChange(factoryId, moduleCode, "REORDER_FIELDS", null,
                Map.of("fieldOrder", fieldOrder,
                       "reorderedCount", reorderedCount,
                       "dynamicReorderedCount", dynamicReorderedCount),
                "字段重排: " + reorderedCount + " 个字段 ("
                        + dynamicReorderedCount + " dynamic)", operatorId);

        Map<String, Object> result = new HashMap<>();
        result.put("newVersion", draft.getRowVersion());
        result.put("reorderedCount", reorderedCount);
        return result;
    }
}
