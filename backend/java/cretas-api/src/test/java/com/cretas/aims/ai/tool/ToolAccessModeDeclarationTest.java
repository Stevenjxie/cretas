package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.dto.skill.SkillDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * spec §8.2 读写声明化的守门测试。
 *
 * <p>三件事:
 * <ol>
 *   <li><b>fail-closed</b> — 没声明 {@code getAccessMode()} 的工具必须被判成 WRITE;</li>
 *   <li><b>Skill 取最大值</b> — 编排里任一 Tool 是 WRITE, Skill 就是 WRITE, 显式声明也压不低;</li>
 *   <li><b>声明 vs 启发式交叉校验</b> — 存量 590 个工具全覆盖, 且不允许出现
 *       "启发式判 WRITE 而声明 READ" 这种危险方向的矛盾。</li>
 * </ol>
 */
class ToolAccessModeDeclarationTest {

    private static final String TOOL_IMPL_PACKAGE = "com.cretas.aims.ai.tool.impl";

    // ==================== 1. fail-closed ====================

    /** 只实现必需方法, 刻意不覆写 getAccessMode() —— 模拟"新加的工具忘了声明"。 */
    static class UndeclaredTool implements ToolExecutor {
        @Override public String getToolName() { return "undeclared_probe"; }
        @Override public String getDescription() { return "probe"; }
        @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
        @Override public String execute(ToolCall toolCall, Map<String, Object> context) { return "{}"; }
    }

    /** 显式声明只读。 */
    static class DeclaredReadTool extends UndeclaredTool {
        @Override public String getToolName() { return "declared_read_probe"; }
        @Override public AccessMode getAccessMode() { return AccessMode.READ; }
    }

    /** 显式声明写。 */
    static class DeclaredWriteTool extends UndeclaredTool {
        @Override public String getToolName() { return "declared_write_probe"; }
        @Override public AccessMode getAccessMode() { return AccessMode.WRITE; }
    }

    @Test
    @DisplayName("fail-closed: 未声明 getAccessMode 的工具一律判定为 WRITE")
    void undeclaredToolIsTreatedAsWrite() {
        UndeclaredTool tool = new UndeclaredTool();

        // 接口默认值本身就是 WRITE
        assertThat(tool.getAccessMode()).isEqualTo(AccessMode.WRITE);
        // 解析器同样判写 (工具名 undeclared_probe 不在迁移种子表里)
        assertThat(ToolAccessModes.resolve(tool)).isEqualTo(AccessMode.WRITE);
        assertThat(ToolAccessModes.isWrite(tool)).isTrue();
        // W0 写闸因此会拦住它 —— 名字里没有任何写动词后缀, 旧启发式是拦不住的
        assertThat(new WriteGuardService().hasWriteSuffix("undeclared_probe")).isFalse();
        assertThat(new WriteGuardService().isWriteTool(tool)).isTrue();

        // null 工具同样 fail-closed
        assertThat(ToolAccessModes.resolve(null)).isEqualTo(AccessMode.WRITE);
    }

    @Test
    @DisplayName("显式声明 READ 的工具不被写闸拦截; 显式声明 WRITE 的被拦截")
    void explicitDeclarationsAreHonoured() {
        assertThat(ToolAccessModes.resolve(new DeclaredReadTool())).isEqualTo(AccessMode.READ);
        assertThat(new WriteGuardService().isWriteTool(new DeclaredReadTool())).isFalse();

        assertThat(ToolAccessModes.resolve(new DeclaredWriteTool())).isEqualTo(AccessMode.WRITE);
        assertThat(new WriteGuardService().isWriteTool(new DeclaredWriteTool())).isTrue();
    }

    // ==================== 2. Skill 取最大值 ====================

    private static SkillDefinition skill(String accessMode, String... tools) {
        return SkillDefinition.builder()
                .name("probe-skill")
                .accessMode(accessMode)
                .tools(List.of(tools))
                .build();
    }

    private static ToolExecutor stub(String name, AccessMode mode) {
        return mode == AccessMode.READ
                ? new DeclaredReadTool() {
                    @Override public String getToolName() { return name; }
                }
                : new DeclaredWriteTool() {
                    @Override public String getToolName() { return name; }
                };
    }

    @Test
    @DisplayName("Skill 取最大值: 编排里任一 Tool 是 WRITE, Skill 即为 WRITE")
    void skillAccessModeIsMaxOfItsTools() {
        Map<String, ToolExecutor> catalog = new LinkedHashMap<>();
        catalog.put("read_a", stub("read_a", AccessMode.READ));
        catalog.put("read_b", stub("read_b", AccessMode.READ));
        catalog.put("write_c", stub("write_c", AccessMode.WRITE));

        // 全读 → READ
        assertThat(ToolAccessModes.resolveSkill(skill(null, "read_a", "read_b"), catalog::get))
                .isEqualTo(AccessMode.READ);

        // 混一个写 → WRITE
        assertThat(ToolAccessModes.resolveSkill(skill(null, "read_a", "write_c"), catalog::get))
                .isEqualTo(AccessMode.WRITE);

        // 显式声明 READ 也压不低推导出来的 WRITE
        assertThat(ToolAccessModes.resolveSkill(skill("READ", "read_a", "write_c"), catalog::get))
                .isEqualTo(AccessMode.WRITE);

        // 显式声明 WRITE 可以抬高全读编排
        assertThat(ToolAccessModes.resolveSkill(skill("WRITE", "read_a", "read_b"), catalog::get))
                .isEqualTo(AccessMode.WRITE);

        // 编排引用了未注册的工具 → 判不了 → WRITE
        assertThat(ToolAccessModes.resolveSkill(skill(null, "read_a", "ghost_tool"), catalog::get))
                .isEqualTo(AccessMode.WRITE);

        // DAG 的 fallbackTool 也算进编排 —— 主工具只读、失败回落到写工具, 不能漏
        SkillDefinition dag = SkillDefinition.builder()
                .name("dag-skill")
                .executionGraph(List.of(SkillDefinition.ExecutionNode.builder()
                        .id("n1").toolName("read_a").fallbackTool("write_c").build()))
                .build();
        assertThat(ToolAccessModes.collectToolNames(dag)).containsExactly("read_a", "write_c");
        assertThat(ToolAccessModes.resolveSkill(dag, catalog::get)).isEqualTo(AccessMode.WRITE);

        // null skill fail-closed
        assertThat(ToolAccessModes.resolveSkill(null, catalog::get)).isEqualTo(AccessMode.WRITE);
    }

    // ==================== 3. 存量覆盖率 + 声明/启发式交叉校验 ====================

    private record ToolInfo(Class<?> type, String toolName, AccessMode declared, boolean declaredInClass) {
    }

    private static List<ToolInfo> scanRegisteredTools() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ToolExecutor.class));

        List<ToolInfo> tools = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(TOOL_IMPL_PACKAGE)) {
            Class<?> type;
            try {
                type = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new AssertionError("工具类无法加载: " + bd.getBeanClassName(), e);
            }
            if (Modifier.isAbstract(type.getModifiers()) || type.isInterface()) {
                continue;
            }
            // 扫描器同时看到 test-classes: 测试夹具工具 (XxxToolTest$TestableTool) 都是嵌套类,
            // 而真实注册工具无一例外是顶层类 —— 用这个区分, 不必按名字猜。
            if (type.getEnclosingClass() != null) {
                continue;
            }
            boolean inClass;
            try {
                Method m = type.getMethod("getAccessMode");
                // classpath 扫出来的是真实类, 不是 Spring 代理, 所以 declaringClass 可信
                inClass = m.getDeclaringClass() != ToolExecutor.class;
            } catch (NoSuchMethodException e) {
                inClass = false;
            }
            ToolExecutor instance = mock(type.asSubclass(ToolExecutor.class),
                    withSettings().defaultAnswer(CALLS_REAL_METHODS));
            String name;
            AccessMode declared;
            try {
                name = instance.getToolName();
                declared = instance.getAccessMode();
            } catch (Throwable t) {
                throw new AssertionError(
                        "无法读取工具声明 (getToolName/getAccessMode 不应依赖注入字段): " + type.getName(), t);
            }
            tools.add(new ToolInfo(type, name, declared, inClass));
        }
        return tools;
    }

    @Test
    @DisplayName("存量全覆盖: 每个工具都有访问模式声明 (类内覆写 或 迁移种子表)")
    void everyToolDeclaresItsAccessMode() {
        List<ToolInfo> tools = scanRegisteredTools();
        assertThat(tools).as("classpath 上应扫到大量工具, 扫不到说明包名或过滤器坏了").hasSizeGreaterThan(500);

        List<String> undeclared = new ArrayList<>();
        for (ToolInfo t : tools) {
            boolean covered = t.declaredInClass()
                    || (t.toolName() != null
                        && ToolAccessModeSeed.PENDING_IN_CLASS_BACKFILL.containsKey(t.toolName()));
            if (!covered) {
                undeclared.add(t.type().getName() + " (tool=" + t.toolName() + ")");
            }
        }
        assertThat(undeclared)
                .as("这些工具既没在类里声明 getAccessMode(), 也不在迁移种子表里。"
                    + "它们会落到 fail-closed 默认值 WRITE —— 只读工具因此会被咨询 tab 拦掉。"
                    + "请在类里加 @Override getAccessMode()。")
                .isEmpty();
    }

    @Test
    @DisplayName("交叉校验: 不允许出现「启发式判 WRITE 而声明 READ」的危险矛盾")
    void noToolDeclaresReadWhileHeuristicSaysWrite() {
        WriteGuardService heuristic = new WriteGuardService();
        List<ToolInfo> tools = scanRegisteredTools();

        Map<String, String> dangerous = new TreeMap<>();
        for (ToolInfo t : tools) {
            AccessMode effective = t.declaredInClass()
                    ? t.declared()
                    : ToolAccessModeSeed.PENDING_IN_CLASS_BACKFILL
                            .getOrDefault(t.toolName(), AccessMode.WRITE);
            if (effective != AccessMode.READ) {
                continue;
            }
            boolean heuristicWrite = heuristic.isWriteAction(safeActionType(t))
                    || heuristic.hasWriteSuffix(t.toolName());
            if (heuristicWrite) {
                dangerous.put(t.toolName(), t.type().getName());
            }
        }
        assertThat(dangerous)
                .as("声明成 READ 但启发式判 WRITE —— 这个方向的矛盾会让工具绕过 W0 写确认闸。"
                    + "若确认是启发式误报 (如工具名恰好含写动词但确实只读), 请在 WriteGuardService "
                    + "的豁免名单里显式登记, 而不是靠声明单方面放行。")
                .isEmpty();
    }

    private static ToolExecutor.ActionType safeActionType(ToolInfo t) {
        try {
            ToolExecutor instance = mock(t.type().asSubclass(ToolExecutor.class),
                    withSettings().defaultAnswer(CALLS_REAL_METHODS));
            return instance.getActionType();
        } catch (Throwable e) {
            return ToolExecutor.ActionType.READ;
        }
    }

    /**
     * {@code revenue_report_generate} 是全仓库<b>唯一</b>一条"声明 READ 而名称启发式判 WRITE"的例外
     * (它的名字含 {@code _GENERATE} 后缀)。它之所以合法, 是因为 {@link WriteGuardService}
     * 的 {@code NON_DESTRUCTIVE_GENERATE_NAMES} 里有一条<b>显式写下的、有意为之的</b>豁免 ——
     * 不是启发式猜出来的, 也不是回填时"拿不准"留下的。
     *
     * <p>本用例把两侧钉在一起: 任何一侧被改动而另一侧没跟上, 构建就红。没有这道锁, 这条 READ
     * 就退化成一个"能被人顺手改掉的巧合" —— 而它恰好是唯一一条对抗写闸的例外, 必须是契约。
     */
    @Test
    @DisplayName("契约锁: revenue_report_generate 的 READ 声明必须与 WriteGuard 的显式豁免一致")
    void revenueReportGenerateReadDeclarationStaysPinnedToTheExplicitExemption() {
        WriteGuardService guard = new WriteGuardService();
        final String tool = "revenue_report_generate";

        // 1) WriteGuard 侧: 豁免必须还在 —— 若被移出 NON_DESTRUCTIVE_GENERATE_NAMES, 这里先红
        assertThat(guard.hasWriteSuffix(tool))
                .as("WriteGuardService.NON_DESTRUCTIVE_GENERATE_NAMES 里的 '%s' 豁免不见了。"
                    + "豁免既然撤销, 声明侧也必须同步改成 WRITE (见 ToolAccessModeSeed)。", tool)
                .isFalse();

        // 2) 声明侧: 必须仍然是 READ —— 若被改成 WRITE, 说明有人反转了豁免却没动 WriteGuard
        assertThat(ToolAccessModeSeed.PENDING_IN_CLASS_BACKFILL.get(tool))
                .as("'%s' 的声明被改动了。它是唯一一条对抗启发式的 READ 例外, 依据是 WriteGuard 里的"
                    + "显式豁免; 要改必须两侧一起改, 并在 PR 里说明为什么推翻原豁免决定。", tool)
                .isEqualTo(AccessMode.READ);

        // 3) 合起来: 该工具最终不被写闸拦截 (这正是豁免的目的)
        ToolExecutor probe = new UndeclaredTool() {
            @Override public String getToolName() { return tool; }
            @Override public AccessMode getAccessMode() { return AccessMode.READ; }
        };
        assertThat(guard.isWriteTool(probe))
                .as("豁免 + READ 声明 ⇒ 不进写确认闸").isFalse();

        // 4) 唯一性: 除它以外, 不允许再出现"声明 READ 但启发式判 WRITE"的例外。
        //    新增例外必须走 WriteGuard 的显式豁免名单, 并在这里登记。
        assertThat(NON_DESTRUCTIVE_EXEMPTIONS)
                .as("对抗启发式的 READ 例外只允许这一条; 新增请先在 WriteGuardService 里显式豁免")
                .containsExactly(tool);
    }

    /** 与 {@code WriteGuardService.NON_DESTRUCTIVE_GENERATE_NAMES} 一一对应的登记簿。 */
    private static final List<String> NON_DESTRUCTIVE_EXEMPTIONS = List.of("revenue_report_generate");

    @Test
    @DisplayName("迁移种子表只覆盖餐饮包, 且只授予 READ")
    void seedTableStaysScopedToRestaurantPackage() {
        Set<String> seed = ToolAccessModeSeed.PENDING_IN_CLASS_BACKFILL.keySet();
        assertThat(seed).isNotEmpty();

        List<ToolInfo> tools = scanRegisteredTools();
        Map<String, ToolInfo> byName = new LinkedHashMap<>();
        for (ToolInfo t : tools) {
            if (t.toolName() != null) {
                byName.put(t.toolName(), t);
            }
        }
        for (String name : seed) {
            ToolInfo t = byName.get(name);
            assertThat(t).as("种子表里的工具名 '%s' 在 classpath 上找不到 —— 工具被删/改名后请同步清理", name)
                    .isNotNull();
            assertThat(t.type().getName())
                    .as("种子表是餐饮包禁改期的临时豁免, 不该扩散到其它包")
                    .contains(".ai.tool.impl.restaurant.");
        }
    }
}
