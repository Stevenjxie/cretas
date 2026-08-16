package com.cretas.aims.gate;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 半成品过账<b>只许发生在报工提交路径上</b>，全仓只能有一个入库时机。
 *
 * <h2>为什么需要这一条</h2>
 *
 * <p>2026-08-16 之前，{@code WipInventoryService.postApprovedOutput} 挂在<b>审批</b>上，
 * 而领用在<b>提交</b>时就已即时扣。这个不对称造成：第②道报完工，料已经扣了、产出还没进库，
 * <b>第③道永远领不到</b>。客户把它描述成「上工序不报工，下工序就没有库存」——
 * 实测表明<b>就算他不漏报也一样领不到</b>。
 *
 * <p>改动本身很小（把调用点从审批搬到提交），但<b>真正的风险是它会被搬回去</b>：
 * {@code postApprovedOutput} 里有 {@code wipPosted} 幂等标记，
 * 所以即使有人在审批处再加一次调用，<b>也不会报错、不会双重入库</b>——
 * 它只会安静地让「审批」重新看起来像一个入库时机，
 * 于是下一个人又会据此推理。⇒ 幂等让这个错误<b>无声</b>，必须由闸来吼。
 *
 * <h2>为什么用 AST 而不是正则</h2>
 *
 * <p>本仓在「用文本数东西」上栽过三次（注解闸把自己的文档数进去、grep 把 docstring 里
 * 提到函数名的行数成调用点、实收率闸打中同名但无关的除法）。每次的修法都是「把正则收窄
 * 一点」，于是它下一次又长出来。这里直接用 JDK 自带的 {@code com.sun.source} 编译器
 * Tree API 拿<b>真 AST</b>，问的是「有没有一个方法调用节点叫这个名字」，
 * 而不是「这一行里有没有这串字符」。⛔ 不引入任何新依赖。
 */
@DisplayName("实时入库：过账只许挂在报工提交路径上")
class RealtimeWipPostingContractTest {

    private static final Path MAIN_ROOT = Paths.get("src/main/java");

    /** 过账方法名。它是「库存真的动了」这件事的唯一入口。 */
    private static final String POSTING = "postApprovedOutput";

    /**
     * 允许调用过账的类。<b>只能有报工提交这一个。</b>
     *
     * <p>⛔ 往这里加一个类 = 又长出第二个入库时机。真要加，必须先回答：
     * 「同一笔产出会不会被两条路各入一次账？」——{@code wipPosted} 幂等标记
     * 会让答案看起来是「不会」，但那只是让错误变得<b>无声</b>，不是让它不存在。
     */
    private static final Set<String> ALLOWED_CALLERS = Set.of("YieldReportServiceImpl");

    /** 定义方（接口 + 实现）不算调用方。 */
    private static final Set<String> DEFINERS = Set.of("WipInventoryService", "WipInventoryServiceImpl");

    private record Call(String klass, String method) {
        @Override
        public String toString() {
            return klass + "#" + method;
        }
    }

    private List<Call> scanCalls() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("拿不到 JDK 编译器 —— 仪器坏了, 不是仓里没有调用").isNotNull();

        List<Path> sources;
        try (Stream<Path> walk = Files.walk(MAIN_ROOT)) {
            sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }

        List<Call> calls = new ArrayList<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            // 只 parse, 不做符号解析 —— 不需要 classpath, 也就不受编译顺序影响。
            JavacTask task = (JavacTask) compiler.getTask(null, fm, d -> { }, List.of(), null,
                    fm.getJavaFileObjectsFromPaths(sources));
            for (CompilationUnitTree cu : task.parse()) {
                String file = Paths.get(cu.getSourceFile().toUri()).getFileName().toString();
                String klass = file.substring(0, file.length() - ".java".length());
                if (DEFINERS.contains(klass)) {
                    continue;
                }
                cu.accept(new TreeScanner<Void, String>() {
                    @Override
                    public Void visitMethod(MethodTree node, String enclosing) {
                        return super.visitMethod(node, node.getName().toString());
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, String enclosing) {
                        String sel = node.getMethodSelect().toString();
                        if (sel.equals(POSTING) || sel.endsWith("." + POSTING)) {
                            calls.add(new Call(klass, enclosing == null ? "<unknown>" : enclosing));
                        }
                        return super.visitMethodInvocation(node, enclosing);
                    }
                }, null);
            }
        }
        return calls;
    }

    @Test
    @DisplayName("阳性对照：真的找得到过账调用（找不到最像「一切正常」）")
    void positiveControl() throws IOException {
        assertThat(scanCalls())
                .as("AST 里一个 " + POSTING + " 调用都没找到 —— 那是解析没跑起来, "
                        + "不是仓里没有过账。「找不到」最像「一切正常」。")
                .isNotEmpty();
    }

    @Test
    @DisplayName("🔴 过账只许出现在报工提交路径上 —— 全仓只能有一个入库时机")
    void postingOnlyOnSubmitPath() throws IOException {
        Set<String> callerClasses = new TreeSet<>();
        scanCalls().forEach(c -> callerClasses.add(c.klass()));

        assertThat(callerClasses)
                .as("""
                        半成品过账出现在了报工提交之外的地方 —— 那就是第二个入库时机。
                        ⚠️ postApprovedOutput 有 wipPosted 幂等标记, 所以多一处调用【不会报错、
                           不会双重入库】, 它只会安静地让那个地方重新看起来像入库时机,
                           下一个人就会据此推理。幂等让这个错误无声, 所以由本闸来吼。
                        修法: 把过账留在 YieldReportServiceImpl 的提交路径上, 删掉别处的调用。""")
                .isEqualTo(ALLOWED_CALLERS);
    }
}
