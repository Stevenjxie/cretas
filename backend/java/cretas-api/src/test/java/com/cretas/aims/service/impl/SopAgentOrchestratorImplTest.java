package com.cretas.aims.service.impl;

import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Defense-in-depth test for {@link SopAgentOrchestratorImpl#evaluateCondition}
 * — B-A7 P0 sister fix (Canvas Phase 2-5 QA 2026-05-19).
 *
 * <p><b>Vulnerability before fix</b>: {@code evaluateCondition} used
 * {@link org.springframework.expression.spel.support.StandardEvaluationContext}
 * — full reflection / Type / static methods / constructors — so any rule with
 * malicious {@code conditionExpression} persisted in DB before write-time validation
 * existed (or written via SQL/direct DB access) could trigger RCE on the next
 * SOP-upload event.
 *
 * <p><b>Fix</b>: {@link SopAgentOrchestratorImpl#evaluateCondition} now delegates to
 * {@link SandboxedSpelEvaluator#evaluateBoolean} which uses
 * {@code SimpleEvaluationContext.forReadOnlyDataBinding()}. The sandbox rejects
 * forbidden constructs as {@link SandboxedSpelEvaluator.SpelEvaluationFailure},
 * caught + logged + treated as "rule doesn't match" — does NOT break the event flow.
 *
 * <p>Tests use reflection to invoke private {@code evaluateCondition} since this
 * is a defense-in-depth verification (the public {@code executeToolChain} path
 * needs a mocked repository + tools — too much setup for this focused
 * security test).
 *
 * @since 2026-05-19 (Canvas Phase 2-5 QA hotfix sister)
 */
@DisplayName("SopAgentOrchestratorImpl evaluateCondition 沙箱化 (B-A7 defense-in-depth)")
class SopAgentOrchestratorImplTest {

    private SopAgentOrchestratorImpl orchestrator;
    private Method evaluateConditionMethod;

    @BeforeEach
    void setUp() throws Exception {
        orchestrator = new SopAgentOrchestratorImpl();

        // Inject real SandboxedSpelEvaluator (Spring would normally @Autowired this)
        Field spelEvalField = SopAgentOrchestratorImpl.class.getDeclaredField("spelEvaluator");
        spelEvalField.setAccessible(true);
        spelEvalField.set(orchestrator, new SandboxedSpelEvaluator());

        // Inject ObjectMapper for completeness (evaluateCondition doesn't use it, but
        // other code paths in the orchestrator do).
        Field omField = SopAgentOrchestratorImpl.class.getDeclaredField("objectMapper");
        omField.setAccessible(true);
        omField.set(orchestrator, new ObjectMapper());

        // Get reference to private evaluateCondition method
        evaluateConditionMethod = SopAgentOrchestratorImpl.class.getDeclaredMethod(
                "evaluateCondition", String.class, Map.class);
        evaluateConditionMethod.setAccessible(true);
    }

    private boolean evaluateCondition(String spel, Map<String, Object> context) throws Exception {
        return (boolean) evaluateConditionMethod.invoke(orchestrator, spel, context);
    }

    // ==================== Sandbox blocks RCE at runtime (defense-in-depth) ====================

    @Test
    @DisplayName("B-A7 defense-in-depth: 运行时拒绝 T(Runtime).getRuntime().exec(...) (pre-fix DB data)")
    void testEvaluateConditionRejectsRuntimeExec() throws Exception {
        // Simulate pre-fix-persisted malicious rule. Sandbox rejects, returns false.
        // The exec(...) call must NEVER actually execute.
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("sopType", "PRODUCTION");
        boolean result = evaluateCondition(
                "T(java.lang.Runtime).getRuntime().exec(\"calc\")", ctx);
        assertFalse(result, "Sandbox MUST reject T(Runtime).exec → returns false (rule doesn't match)");
    }

    @Test
    @DisplayName("B-A7 defense-in-depth: 运行时拒绝 new ProcessBuilder() 构造器")
    void testEvaluateConditionRejectsConstructor() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        boolean result = evaluateCondition(
                "new java.lang.ProcessBuilder('rm').start() != null", ctx);
        assertFalse(result);
    }

    @Test
    @DisplayName("B-A7 defense-in-depth: 运行时拒绝 #ctx.getClass().forName(...) 反射逃逸")
    void testEvaluateConditionRejectsReflection() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("anything", "value");
        boolean result = evaluateCondition(
                "#anything.getClass().forName('java.lang.Runtime') != null", ctx);
        assertFalse(result);
    }

    @Test
    @DisplayName("B-A7 defense-in-depth: 运行时拒绝 #{...} 包装的 RCE")
    void testEvaluateConditionRejectsWrappedRce() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        // Wrapping #{...} is stripped before parse — must still hit sandbox.
        boolean result = evaluateCondition(
                "#{T(java.lang.Runtime).getRuntime().exec(\"id\")}", ctx);
        assertFalse(result);
    }

    // ==================== Valid expressions still evaluate correctly ====================

    @Test
    @DisplayName("null 表达式 → true (无条件 = 始终满足)")
    void testNullExpressionReturnsTrue() throws Exception {
        boolean result = evaluateCondition(null, new HashMap<>());
        assertTrue(result);
    }

    @Test
    @DisplayName("blank 表达式 → true")
    void testBlankExpressionReturnsTrue() throws Exception {
        boolean result = evaluateCondition("   ", new HashMap<>());
        assertTrue(result);
    }

    @Test
    @DisplayName("合法表达式正常求值: #sopType == 'PRODUCTION' (match)")
    void testValidExpressionEvaluatesTrue() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("sopType", "PRODUCTION");
        boolean result = evaluateCondition("#sopType == 'PRODUCTION'", ctx);
        assertTrue(result, "合法 SpEL 表达式应正常求值");
    }

    @Test
    @DisplayName("合法表达式正常求值: #sopType == 'PRODUCTION' (no-match)")
    void testValidExpressionEvaluatesFalse() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("sopType", "QUALITY");
        boolean result = evaluateCondition("#sopType == 'PRODUCTION'", ctx);
        assertFalse(result, "条件不满足时返 false");
    }

    @Test
    @DisplayName("合法表达式: #{...} 包装的属性比较 (mirror old runtime contract)")
    void testValidWrappedExpression() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("sopType", "PRODUCTION");
        boolean result = evaluateCondition("#{#sopType == 'PRODUCTION'}", ctx);
        assertTrue(result);
    }

    @Test
    @DisplayName("不存在字段 → 沙箱返 SpelEvaluationFailure → false (不破坏 event 循环)")
    void testUnknownFieldReturnsFalse() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        boolean result = evaluateCondition("#nonExistent.field == 'x'", ctx);
        assertFalse(result, "未知字段不应抛异常, 仅返 false 让 rule 不 match");
    }
}
