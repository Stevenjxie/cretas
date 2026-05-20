package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.engine.DynamicSchedulerService;
import com.cretas.aims.entity.config.*;
import com.cretas.aims.repository.config.*;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator.SpelEvaluationFailure;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Round 5 Fix SEC-6: added @RequireRole on mutating endpoints (PUT validation-rules,
 * default-values, formulas, scheduler). Previously any authenticated factory user could
 * set validation rules or change scheduler cron — huge production risk (a bad rule can
 * block all saves across a module, and a bad cron can trigger runaway AI workflows).
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/config/v2")
@RequiredArgsConstructor
@Tag(name = "Canvas V2 Business Rules", description = "校验规则/默认值/公式/定时任务")
public class BusinessRuleController {

    /** Minimum interval: seconds field must be a single literal number (0-59).
     *  Blocks *, ranges, commas, and sub-minute steps that cause self-DDoS. */
    private static void validateCronFrequency(String cron) {
        if (cron == null) return;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length >= 1) {
            String seconds = parts[0];
            // Only allow a single literal second value (e.g. "0", "30", "59")
            if (!seconds.matches("^\\d{1,2}$")) {
                throw new IllegalArgumentException(
                    "Cron 秒字段只允许单个数字 (0-59), 当前: '" + seconds
                    + "'. 最小间隔: 0 * * * * ? (每分钟). 不支持 */N, 逗号列表, 范围");
            }
            int sec = Integer.parseInt(seconds);
            if (sec < 0 || sec > 59) {
                throw new IllegalArgumentException("Cron 秒字段超出范围 (0-59): " + sec);
            }
        }
    }


    private final FactoryValidationRuleRepository validationRuleRepo;
    private final FactoryDefaultValueRepository defaultValueRepo;
    private final FactoryFormulaRepository formulaRepo;
    private final FactorySchedulerConfigRepository schedulerRepo;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final SandboxedSpelEvaluator spelEvaluator;

    @GetMapping("/validation-rules")
    @Operation(summary = "获取工厂校验规则列表")
    public ApiResponse<List<FactoryValidationRule>> getValidationRules(
            @PathVariable String factoryId, @RequestParam(required = false) String moduleCode) {
        if (moduleCode != null) {
            return ApiResponse.success(validationRuleRepo.findByModuleCodeForFactory(factoryId, moduleCode));
        }
        return ApiResponse.success(validationRuleRepo.findAllForFactory(factoryId));
    }

    @PutMapping("/validation-rules/{ruleCode}")
    @Operation(summary = "配置校验规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<FactoryValidationRule> setValidationRule(
            @PathVariable String factoryId, @PathVariable String ruleCode,
            @RequestBody FactoryValidationRule body) {
        // B-A7 P2 defense-in-depth (sister of PR #48 / #50 / aiAgent fix):
        // FactoryValidationRule.condition is consumed as SpEL by downstream evaluators
        // — reject RCE / reflection at write time before persist.
        ApiResponse<FactoryValidationRule> conditionError = validateSpelFvr(body.getCondition());
        if (conditionError != null) return conditionError;

        FactoryValidationRule rule = validationRuleRepo.findByFactoryIdAndModuleCode(factoryId, body.getModuleCode())
                .stream().filter(r -> r.getRuleCode().equals(ruleCode)).findFirst()
                .orElseGet(() -> {
                    FactoryValidationRule r = new FactoryValidationRule();
                    r.setFactoryId(factoryId); r.setRuleCode(ruleCode);
                    r.setModuleCode(body.getModuleCode());
                    r.setCondition(body.getCondition() != null ? body.getCondition() : "true");
                    r.setErrorMessage(body.getErrorMessage() != null ? body.getErrorMessage() : ruleCode);
                    return r;
                });
        if (body.getEnabled() != null) rule.setEnabled(body.getEnabled());
        if (body.getSeverity() != null) rule.setSeverity(body.getSeverity());
        if (body.getErrorMessage() != null) rule.setErrorMessage(body.getErrorMessage());
        if (body.getCondition() != null) rule.setCondition(body.getCondition());
        if (body.getOperation() != null) rule.setOperation(body.getOperation());
        return ApiResponse.success(validationRuleRepo.save(rule));
    }

    @GetMapping("/default-values")
    @Operation(summary = "获取工厂默认值列表")
    public ApiResponse<List<FactoryDefaultValue>> getDefaultValues(
            @PathVariable String factoryId, @RequestParam(required = false) String moduleCode) {
        if (moduleCode != null) return ApiResponse.success(defaultValueRepo.findByModuleCodeForFactory(factoryId, moduleCode));
        return ApiResponse.success(defaultValueRepo.findAllForFactory(factoryId));
    }

    @PutMapping("/default-values")
    @Operation(summary = "设置默认值")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<FactoryDefaultValue> setDefaultValue(@PathVariable String factoryId, @RequestBody FactoryDefaultValue body) {
        body.setFactoryId(factoryId);
        return ApiResponse.success(defaultValueRepo.save(body));
    }

    @GetMapping("/formulas")
    @Operation(summary = "获取工厂公式列表")
    public ApiResponse<List<FactoryFormula>> getFormulas(
            @PathVariable String factoryId, @RequestParam(required = false) String moduleCode) {
        if (moduleCode != null) return ApiResponse.success(formulaRepo.findByModuleCodeForFactory(factoryId, moduleCode));
        return ApiResponse.success(formulaRepo.findAllForFactory(factoryId));
    }

    @PutMapping("/formulas/{formulaCode}")
    @Operation(summary = "配置公式")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<FactoryFormula> setFormula(
            @PathVariable String factoryId, @PathVariable String formulaCode, @RequestBody FactoryFormula body) {
        // B-A7 P2 defense-in-depth (sister of PR #48 / #50 / aiAgent fix):
        // FactoryFormula.expression is evaluated by the formula engine; even though the
        // current engine may use its own parser, treat any user-supplied expression as
        // untrusted and reject obvious RCE constructs (T() / new / reflection / @bean)
        // at write time. The SpEL sandbox's static-pattern layer (Layer 1) provides this
        // defense regardless of which engine eventually consumes the field.
        ApiResponse<FactoryFormula> expressionError = validateSpelFormula(body.getExpression());
        if (expressionError != null) return expressionError;

        FactoryFormula formula = formulaRepo.findByFactoryIdAndModuleCodeAndFormulaCode(factoryId, body.getModuleCode(), formulaCode)
                .orElseGet(() -> { FactoryFormula f = new FactoryFormula(); f.setFactoryId(factoryId); f.setFormulaCode(formulaCode); f.setModuleCode(body.getModuleCode()); return f; });
        if (body.getExpression() != null) formula.setExpression(body.getExpression());
        if (body.getVariables() != null) formula.setVariables(body.getVariables());
        if (body.getResultType() != null) formula.setResultType(body.getResultType());
        if (body.getPrecisionVal() != null) formula.setPrecisionVal(body.getPrecisionVal());
        return ApiResponse.success(formulaRepo.save(formula));
    }

    // R7 Issue 2: close CRUD gap — PUT creates formulas, but there was no DELETE endpoint.
    // Test env accumulated test formulas per J1 run (phaseI creates one with SUFFIX-scoped code).
    // Prod admins had no API way to remove a formula (only direct SQL). This closes the gap.
    //
    // Unique constraint on (factory_id, module_code, formula_code) means the same formulaCode
    // can exist for different moduleCodes → moduleCode must be specified as a query param.
    // Idempotent: if formula doesn't exist, returns success (no 404) — friendly for cleanup
    // scripts that may run DELETE even when the resource was already removed.
    @DeleteMapping("/formulas/{formulaCode}")
    @Operation(summary = "删除公式")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Void> deleteFormula(
            @PathVariable String factoryId,
            @PathVariable String formulaCode,
            @RequestParam String moduleCode) {
        formulaRepo.findByFactoryIdAndModuleCodeAndFormulaCode(factoryId, moduleCode, formulaCode)
            .ifPresent(formulaRepo::delete);
        return ApiResponse.success("已删除公式 (或已不存在): " + formulaCode, null);
    }

    @GetMapping("/scheduler")
    @Operation(summary = "获取工厂定时任务列表")
    public ApiResponse<List<FactorySchedulerConfig>> getSchedulerConfigs(@PathVariable String factoryId) {
        return ApiResponse.success(schedulerRepo.findAllForFactory(factoryId));
    }

    @PutMapping("/scheduler/{taskCode}")
    @Operation(summary = "配置定时任务 (热更新)")
    @RequireRole({"factory_super_admin"})
    public ApiResponse<FactorySchedulerConfig> setSchedulerConfig(
            @PathVariable String factoryId, @PathVariable String taskCode, @RequestBody FactorySchedulerConfig body) {
        if (body.getCronExpression() != null) validateCronFrequency(body.getCronExpression());
        FactorySchedulerConfig config = schedulerRepo.findByFactoryIdAndTaskCode(factoryId, taskCode)
                .orElseGet(() -> { FactorySchedulerConfig c = new FactorySchedulerConfig(); c.setFactoryId(factoryId); c.setTaskCode(taskCode); c.setCronExpression("0 0 2 * * ?"); return c; });
        if (body.getCronExpression() != null) config.setCronExpression(body.getCronExpression());
        if (body.getEnabled() != null) config.setEnabled(body.getEnabled());
        if (body.getToolOrMethod() != null) config.setToolOrMethod(body.getToolOrMethod());
        if (body.getParams() != null) config.setParams(body.getParams());
        FactorySchedulerConfig saved = schedulerRepo.save(config);
        dynamicSchedulerService.reloadSchedule(factoryId, taskCode);
        return ApiResponse.success(saved);
    }

    /**
     * B-A7 P2 defense-in-depth (sister of PR #48 / #50 / aiAgent fix): validate
     * SpEL-shaped fields ({@code condition}) on validation-rule PUT.
     * Returns non-null error response when SpEL contains RCE / reflection / forbidden
     * constructs. Returns null when null/blank (allowed) or passes sandbox validation.
     */
    private ApiResponse<FactoryValidationRule> validateSpelFvr(String spel) {
        if (spel == null || spel.isBlank()) return null;
        try {
            spelEvaluator.validateSyntax(spel);
            return null;
        } catch (SpelEvaluationFailure ex) {
            log.warn("Rejected validation-rule SpEL at write time: spel={}, reason={}",
                    spel, ex.getUserMessage());
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "SpEL 表达式语法/安全检查失败: " + ex.getUserMessage(),
                    ex.getActionHint() != null
                            ? ex.getActionHint()
                            : "仅支持业务字段访问, 禁用 T()/new/反射",
                    "warning");
        }
    }

    /**
     * Same as {@link #validateSpelFvr} but returns a {@link FactoryFormula}-typed
     * ApiResponse so the formula PUT signature is satisfied without a generic.
     * Note: even if {@code FactoryFormula.expression} is evaluated by a non-SpEL engine,
     * the static-pattern Layer 1 of {@link SandboxedSpelEvaluator#validateSyntax} still
     * blocks T() / new / reflection / @bean — defense-in-depth.
     */
    private ApiResponse<FactoryFormula> validateSpelFormula(String spel) {
        if (spel == null || spel.isBlank()) return null;
        try {
            spelEvaluator.validateSyntax(spel);
            return null;
        } catch (SpelEvaluationFailure ex) {
            log.warn("Rejected formula expression at write time: spel={}, reason={}",
                    spel, ex.getUserMessage());
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "公式表达式语法/安全检查失败: " + ex.getUserMessage(),
                    ex.getActionHint() != null
                            ? ex.getActionHint()
                            : "仅支持业务字段引用 + 算术运算, 禁用 T()/new/反射",
                    "warning");
        }
    }
}
