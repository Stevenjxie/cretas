package com.cretas.aims.gate;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拿 {@code plannedQuantity} 做 {@code >=} 比较时，<b>必须先判它 {@code > 0}</b>。
 *
 * <h2>为什么需要这一条</h2>
 *
 * <p>{@code planned_quantity} 列在 prod 是 {@code NOT NULL}。存货生产原本可以没有计划数量，
 * 于是 `373d3dbb6d` 把 {@code null} <b>存成了 0</b> —— 从那天起，库里的 0 表示
 * <b>「不知道计划多少」，而不是「计划产 0 个」</b>。
 *
 * <p>后果是所有 {@code X >= plannedQuantity} 的比较<b>恒真</b>。这个洞已经长出来过两次：
 *
 * <ul>
 *   <li>调拨单：{@code SAFETY_STOCK plannedQuantity=0} 展开出全 0 的幽灵调拨单
 *       —— `02c2777d33` / #1239 给 {@code ProductionWorkflowOrchestrator} 加了 loud-fail 守卫。</li>
 *   <li>报工：{@code actual >= planned(0)} 恒真 ⇒ <b>第一次报工就把整个批次关掉</b>，
 *       而批次下面还挂着未完成的工序 → 后续报工一律 409。
 *       2026-08-16 F006 受控走查实测撞到（批次 `10759`，报 3 盒即 `COMPLETED`，w2/w3 仍 `PENDING`）。</li>
 * </ul>
 *
 * <p>修第一处时，全仓另有 <b>10 处</b>比较陆续加上了 {@code > 0} 守卫，
 * 但 {@code WorkReportingServiceImpl} 和 {@code FuturePlanMatchingServiceImpl}
 * <b>两处漏了</b>。⇒ 这正是「改共享结构前先数、改完再数」那条硬约束的形态：
 * 当时只改了一处，而被 0 污染的比较点有十几处。
 *
 * <h2>为什么用 AST 而不是正则</h2>
 *
 * <p>本仓在「用文本数东西」上栽过三次（注解闸把自己的文档数了进去、grep 把 docstring 里
 * 提到函数名的行也数成调用点、实收率闸打中了一个同名但无关的除法）。
 * 每次的修法都是「把正则收窄一点」，于是它下一次又长出来。
 *
 * <p>所以这里直接用 JDK 自带的 {@code com.sun.source} 编译器 Tree API 拿<b>真 AST</b>，
 * 问的是「有没有一个 {@code >=} 二元节点，它的一侧是 {@code compareTo(plannedQuantity)}」，
 * 而不是「这一行里有没有这串字符」。⛔ 不引入任何新依赖。
 */
@DisplayName("planned=0 守卫：拿 plannedQuantity 做 >= 比较前必须先判 > 0")
class PlannedQuantityZeroGuardContractTest {

    private static final Path MAIN_ROOT = Paths.get("src/main/java");

    /** 判「这个表达式说的是计划数量」——在 AST 节点上问，不是在源码行上问。 */
    private static final String PLANNED = "plannedquantity";

    /**
     * 存量豁免。<b>当前为空 —— 这是它应有的状态。</b>
     *
     * <p>建闸当天两处违例（{@code WorkReportingServiceImpl#checkAndCompleteBatch}、
     * {@code FuturePlanMatchingServiceImpl#matchAndAllocate}）已在同一个 PR 里修掉，
     * 所以这道闸<b>一上来就是硬红</b>，不需要棘轮。
     *
     * <p>⛔ 往这里加条目 = 让同一个洞第三次长出来。真要加，必须写明由谁、何时还。
     */
    private static final Set<String> KNOWN_UNGUARDED = Set.<String>of();

    /** 一处「拿 plannedQuantity 做 >= 比较」的位置，形如 {@code 类名#方法名}。 */
    private record Site(String klass, String method) {
        @Override
        public String toString() {
            return klass + "#" + method;
        }
    }

    private static boolean mentionsPlanned(Tree node) {
        if (node == null || node.getKind() == Tree.Kind.STRING_LITERAL) {
            return false; // 字符串字面量不算 —— AST 扫字面量仍然是字符串匹配
        }
        return node.toString().toLowerCase(Locale.ROOT).contains(PLANNED);
    }

    /** 是不是「零」——{@code BigDecimal.ZERO} 或字面量 {@code 0}。 */
    private static boolean isZero(Tree node) {
        if (node == null) {
            return false;
        }
        String s = node.toString();
        return "0".equals(s) || s.endsWith(".ZERO");
    }

    /**
     * planned 是不是<b>直接</b>出现在这一侧（而不是作为 {@code compareTo} 的参数）。
     *
     * <p>🔴 这个区分是本闸的关键，第一版栽在这里：我原本写「这个二元节点里出现了 planned
     * 且出现了 0」就算有守卫，而<b>被守的那个表达式本身</b>
     * （{@code actual.compareTo(planned) >= 0}）恰好同时满足两条 ⇒ 守卫检测器
     * 把它自己要守的东西当成了守卫，闸变成<b>恒真式</b>，删掉真守卫也不红。
     * 是变异把它抓出来的。
     *
     * <p>判别依据是 planned <b>站的位置</b>：守卫里它是 {@code compareTo} 的<b>接收者</b>
     * （{@code planned.compareTo(ZERO)}），危险比较里它是<b>参数</b>
     * （{@code actual.compareTo(planned)}）。
     */
    private static boolean mentionsPlannedDirectly(Tree node) {
        if (!mentionsPlanned(node)) {
            return false;
        }
        if (node instanceof MethodInvocationTree call
                && call.getMethodSelect().toString().endsWith("compareTo")
                && call.getArguments().size() == 1
                && mentionsPlanned(call.getArguments().get(0))) {
            return false; // 这是「拿 planned 当参数比」, 不是「拿 planned 跟 0 比」
        }
        return true;
    }

    /** 方法体里有没有「planned 与 0 比」这道守卫（{@code > 0} / {@code <= 0} / {@code == 0} / {@code compareTo(ZERO)} / {@code signum()}）。 */
    private static boolean hasZeroGuard(MethodTree method) {
        boolean[] found = {false};
        method.accept(new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                String sel = node.getMethodSelect().toString().toLowerCase(Locale.ROOT);
                if (sel.contains(PLANNED)) {
                    // planned.signum()  或  planned.compareTo(ZERO)
                    if (sel.endsWith("signum")) {
                        found[0] = true;
                    }
                    if (sel.endsWith("compareto")
                            && node.getArguments().size() == 1
                            && isZero(node.getArguments().get(0))) {
                        found[0] = true;
                    }
                }
                return super.visitMethodInvocation(node, unused);
            }

            @Override
            public Void visitBinary(BinaryTree node, Void unused) {
                // 基础类型那一档: `getPlannedQuantity() <= 0` / `totalPlannedQuantity == 0`
                if ((isZero(node.getRightOperand()) && mentionsPlannedDirectly(node.getLeftOperand()))
                        || (isZero(node.getLeftOperand()) && mentionsPlannedDirectly(node.getRightOperand()))) {
                    found[0] = true;
                }
                return super.visitBinary(node, unused);
            }
        }, null);
        return found[0];
    }

    /** 找出方法体里所有「X.compareTo(<planned>) >= 0」形状的比较。 */
    private static boolean hasRiskyGreaterEqual(MethodTree method) {
        boolean[] risky = {false};
        method.accept(new TreeScanner<Void, Void>() {
            @Override
            public Void visitBinary(BinaryTree node, Void unused) {
                if (node.getKind() == Tree.Kind.GREATER_THAN_EQUAL
                        && node.getLeftOperand() instanceof MethodInvocationTree call
                        && call.getMethodSelect().toString().endsWith("compareTo")
                        && call.getArguments().size() == 1
                        && mentionsPlanned(call.getArguments().get(0))) {
                    risky[0] = true;
                }
                return super.visitBinary(node, unused);
            }
        }, null);
        return risky[0];
    }

    private List<Site> scan() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("拿不到 JDK 编译器 —— 仪器坏了, 不是仓里没有违例").isNotNull();

        List<Path> sources;
        try (Stream<Path> walk = Files.walk(MAIN_ROOT)) {
            sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }

        List<Site> sites = new ArrayList<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);
            // 只 parse, 不做符号解析 —— 不需要 classpath, 也就不会被编译顺序影响。
            JavacTask task = (JavacTask) compiler.getTask(null, fm, d -> { }, List.of(), null, units);
            for (CompilationUnitTree cu : task.parse()) {
                String file = Paths.get(cu.getSourceFile().toUri()).getFileName().toString();
                String klass = file.substring(0, file.length() - ".java".length());
                cu.accept(new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree node, Void unused) {
                        if (node.getBody() != null
                                && hasRiskyGreaterEqual(node)
                                && !hasZeroGuard(node)) {
                            sites.add(new Site(klass, node.getName().toString()));
                        }
                        return super.visitMethod(node, unused);
                    }
                }, null);
            }
        }
        return sites;
    }

    @Test
    @DisplayName("阳性对照：AST 真的在解析（一个 compareTo(planned) 都找不到最像「一切正常」）")
    void positiveControl() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();

        int[] plannedCompares = {0};
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(MAIN_ROOT)) {
            sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fm, d -> { }, List.of(), null,
                    fm.getJavaFileObjectsFromPaths(sources));
            for (CompilationUnitTree cu : task.parse()) {
                cu.accept(new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        if (node.getMethodSelect().toString().endsWith("compareTo")
                                && node.getArguments().size() == 1
                                && mentionsPlanned(node.getArguments().get(0))) {
                            plannedCompares[0]++;
                        }
                        return super.visitMethodInvocation(node, unused);
                    }
                }, null);
            }
        }
        assertThat(plannedCompares[0])
                .as("AST 里一个 compareTo(plannedQuantity) 都没找到 —— 那是解析没跑起来, "
                        + "不是仓里没有这种比较。「找不到」最像「一切正常」。")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("🔴 拿 plannedQuantity 做 >= 比较的方法，必须同时判过它 > 0")
    void plannedQuantityComparisonsMustGuardAgainstZero() throws IOException {
        Set<String> violations = new TreeSet<>();
        for (Site s : scan()) {
            violations.add(s.toString());
        }
        violations.removeAll(KNOWN_UNGUARDED);

        assertThat(violations)
                .as("""
                        这些方法拿 plannedQuantity 做了 `>= 0` 比较, 但方法里没有判过它 > 0。
                        planned_quantity 在 prod 是 NOT NULL, 存货生产把「没有计划数量」存成 0,
                        所以这里的 0 是【未知】不是【计划 0 个】—— 不守就恒真。
                        已知后果: 报工侧第一次报工就关掉整个批次(F006 批次 10759 实测),
                                  计划匹配侧任何一次分配都判「完全匹配」。
                        修法: 比较前加 `plannedQuantity.compareTo(BigDecimal.ZERO) > 0`,
                              与全仓另外 10 处写法一致。
                        ⛔ 不要往 KNOWN_UNGUARDED 里加 —— 那是让同一个洞第三次长出来。""")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 豁免名单必须为空，加进去要有人还")
    void exemptionListMustStayEmpty() {
        assertThat(KNOWN_UNGUARDED)
                .as("建闸当天两处违例已在同一个 PR 修掉, 这份名单应当保持为空")
                .isEmpty();
    }
}
