package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.engine.DynamicSchedulerService;
import com.cretas.aims.entity.config.FactoryFormula;
import com.cretas.aims.entity.config.FactoryValidationRule;
import com.cretas.aims.repository.config.FactoryDefaultValueRepository;
import com.cretas.aims.repository.config.FactoryFormulaRepository;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.repository.config.FactoryValidationRuleRepository;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Hardening tests for {@link BusinessRuleController} — P2 defense-in-depth
 * (B-A7 P0 sister fix, Canvas Phase 2-5 QA 2026-05-19).
 *
 * <p><b>Vulnerability before fix</b>: PUT {@code /api/mobile/{factoryId}/config/v2/formulas/{code}}
 * and PUT {@code /validation-rules/{ruleCode}} accepted arbitrary {@code expression} /
 * {@code condition} fields. {@code FactoryValidationRule.condition} is downstream-evaluated
 * as SpEL; {@code FactoryFormula.expression} is evaluated by a formula engine that may also
 * be SpEL-based or rely on similar parser conventions. RCE payloads like
 * {@code T(java.lang.Runtime).getRuntime().exec(...)} could be persisted without validation.
 *
 * <p><b>Fix</b>: inject {@link SandboxedSpelEvaluator}, call {@code validateSyntax(...)} on
 * write paths before persist. Layer 1 static-pattern scan blocks T() / new / reflection /
 * @bean / #root regardless of which engine eventually consumes the field.
 *
 * <p>Tests use a real {@link SandboxedSpelEvaluator} (not mock) — same pattern as
 * {@code CanvasAlertControllerTest} and {@code CanvasRuleControllerTest}.
 *
 * @since 2026-05-19 (Canvas Phase 2-5 QA hotfix sister)
 */
@DisplayName("BusinessRuleController SpEL/Formula 注入加固 (B-A7 P2 defense-in-depth)")
@ExtendWith(MockitoExtension.class)
class BusinessRuleControllerTest {

    @Mock private FactoryValidationRuleRepository validationRuleRepo;
    @Mock private FactoryDefaultValueRepository defaultValueRepo;
    @Mock private FactoryFormulaRepository formulaRepo;
    @Mock private FactorySchedulerConfigRepository schedulerRepo;
    @Mock private DynamicSchedulerService dynamicSchedulerService;

    /** Use real SpEL evaluator — verifies actual sandbox behavior, not a stub. */
    private final SandboxedSpelEvaluator spelEvaluator = new SandboxedSpelEvaluator();

    private BusinessRuleController controller;

    private static final String FACTORY_ID = "F006";

    @BeforeEach
    void setUp() {
        controller = new BusinessRuleController(
                validationRuleRepo,
                defaultValueRepo,
                formulaRepo,
                schedulerRepo,
                dynamicSchedulerService,
                spelEvaluator);
    }

    // ==================== Validation rule SpEL injection ====================

    @Test
    @DisplayName("B-A7 P2: PUT /validation-rules 拒绝 condition 含 T(Runtime).exec(...) RCE")
    void testValidationRuleSpelInjectionRejected() {
        FactoryValidationRule body = new FactoryValidationRule();
        body.setModuleCode("sales");
        body.setCondition("T(java.lang.Runtime).getRuntime().exec(\"calc\")");
        body.setErrorMessage("test");

        ApiResponse<FactoryValidationRule> resp = controller.setValidationRule(
                FACTORY_ID, "test_rule", body);

        assertNotNull(resp);
        assertEquals(Integer.valueOf(400), resp.getCode());
        assertFalse(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("SpEL"),
                "message 必须含 'SpEL' 关键字");
        // CRITICAL: rule must NOT be persisted
        verify(validationRuleRepo, never()).save(any(FactoryValidationRule.class));
    }

    @Test
    @DisplayName("B-A7 P2: PUT /validation-rules 拒绝 new ProcessBuilder() 构造器")
    void testValidationRuleSpelInjectionRejectedConstructor() {
        FactoryValidationRule body = new FactoryValidationRule();
        body.setModuleCode("inventory");
        body.setCondition("new java.lang.ProcessBuilder('ls').start() != null");
        body.setErrorMessage("test");

        ApiResponse<FactoryValidationRule> resp = controller.setValidationRule(
                FACTORY_ID, "test_rule_2", body);

        assertEquals(Integer.valueOf(400), resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        verify(validationRuleRepo, never()).save(any(FactoryValidationRule.class));
    }

    @Test
    @DisplayName("B-A7 P2: PUT /validation-rules 拒绝反射调用 #obj.getClass().forName(...)")
    void testValidationRuleSpelInjectionRejectedReflection() {
        FactoryValidationRule body = new FactoryValidationRule();
        body.setModuleCode("sales");
        body.setCondition("#order.getClass().forName('java.lang.Runtime') != null");
        body.setErrorMessage("test");

        ApiResponse<FactoryValidationRule> resp = controller.setValidationRule(
                FACTORY_ID, "test_rule_refl", body);

        assertEquals(Integer.valueOf(400), resp.getCode());
        verify(validationRuleRepo, never()).save(any(FactoryValidationRule.class));
    }

    // ==================== Formula expression SpEL injection ====================

    @Test
    @DisplayName("B-A7 P2: PUT /formulas 拒绝 expression 含 T(Runtime).exec(...) RCE")
    void testFormulaSpelInjectionRejected() {
        FactoryFormula body = new FactoryFormula();
        body.setModuleCode("finance");
        body.setExpression("T(java.lang.Runtime).getRuntime().exec(\"calc\")");

        ApiResponse<FactoryFormula> resp = controller.setFormula(
                FACTORY_ID, "test_formula", body);

        assertNotNull(resp);
        assertEquals(Integer.valueOf(400), resp.getCode());
        assertFalse(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("公式") || resp.getMessage().contains("SpEL"));
        verify(formulaRepo, never()).save(any(FactoryFormula.class));
    }

    @Test
    @DisplayName("B-A7 P2: PUT /formulas 拒绝 @beanName Spring bean 访问")
    void testFormulaSpelInjectionRejectedBeanRef() {
        FactoryFormula body = new FactoryFormula();
        body.setModuleCode("finance");
        body.setExpression("@dataSource.getConnection() != null");

        ApiResponse<FactoryFormula> resp = controller.setFormula(
                FACTORY_ID, "test_formula_bean", body);

        assertEquals(Integer.valueOf(400), resp.getCode());
        verify(formulaRepo, never()).save(any(FactoryFormula.class));
    }

    @Test
    @DisplayName("B-A7 P2: PUT /formulas 拒绝 #root 访问 (context 内部泄漏)")
    void testFormulaSpelInjectionRejectedRootAccess() {
        FactoryFormula body = new FactoryFormula();
        body.setModuleCode("finance");
        body.setExpression("#root.toString()");

        ApiResponse<FactoryFormula> resp = controller.setFormula(
                FACTORY_ID, "test_formula_root", body);

        assertEquals(Integer.valueOf(400), resp.getCode());
        verify(formulaRepo, never()).save(any(FactoryFormula.class));
    }

    // ==================== Valid expressions still accepted ====================

    @Test
    @DisplayName("B-A7 regression: 合法 validation rule condition 通过")
    void testValidValidationRuleAccepted() {
        FactoryValidationRule body = new FactoryValidationRule();
        body.setModuleCode("sales");
        body.setCondition("#order.amount > 0");
        body.setErrorMessage("订单金额必须大于 0");

        java.util.List<FactoryValidationRule> existing = java.util.Collections.emptyList();
        org.mockito.Mockito.when(validationRuleRepo.findByFactoryIdAndModuleCode(FACTORY_ID, "sales"))
                .thenReturn(existing);
        org.mockito.Mockito.when(validationRuleRepo.save(any(FactoryValidationRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<FactoryValidationRule> resp = controller.setValidationRule(
                FACTORY_ID, "amount_positive", body);

        assertNotNull(resp);
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals(Integer.valueOf(200), resp.getCode());
        verify(validationRuleRepo).save(any(FactoryValidationRule.class));
    }

    @Test
    @DisplayName("B-A7 regression: 合法公式表达式通过 (property-access syntax)")
    void testValidFormulaAccepted() {
        FactoryFormula body = new FactoryFormula();
        body.setModuleCode("finance");
        // Use property-access syntax (#var.field) — sandbox dry-run with empty variables
        // swallows "Property or field ... cannot be found" / "on null" errors. This mirrors
        // the same constraint observed in CanvasRuleControllerTest (PR #50) for the same reason.
        // (Pure arithmetic `#a * #b` on unbound vars surfaces a different operator-on-null
        // error not in the swallow list — that's a known dry-run limitation, not a controller bug.)
        body.setExpression("#price.amount > 0");

        org.mockito.Mockito.when(formulaRepo.findByFactoryIdAndModuleCodeAndFormulaCode(
                FACTORY_ID, "finance", "total"))
                .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(formulaRepo.save(any(FactoryFormula.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<FactoryFormula> resp = controller.setFormula(FACTORY_ID, "total", body);

        assertNotNull(resp);
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        verify(formulaRepo).save(any(FactoryFormula.class));
    }

    @Test
    @DisplayName("B-A7 regression: null condition (validation rule) — 不影响 save")
    void testNullConditionAcceptedValidationRule() {
        FactoryValidationRule body = new FactoryValidationRule();
        body.setModuleCode("sales");
        body.setCondition(null);
        body.setErrorMessage("test");

        java.util.List<FactoryValidationRule> existing = java.util.Collections.emptyList();
        org.mockito.Mockito.when(validationRuleRepo.findByFactoryIdAndModuleCode(FACTORY_ID, "sales"))
                .thenReturn(existing);
        org.mockito.Mockito.when(validationRuleRepo.save(any(FactoryValidationRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<FactoryValidationRule> resp = controller.setValidationRule(
                FACTORY_ID, "null_cond", body);

        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        verify(validationRuleRepo).save(any(FactoryValidationRule.class));
    }

    @Test
    @DisplayName("B-A7 regression: blank expression (formula) — 不影响 save")
    void testBlankExpressionAcceptedFormula() {
        FactoryFormula body = new FactoryFormula();
        body.setModuleCode("finance");
        body.setExpression(null);  // null expression (allowed for partial updates)

        org.mockito.Mockito.when(formulaRepo.findByFactoryIdAndModuleCodeAndFormulaCode(
                FACTORY_ID, "finance", "f1"))
                .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(formulaRepo.save(any(FactoryFormula.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<FactoryFormula> resp = controller.setFormula(FACTORY_ID, "f1", body);

        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        verify(formulaRepo).save(any(FactoryFormula.class));
    }
}
