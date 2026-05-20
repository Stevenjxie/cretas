package com.cretas.aims.service.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * 沙箱化 SpEL 求值器 — Sprint 4 W2 Chat J C-WF-VAR-1.
 *
 * <p>替换 Sprint 3 I {@code ApprovalWorkflowExecutorImpl} 直接使用的
 * {@link org.springframework.expression.spel.support.StandardEvaluationContext} —
 * StandardEvaluationContext 默认开放 <b>反射 / Type / 构造器 / 静态方法</b> 调用,
 * 任何能编辑 workflow edge condition 的用户都能 RCE (e.g.
 * {@code T(java.lang.Runtime).getRuntime().exec('rm -rf /')}).
 *
 * <p>本实现用 {@link SimpleEvaluationContext#forReadOnlyDataBinding()}, 仅允许:
 * <ul>
 *   <li>变量引用 ({@code #own}, {@code #order})</li>
 *   <li>属性读取 ({@code .userId}, {@code .amount})</li>
 *   <li>Map 索引 ({@code ['key']})</li>
 *   <li>字面量 + 比较 + 逻辑运算符 (== / != / > / < / && / ||)</li>
 * </ul>
 * 自动 <b>禁止</b>:
 * <ul>
 *   <li>{@code T(...)} type 引用</li>
 *   <li>静态方法 ({@code Runtime.getRuntime})</li>
 *   <li>构造器 ({@code new java.lang.ProcessBuilder()})</li>
 *   <li>反射 ({@code obj.getClass().getMethods()})</li>
 *   <li>property 写入 (read-only)</li>
 * </ul>
 *
 * <p>错误处理: parse/eval 失败抛 {@link SpelEvaluationFailure} 而非 silent false,
 * 让 caller 决定 fallback (per .claude/rules/fool-proof-design.md R5 友好错误).
 *
 * @author Cretas Team
 * @since 2026-05-17
 */
@Slf4j
@Component
public class SandboxedSpelEvaluator {

    private final SpelExpressionParser parser = new SpelExpressionParser();

    /** DoS guard: max SpEL expression length. Anything longer is rejected at validate-time
     *  before parse. Mirrors typical safe upper bounds for hand-written business expressions —
     *  practical formulas rarely exceed a few hundred chars. */
    private static final int MAX_SPEL_LENGTH = 2000;

    /** DoS guard: max parenthesis nesting depth. Anything deeper would either be unreadable
     *  or be a parser stack-overflow attack vector. */
    private static final int MAX_PAREN_DEPTH = 30;

    /** Heuristic ReDoS guard: matches {@code .matches("(.+...)+...")} or {@code .matches("(.+...)*")}
     *  pattern — classic catastrophic-backtracking regex shape. Imperfect but catches obvious cases. */
    private static final java.util.regex.Pattern REDOS_HEURISTIC =
            java.util.regex.Pattern.compile("\\.matches\\s*\\(\\s*[\"'][^\"']*\\([^)]*[+*][^)]*\\)\\s*[+*][^\"']*[\"']");

    /**
     * 求值 SpEL 表达式, 返 boolean.
     *
     * @param spel SpEL 字符串 (e.g. {@code "#order.amount > 10000"})
     * @param variables 变量 map (e.g. {@code {"order": OrderContext, ...}})
     * @return {@code true} 当且仅当结果是 boolean {@code true}; 其他全部 {@code false}
     * @throws SpelEvaluationFailure 当 parse / eval 失败时, 携带 user-friendly hint
     */
    public boolean evaluateBoolean(String spel, Map<String, Object> variables)
            throws SpelEvaluationFailure {
        Objects.requireNonNull(spel, "spel must not be null");
        Object result = evaluate(spel, variables);
        if (result == null) return false;
        if (result instanceof Boolean b) return b;
        // 容忍 truthy-y: e.g. "#own != null" 实际后端可能返非 boolean.
        // 为防误判, 仅 Boolean 视为 true, 其他统一 false.
        log.warn("SpEL 求值结果非 boolean - spel={}, result type={}", spel, result.getClass().getSimpleName());
        return false;
    }

    /**
     * 仅校验 SpEL 表达式语法 + 安全约束 (write-time validation), 不依赖业务变量.
     *
     * <p>使用场景: Controller 在 POST/PUT 持久化之前调用此方法, 防止恶意 SpEL
     * (RCE 攻击如 {@code T(java.lang.Runtime).getRuntime().exec(...)}) 被写入数据库.
     * 等到 scheduler / event listener 触发时才发现 已经为时已晚.
     *
     * <p>实现策略 (两层防御):
     * <ol>
     *   <li><b>静态 pattern 检查</b>: 拒绝 {@code T(}, {@code new }, {@code getClass},
     *       {@code getMethod}, {@code getDeclaredMethod}, {@code @beanName}, {@code #root.}
     *       等已知 RCE / 反射 / Spring bean 访问模式. 即使 SpEL 引擎放过, 静态扫描挡住.</li>
     *   <li><b>沙箱 dry-run</b>: 用 {@link SimpleEvaluationContext#forReadOnlyDataBinding()}
     *       parse + 试求值 (空变量). 任何 {@link SpelParseException} 或
     *       {@link SpelEvaluationException} 都被翻译成友好错误.
     *       注意: 由于变量为空, {@code #foo.bar} 这种合法表达式会因 null property
     *       而抛 SpelEvaluationException — 我们 ignore "Property or field ... cannot be found"
     *       错误, 仅在涉及 forbidden constructs 时才 propagate.</li>
     * </ol>
     *
     * @param spel SpEL 字符串 (caller 应先 null/blank check)
     * @throws SpelEvaluationFailure 当语法错误 / 安全约束违反时, 携带 user-friendly hint
     */
    public void validateSyntax(String spel) throws SpelEvaluationFailure {
        Objects.requireNonNull(spel, "spel must not be null");

        // Layer 0: DoS guards — bound input before any parse/eval work.
        // (a) length cap: protects parser from megabyte payload.
        if (spel.length() > MAX_SPEL_LENGTH) {
            throw new SpelEvaluationFailure(spel,
                    "SpEL 表达式过长 (当前 " + spel.length() + " 字符, 上限 " + MAX_SPEL_LENGTH + ")",
                    "请缩短表达式至 " + MAX_SPEL_LENGTH + " 字符内, 或拆分为多条规则",
                    null);
        }
        // (b) paren depth cap: protects parser stack from deeply nested input
        //     (StackOverflowError is otherwise unrecoverable in Layer 2 dry-run).
        int depth = 0;
        int maxDepth = 0;
        for (int i = 0; i < spel.length(); i++) {
            char c = spel.charAt(i);
            if (c == '(') {
                depth++;
                if (depth > maxDepth) maxDepth = depth;
            } else if (c == ')') {
                depth--;
            }
        }
        if (maxDepth > MAX_PAREN_DEPTH) {
            throw new SpelEvaluationFailure(spel,
                    "SpEL 表达式嵌套过深 (当前 " + maxDepth + " 层, 上限 " + MAX_PAREN_DEPTH + ")",
                    "请简化括号嵌套层级, 或拆分为多条简单规则",
                    null);
        }
        // (c) ReDoS heuristic: scan for .matches("(...+)+...") / .matches("(...+)*...") shapes
        //     that classic regex engines backtrack catastrophically on. Imperfect — only
        //     catches obvious nested-quantifier patterns, but blocks the textbook attack vector.
        if (REDOS_HEURISTIC.matcher(spel).find()) {
            throw new SpelEvaluationFailure(spel,
                    "SpEL 安全检查失败: 正则表达式存在 ReDoS 风险 (嵌套量词模式)",
                    "请避免在 .matches() 内使用 (a+)+ 或 (a+)* 等嵌套量词", null);
        }

        // Layer 1: static pattern scan — defense-in-depth, catches RCE attempts
        // even before SpelExpressionParser sees them.
        String normalized = spel.replaceAll("\\s+", " ").trim();
        // T(...) type reference — used for static method access (T(Runtime).getRuntime())
        if (normalized.matches(".*\\bT\\s*\\(.*")) {
            throw new SpelEvaluationFailure(spel,
                    "SpEL 安全检查失败: 禁止使用 T() 类型引用",
                    "仅支持 #input/#order/#material 等业务字段访问, 禁用 T()/new/反射",
                    null);
        }
        // new ClassName(...) — constructor invocation
        if (normalized.matches(".*\\bnew\\s+[A-Za-z_].*")) {
            throw new SpelEvaluationFailure(spel,
                    "SpEL 安全检查失败: 禁止 new 构造器调用",
                    "仅支持 #input/#order/#material 等业务字段访问, 禁用 T()/new/反射",
                    null);
        }
        // getClass()/getMethod()/getDeclaredMethod()/getMethods() — reflection escape
        if (normalized.matches(".*\\.(getClass|getMethod|getMethods|getDeclaredMethod|getDeclaredMethods|getDeclaredField|getDeclaredFields|forName)\\s*\\(.*")) {
            throw new SpelEvaluationFailure(spel,
                    "SpEL 安全检查失败: 禁止反射调用 (getClass/getMethod/...)",
                    "仅支持 #input/#order/#material 等业务字段访问, 禁用 T()/new/反射",
                    null);
        }
        // @beanName — Spring bean reference (BeanFactoryResolver leak)
        if (normalized.matches(".*@[A-Za-z_].*")) {
            throw new SpelEvaluationFailure(spel,
                    "SpEL 安全检查失败: 禁止 @bean 引用",
                    "仅支持 #input/#order/#material 等业务字段访问, 禁用 T()/new/反射",
                    null);
        }
        // #root — root object access (can leak Context internals)
        if (normalized.matches(".*#root\\b.*")) {
            throw new SpelEvaluationFailure(spel,
                    "SpEL 安全检查失败: 禁止 #root 访问",
                    "仅支持 #input/#order/#material 等业务字段访问, 禁用 T()/new/反射",
                    null);
        }

        // Layer 2: sandbox dry-run — parse + evaluate with empty context.
        // Catches genuine parse errors that pattern scan missed.
        SimpleEvaluationContext ctx = SimpleEvaluationContext
                .forReadOnlyDataBinding()
                .withInstanceMethods()
                .build();
        try {
            Expression expression = parser.parseExpression(spel);
            // Dry-run: 空 context + 空变量. 大多数业务表达式 (#order.amount > 100)
            // 会因变量未注册而抛 SpelEvaluationException "Property or field ...
            // cannot be found on null" — 这是预期行为, 静默 swallow.
            // 任何 forbidden construct (T()/new/getClass) 已在 Layer 1 拦截.
            expression.getValue(ctx);
        } catch (SpelParseException pe) {
            throw new SpelEvaluationFailure(spel,
                    "SpEL 语法错误: " + pe.getSimpleMessage(),
                    "请检查表达式拼写, 例: #order.amount > 10000",
                    pe);
        } catch (SpelEvaluationException ee) {
            // 空 context 下 "Property or field ... cannot be found" 是预期, swallow.
            // 其他 evaluation error (e.g. T() / reflection) propagate as failure.
            String msg = ee.getMessage() == null ? "" : ee.getMessage();
            if (msg.contains("cannot be found") || msg.contains("on null")
                    || msg.contains("not assignable")
                    || msg.contains("Cannot index into a null value")) {
                // expected when running with empty variables — syntax is OK.
                // "Cannot index into a null value" (EL1012E) fires on bracket indexing
                // #order['amount'] when #order is null at dry-run; the syntax is valid,
                // runtime binds Map (per RuleEngineImpl.buildSpelVariables) and resolves correctly.
                return;
            }
            throw new SpelEvaluationFailure(spel,
                    "SpEL 安全检查失败: " + ee.getSimpleMessage(),
                    nextActionHint(ee),
                    ee);
        } catch (ParseException | EvaluationException ge) {
            throw new SpelEvaluationFailure(spel,
                    "SpEL 错误: " + ge.getMessage(),
                    "请检查表达式语法",
                    ge);
        } catch (StackOverflowError soe) {
            // DoS guard (last-resort): Layer 0 paren-depth cap should already have rejected
            // most pathological input, but the parser may still recurse on non-paren
            // constructs (e.g. deeply nested ternary, chained method calls). Catching
            // StackOverflowError keeps the JVM stable — Error not RuntimeException, must
            // be explicit. Reset the local stack frame; do not re-throw as Error.
            throw new SpelEvaluationFailure(spel,
                    "SpEL 表达式嵌套过深, 解析器栈溢出",
                    "请大幅简化嵌套层级 (推荐 < " + MAX_PAREN_DEPTH + " 层)", soe);
        }
    }

    /**
     * 求值 SpEL 表达式, 返原始 Object (供 AIChat workflow_variable_test Tool 用).
     *
     * @throws SpelEvaluationFailure 见 above
     */
    public Object evaluate(String spel, Map<String, Object> variables) throws SpelEvaluationFailure {
        Objects.requireNonNull(spel, "spel must not be null");
        SimpleEvaluationContext ctx = SimpleEvaluationContext
                .forReadOnlyDataBinding()
                .withInstanceMethods()   // 允许 String.length() 等无害实例方法
                .build();
        // forReadOnlyDataBinding() 已经内置 DataBindingPropertyAccessor (read-only).
        // SimpleEvaluationContext 不允许 addPropertyAccessor(), 配置走 builder.
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                ctx.setVariable(entry.getKey(), entry.getValue());
            }
        }
        try {
            Expression expression = parser.parseExpression(spel);
            return expression.getValue(ctx);
        } catch (SpelParseException pe) {
            throw new SpelEvaluationFailure(spel,
                    "SpEL 语法错误: " + pe.getSimpleMessage(),
                    "请检查表达式拼写, 例: #own.role == 'admin'",
                    pe);
        } catch (SpelEvaluationException ee) {
            // SimpleEvaluationContext 拒绝 T()/反射时抛此异常.
            throw new SpelEvaluationFailure(spel,
                    "SpEL 求值失败: " + ee.getSimpleMessage(),
                    nextActionHint(ee),
                    ee);
        } catch (ParseException | EvaluationException ge) {
            throw new SpelEvaluationFailure(spel,
                    "SpEL 错误: " + ge.getMessage(),
                    "请联系平台管理员排查",
                    ge);
        }
    }

    private String nextActionHint(SpelEvaluationException ee) {
        String msg = ee.getMessage() == null ? "" : ee.getMessage();
        if (msg.contains("Property or field") && msg.contains("cannot be found")) {
            return "变量或字段不存在 — 请在变量库 (/api/mobile/{factoryId}/workflow-variables) 查看可用列表";
        }
        if (msg.contains("Type") || msg.contains("class")) {
            return "禁止访问 Java 类型 — 表达式只能引用注册变量 (#own / #order / #customer / #businessEntity)";
        }
        if (msg.contains("not assignable") || msg.contains("immutable")) {
            return "禁止赋值操作 — 工作流条件只能读取变量, 不能 modify";
        }
        return "请检查表达式语法, 或在变量库测试器试运行";
    }

    // ==================== Custom exception ====================

    /**
     * SpEL 求值失败的 user-friendly exception.
     *
     * <p>跟普通 RuntimeException 区别: 携带 user-facing message + actionHint,
     * 调用方可直接 throw 到 GlobalExceptionHandler / 透传到 AIChat Tool result.
     */
    public static class SpelEvaluationFailure extends RuntimeException {
        private final String spel;
        private final String userMessage;
        private final String actionHint;

        public SpelEvaluationFailure(String spel, String userMessage, String actionHint, Throwable cause) {
            super(userMessage + " (expression=" + spel + ")", cause);
            this.spel = spel;
            this.userMessage = userMessage;
            this.actionHint = actionHint;
        }

        public String getSpel() { return spel; }
        public String getUserMessage() { return userMessage; }
        public String getActionHint() { return actionHint; }
    }
}
