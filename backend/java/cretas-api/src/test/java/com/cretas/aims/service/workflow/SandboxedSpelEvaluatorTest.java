package com.cretas.aims.service.workflow;

import com.cretas.aims.service.workflow.SandboxedSpelEvaluator.SpelEvaluationFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SandboxedSpelEvaluator 单元测试 — Sprint 4 W2 Chat J C-WF-VAR-1.
 *
 * <p>核心: 验证 sandbox 真的 block 反射 / Type / 静态方法 / 构造器, 而非 lip-service.
 *
 * <p>覆盖:
 * <ol>
 *   <li>正面: 变量引用 + 属性读取 + 比较 + 逻辑 + Map[]</li>
 *   <li>负面: T(Runtime).getRuntime() — security</li>
 *   <li>负面: new java.lang.ProcessBuilder() — security</li>
 *   <li>负面: #own.getClass().getMethods() — reflection</li>
 *   <li>负面: 不存在字段 → friendly error with hint</li>
 *   <li>负面: parse 错误 → friendly error with hint</li>
 *   <li>负面: 写入 #own.role = 'X' — read-only enforcement</li>
 *   <li>正面: SpelEvaluationFailure.getActionHint 内容</li>
 * </ol>
 */
@DisplayName("SandboxedSpelEvaluator 安全 + 求值测试")
class SandboxedSpelEvaluatorTest {

    private SandboxedSpelEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new SandboxedSpelEvaluator();
    }

    private Map<String, Object> sampleVars() {
        Map<String, Object> vars = new HashMap<>();
        WorkflowVariableContext.OwnContext own = new WorkflowVariableContext.OwnContext(
                42L, "alice", "factory_admin", "FINANCE");
        WorkflowVariableContext.OrderContext order = new WorkflowVariableContext.OrderContext(
                "SO-001", new BigDecimal("15000"), "SALES", 7L, "PENDING_APPROVAL");
        WorkflowVariableContext.CustomerContext cust = new WorkflowVariableContext.CustomerContext(
                "C001", "六腾门", "VIP", new BigDecimal("100000"));
        vars.put("own", own);
        vars.put("order", order);
        vars.put("customer", cust);
        Map<String, Object> biz = new HashMap<>();
        biz.put("priority", "HIGH");
        biz.put("season", "Q3");
        vars.put("businessEntity", biz);
        return vars;
    }

    // ==================== 正面: 求值能力 ====================

    @Test
    @DisplayName("简单变量引用 + 比较 — #order.amount > 10000")
    void testAmountComparison() {
        boolean result = evaluator.evaluateBoolean("#order.amount > 10000", sampleVars());
        assertTrue(result);
    }

    @Test
    @DisplayName("逻辑组合 — VIP + 大单 走特殊审批")
    void testLogicalAnd() {
        boolean result = evaluator.evaluateBoolean(
                "#customer.level == 'VIP' && #order.amount > 10000",
                sampleVars());
        assertTrue(result);
    }

    @Test
    @DisplayName("Map 索引访问 — #businessEntity['priority'] == 'HIGH'")
    void testMapIndexAccess() {
        boolean result = evaluator.evaluateBoolean(
                "#businessEntity['priority'] == 'HIGH'", sampleVars());
        assertTrue(result);
    }

    @Test
    @DisplayName("数值 + decimal 兼容 — #order.amount > 100 (BigDecimal vs int)")
    void testDecimalIntComparison() {
        boolean result = evaluator.evaluateBoolean("#order.amount > 100", sampleVars());
        assertTrue(result);
    }

    @Test
    @DisplayName("evaluate() 返原始 Object (供 AIChat Tool 用)")
    void testEvaluateReturnsRawObject() {
        Object result = evaluator.evaluate("#own.role", sampleVars());
        assertEquals("factory_admin", result);
    }

    // ==================== 负面: 安全 (核心) ====================

    @Test
    @DisplayName("⛔ 禁止 T(Runtime).getRuntime() — RCE 攻击 vector")
    void testBlocksRuntimeAccess() {
        SpelEvaluationFailure ex = assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.evaluate("T(java.lang.Runtime).getRuntime()", sampleVars()));
        assertNotNull(ex.getActionHint());
    }

    @Test
    @DisplayName("⛔ 禁止 new java.lang.ProcessBuilder()")
    void testBlocksConstructor() {
        assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.evaluate("new java.lang.ProcessBuilder('ls')", sampleVars()));
    }

    @Test
    @DisplayName("⛔ 禁止 #own.getClass() 反射逃逸")
    void testBlocksReflection() {
        assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.evaluate("#own.getClass().getName()", sampleVars()));
    }

    @Test
    @DisplayName("⛔ 禁止 #own.role = 'admin' 赋值 (read-only)")
    void testBlocksAssignment() {
        assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.evaluate("#own.role = 'super_admin'", sampleVars()));
    }

    @Test
    @DisplayName("⛔ 禁止 T(System).exit(0)")
    void testBlocksSystemExit() {
        assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.evaluate("T(java.lang.System).exit(0)", sampleVars()));
    }

    // ==================== R5: 友好错误 ====================

    @Test
    @DisplayName("R5: 不存在字段抛 SpelEvaluationFailure 带 hint")
    void testUnknownFieldHint() {
        SpelEvaluationFailure ex = assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.evaluate("#own.nonExistentField", sampleVars()));
        assertTrue(ex.getActionHint().contains("变量库") || ex.getActionHint().contains("字段"));
    }

    @Test
    @DisplayName("R5: SpEL 语法错误抛 friendly message + 示例 hint")
    void testParseErrorHint() {
        SpelEvaluationFailure ex = assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.evaluate("#own.role ==== 'admin'", sampleVars()));
        assertTrue(ex.getActionHint().contains("拼写") || ex.getActionHint().contains("语法"));
    }

    @Test
    @DisplayName("R5: 引用未注册的 #variable 抛 SpelEvaluationFailure")
    void testUnknownVariableThrows() {
        // SpEL: 不存在 #foo 实际 evaluates to null, 不抛. 但 #foo.bar 抛 (访问 null 属性).
        SpelEvaluationFailure ex = assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.evaluate("#nonExistent.field", sampleVars()));
        assertNotNull(ex.getActionHint());
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("evaluateBoolean 结果非 boolean 时返 false (不抛)")
    void testNonBooleanReturnsFalse() {
        // #own.role 返 String, 不是 Boolean
        boolean result = evaluator.evaluateBoolean("#own.role", sampleVars());
        assertFalse(result);
    }

    @Test
    @DisplayName("空 variables map 时基础求值仍工作 — 1 == 1")
    void testNoVariables() {
        boolean result = evaluator.evaluateBoolean("1 == 1", new HashMap<>());
        assertTrue(result);
    }

    // ==================== validateSyntax bracket indexing ====================

    @Test
    @DisplayName("validateSyntax: bracket indexing #order['amount'] accepted (Map-friendly syntax)")
    void testValidateSyntaxAcceptsBracketIndexing() {
        // Bracket syntax required for Map property access at runtime
        // (per RuleEngineImpl.buildSpelVariables which binds Map<String,Object>).
        // Dry-run with null #order emits EL1012E "Cannot index into a null value"
        // — must be swallowed since syntax itself is valid.
        assertDoesNotThrow(() -> evaluator.validateSyntax("#order['amount'] > 100"));
        assertDoesNotThrow(() -> evaluator.validateSyntax("#input['value'] < 10"));
        assertDoesNotThrow(() -> evaluator.validateSyntax("#inventory['stock'] != 0"));
    }

    @Test
    @DisplayName("validateSyntax: bracket indexing still rejects T()/new/reflection")
    void testValidateSyntaxBracketDoesNotBypassSecurity() {
        // Bracket indexing must not be an escape hatch for forbidden constructs.
        assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.validateSyntax("T(java.lang.Runtime).getRuntime().exec('calc')"));
        assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.validateSyntax("#order['amount'] > new java.lang.Integer(0)"));
    }

    @Test
    @DisplayName("validateSyntax: binary operators on null variables accepted (EL1030E swallow)")
    void testValidateSyntaxAcceptsBinaryOpOnNullVars() {
        // Layer 2 dry-run runs with no variables bound; "#qty + #price" evaluates to
        // null + null → EL1030E "is not supported between objects of type 'null' and 'null'".
        // Syntax itself is valid — runtime binds numeric values (e.g. FormulaEngine.evaluate
        // passes variables map with #qty and #price set). Must be swallowed.
        assertDoesNotThrow(() -> evaluator.validateSyntax("#qty + #price"));
        assertDoesNotThrow(() -> evaluator.validateSyntax("#qty * #price"));
        assertDoesNotThrow(() -> evaluator.validateSyntax("#qty - #price"));
        assertDoesNotThrow(() -> evaluator.validateSyntax("#qty / #price"));
        assertDoesNotThrow(() -> evaluator.validateSyntax("#a + #b - #c"));
    }

    @Test
    @DisplayName("validateSyntax: binary-op swallow does not bypass security")
    void testValidateSyntaxBinaryOpDoesNotBypassSecurity() {
        // Adding "is not supported between" to swallow list MUST NOT open T()/new escape.
        assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.validateSyntax("T(java.lang.Runtime).getRuntime() + #x"));
        assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.validateSyntax("#x + new java.lang.Integer(0)"));
    }

    // ==================== DoS guards (Layer 0) ====================

    @Test
    @DisplayName("DoS guard: validateSyntax rejects 2001-char expression (length cap)")
    void testValidateSyntaxRejectsLongExpression() {
        // Build a 2001-char SpEL expression: "#x == '<2000 a's>'" = 7 + 1993 + 1 = 2001 chars.
        StringBuilder sb = new StringBuilder("#x == '");
        // Need final length > 2000. "#x == '" = 7 chars, "'" = 1 char, so padding > 1992.
        while (sb.length() < 2000) {
            sb.append("a");
        }
        sb.append("'");
        String tooLong = sb.toString();
        assertTrue(tooLong.length() > 2000, "test setup: expression must exceed 2000 chars");

        SpelEvaluationFailure ex = assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.validateSyntax(tooLong));
        assertTrue(ex.getUserMessage().contains("过长") || ex.getUserMessage().contains("长"),
                "error message must mention length constraint");
        assertNotNull(ex.getActionHint());
    }

    @Test
    @DisplayName("DoS guard: validateSyntax accepts 2000-char expression (edge — at cap)")
    void testValidateSyntaxAcceptsExactlyMaxLength() {
        // Construct expression of exactly 2000 chars that's also valid SpEL.
        StringBuilder sb = new StringBuilder("#x == '");
        while (sb.length() < 1999) {
            sb.append("a");
        }
        sb.append("'");
        String exactMax = sb.toString();
        assertEquals(2000, exactMax.length(), "test setup: expression must be exactly 2000 chars");

        assertDoesNotThrow(() -> evaluator.validateSyntax(exactMax));
    }

    @Test
    @DisplayName("DoS guard: validateSyntax rejects 31-level paren nesting (depth cap)")
    void testValidateSyntaxRejectsDeeplyNested() {
        // 31 opening + 31 closing parens
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 31; i++) sb.append("(");
        sb.append("1 == 1");
        for (int i = 0; i < 31; i++) sb.append(")");
        String nested = sb.toString();

        SpelEvaluationFailure ex = assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.validateSyntax(nested));
        assertTrue(ex.getUserMessage().contains("嵌套") || ex.getUserMessage().contains("深"),
                "error message must mention nesting depth");
    }

    @Test
    @DisplayName("DoS guard: validateSyntax accepts 30-level paren nesting (edge — at cap)")
    void testValidateSyntaxAcceptsExactlyMaxDepth() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("(");
        sb.append("1 == 1");
        for (int i = 0; i < 30; i++) sb.append(")");
        String nested = sb.toString();

        assertDoesNotThrow(() -> evaluator.validateSyntax(nested));
    }

    @Test
    @DisplayName("DoS guard: validateSyntax rejects 500-level paren (depth cap fires before stack overflow)")
    void testValidateSyntaxRejectsStackOverflow() {
        // 500-level nesting — should be rejected by depth cap (Layer 0), never reaching
        // the StackOverflowError-catch in Layer 2. Both layers protect JVM stability.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append("(");
        sb.append("1 == 1");
        for (int i = 0; i < 500; i++) sb.append(")");
        String deepNested = sb.toString();

        SpelEvaluationFailure ex = assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.validateSyntax(deepNested));
        // Either depth cap (preferred) or stack-overflow catch — both are friendly failures.
        assertNotNull(ex.getUserMessage());
        assertTrue(ex.getUserMessage().contains("嵌套") || ex.getUserMessage().contains("栈"),
                "error message must mention nesting/stack");
    }

    @Test
    @DisplayName("DoS guard: validateSyntax rejects ReDoS-shaped regex (a+)+ in .matches()")
    void testValidateSyntaxRejectsReDoS() {
        // Classic catastrophic-backtracking pattern: (a+)+ nested quantifier.
        // The heuristic catches obvious nested-quantifier shapes inside .matches(...).
        SpelEvaluationFailure ex = assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.validateSyntax("#input.matches(\"(a+)+b\")"));
        assertTrue(ex.getUserMessage().contains("ReDoS") || ex.getUserMessage().contains("正则"),
                "error message must mention ReDoS / regex");
    }

    @Test
    @DisplayName("DoS guard: validateSyntax rejects ReDoS (a+)* shape too")
    void testValidateSyntaxRejectsReDoSStarShape() {
        SpelEvaluationFailure ex = assertThrows(SpelEvaluationFailure.class,
                () -> evaluator.validateSyntax("#text.matches(\"(.+)*end\")"));
        assertNotNull(ex.getUserMessage());
    }

    @Test
    @DisplayName("DoS guard regression: validateSyntax accepts harmless regex in .matches()")
    void testValidateSyntaxAcceptsSimpleRegex() {
        // Simple anchored regex without nested quantifiers should still pass.
        assertDoesNotThrow(() -> evaluator.validateSyntax(
                "#input.matches(\"^[a-z]+$\")"));
    }
}
