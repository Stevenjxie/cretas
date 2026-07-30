package com.cretas.aims.service.config;

import com.cretas.aims.dto.config.*;

import java.util.List;
import java.util.Map;

public interface FactoryConfigService {

    // ========== 合并配置读取 (前端消费) ==========
    EffectiveModuleConfig getEffectiveConfig(String factoryId, String moduleCode);
    EffectiveModuleConfig getEffectiveConfig(String factoryId, String moduleCode, String roleCode);
    EffectiveModuleConfig getEffectiveConfig(String factoryId, String moduleCode, String roleCode, String userId);

    // ========== 字段级查询 (C 层) ==========
    boolean isFieldVisible(String factoryId, String moduleCode, String fieldCode);
    boolean isFieldRequired(String factoryId, String moduleCode, String fieldCode);
    Object getFieldDefault(String factoryId, String moduleCode, String fieldCode);

    // ========== 流程级查询 (D 层) ==========
    List<WorkflowStateDTO> getWorkflowStates(String factoryId, String moduleCode);
    List<WorkflowTransitionDTO> getAvailableTransitions(String factoryId, String moduleCode, String currentState);
    boolean isTransitionAllowed(String factoryId, String moduleCode, String fromState, String toState);

    // ========== 模块级查询 (B 层) ==========
    boolean isModuleEnabled(String factoryId, String moduleCode);
    List<ModuleSummaryDTO> getEnabledModules(String factoryId);

    // ========== 配置写操作 ==========
    void saveModuleConfig(String factoryId, String moduleCode, ModuleConfigDTO config, Long operatorId);
    void toggleModule(String factoryId, String moduleCode, boolean enabled, Long operatorId);
    void updateFieldConfig(String factoryId, String moduleCode, String fieldCode,
                           FieldConfigDTO fieldConfig, Long operatorId);

    // ========== 发布与版本 ==========
    void publishConfig(String factoryId, Long operatorId, String changeSummary);
    void rollbackConfig(String factoryId, int targetVersion, Long operatorId);

    // ========== 模板 ==========
    void applyTemplate(String factoryId, String templateCode, Long operatorId);

    // ========== 导出/导入 (Round 4 Fix P1-16) ==========
    /** Export full Canvas config bundle: modules + dynamic fields + validation rules + trigger chains + formulas */
    Map<String, Object> exportConfig(String factoryId);
    /** Import a config bundle (from another factory) as DRAFT, merging with existing config */
    Map<String, Object> importConfig(String factoryId, Map<String, Object> bundle, Long operatorId);

    // ========== Runtime 创建模块 (Round 4 Fix P1-12) ==========
    /**
     * Create a new ModuleSchema + auto CREATE TABLE for custom business domains.
     * Round 5 Fix OBS-2: accepts operatorId for audit attribution.
     */
    Map<String, Object> createCustomModule(String factoryId, String moduleCode, String moduleName,
                                            String moduleCategory, String description, Long operatorId);

    // ========== Audit (Round 5 Fix OBS-1) ==========
    /**
     * Persist an audit entry for a workflow state transition (DRAFT→PENDING_REVIEW etc).
     * Called from ConfigController.transitionStatus so the transition is visible in the
     * config_change_log table, not just the application log.
     */
    void logWorkflowTransition(String factoryId, int configVersion, String fromStatus,
                                String toStatus, Long operatorId, String notes);

    /**
     * Round 10 Fix: reorder fields within a module's DRAFT config.
     * Returns {newVersion, reorderedCount}. Throws if DRAFT missing or version mismatch.
     */
    Map<String, Object> reorderFields(String factoryId, String moduleCode,
                                       List<String> fieldOrder, Long expectedVersion,
                                       Long operatorId);
}
