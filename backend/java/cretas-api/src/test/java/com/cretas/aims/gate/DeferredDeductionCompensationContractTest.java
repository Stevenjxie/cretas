package com.cretas.aims.gate;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>只要还存在「延迟扣减」的录入路径，半成品盘点就必须继续减掉「待小结投料」。</b>
 *
 * <h2>这道闸是一次差点酿成事故的产物</h2>
 *
 * <p>2026-08-16 把<b>报工</b>改成实时入库（{@code YieldReportServiceImpl} 提交即过账）之后，
 * 设计文档里写了一条硬约束：「『待小结投料』语义必须彻底归零，否则盘点双重扣减」，
 * 并计划<b>删掉</b> {@code SemiFinishedStocktakeServiceImpl} 里那段补偿。
 *
 * <p><b>那条硬约束是错的。</b>实测：{@code PendingInterimFeedServiceImpl} 读的是
 * {@code ProcessSheetRow}（<b>文员网页流水单</b>，由 {@code interim_settled_at IS NULL} 界定），
 * <b>不是</b> {@code production_reports}（App 报工）。而 yield 栈根本不写 {@code process_sheet_rows}。
 *
 * <p>⇒ 报工实时化<b>既不制造也不消除</b>待小结投料。文员那条路<b>仍然是延迟扣减</b>
 * （小结未改），所以那段补偿<b>仍然必要</b>。按原计划删掉它，就会亲手制造它注释里写的那个 bug：
 * <b>仓管诚实数少 → 假盘亏 → 假凭证（借6602/贷1405）</b>，且随后小结二次扣减 → 库存虚低。
 *
 * <h2>这道闸守什么</h2>
 *
 * <p>守的是<b>耦合</b>：「存在延迟扣减的录入路径」⟺「盘点会咨询待小结投料」。
 * 两端任何一端单独变化都会红：
 * <ul>
 *   <li>有人（比如照着那份过期的硬约束）删掉盘点的补偿 → 红；</li>
 *   <li>将来文员路也实时化、{@code PendingInterimFeedService} 整体退役 → 也会红，
 *       提醒把这道闸和补偿一起清掉。<b>那时红是对的</b>，不是误报。</li>
 * </ul>
 *
 * <p>⚠️ 判据必须是<b>成对</b>的 —— 只断言「补偿还在」会变成一道永远不许删的闸，
 * 那会挡住将来正当的清理。
 */
@DisplayName("延迟扣减补偿：只要还有延迟扣减的路径，盘点就必须继续减待小结投料")
class DeferredDeductionCompensationContractTest {

    private static final Path MAIN_ROOT = Paths.get("src/main/java");

    /** 延迟扣减的来源：文员流水单里「未小结」的投料行。 */
    private static final String DEFERRED_SOURCE = "findUnsettledStockFeedRows";

    /** 盘点侧的补偿：账面减去待小结投料。 */
    private static final String COMPENSATION = "pendingSemiFeedByBatchNo";

    private Set<String> callersOf(String method) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("拿不到 JDK 编译器 —— 仪器坏了").isNotNull();

        List<Path> sources;
        try (Stream<Path> walk = Files.walk(MAIN_ROOT)) {
            sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }
        Set<String> callers = new TreeSet<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fm, d -> { }, List.of(), null,
                    fm.getJavaFileObjectsFromPaths(sources));
            for (CompilationUnitTree cu : task.parse()) {
                String file = Paths.get(cu.getSourceFile().toUri()).getFileName().toString();
                String klass = file.substring(0, file.length() - ".java".length());
                cu.accept(new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        String sel = node.getMethodSelect().toString();
                        if (sel.equals(method) || sel.endsWith("." + method)) {
                            callers.add(klass);
                        }
                        return super.visitMethodInvocation(node, unused);
                    }
                }, null);
            }
        }
        return callers;
    }

    @Test
    @DisplayName("阳性对照：AST 真的解析到了这两个方法的调用（找不到最像「一切正常」）")
    void positiveControl() throws IOException {
        assertThat(callersOf(DEFERRED_SOURCE))
                .as("一个 " + DEFERRED_SOURCE + " 调用都没找到 —— 那是 AST 没跑起来")
                .isNotEmpty();
    }

    @Test
    @DisplayName("🔴 延迟扣减路径与盘点补偿必须同生共死")
    void deferredDeductionAndCompensationMustCoexist() throws IOException {
        boolean deferredExists = !callersOf(DEFERRED_SOURCE).isEmpty();
        boolean compensated = !callersOf(COMPENSATION).isEmpty();

        assertThat(compensated)
                .as("""
                        文员流水单那条路仍然是【延迟扣减】(报工写待小结投料, 小结才扣),
                        而半成品盘点【没有】减掉待小结投料 ⇒ 仓管诚实数少 → 假盘亏
                        → 假凭证(借6602/贷1405), 且随后小结二次扣减 → 库存虚低。

                        ⚠️ 如果你是照着 2026-08-16 设计文档里那条「待小结投料必须彻底归零」
                           删的 —— 那条硬约束【是错的】, 已订正: 待小结投料来自
                           process_sheet_rows(文员路), 与报工实时化无关。
                           只有文员路也实时化之后, 才轮到删这段补偿。""")
                .isEqualTo(deferredExists);
    }
}
