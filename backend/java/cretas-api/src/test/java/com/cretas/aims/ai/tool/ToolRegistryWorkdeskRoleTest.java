package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ToolRegistry} 的岗位查询。 */
class ToolRegistryWorkdeskRoleTest {

    /** 最小假执行器：只实现接口必需的方法 + 岗位。 */
    private static ToolExecutor fake(String name, WorkdeskRole role) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return "desc of " + name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public String execute(ToolCall toolCall, Map<String, Object> context) { return "{}"; }
            @Override public WorkdeskRole workdeskRole() { return role; }
        };
    }

    /**
     * toolMap 是 final 且内联初始化的 ConcurrentHashMap —— 不能重新赋值，
     * 只能清空后填充。
     */
    @SuppressWarnings("unchecked")
    private ToolRegistry registryWith(ToolExecutor... executors) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        Field f = ToolRegistry.class.getDeclaredField("toolMap");
        f.setAccessible(true);
        Map<String, ToolExecutor> map = (Map<String, ToolExecutor>) f.get(registry);
        map.clear();
        for (ToolExecutor e : executors) {
            map.put(e.getToolName(), e);
        }
        return registry;
    }

    @Test
    @DisplayName("UT-TRW-01: 只返回该岗位的工具")
    void filtersByRole() throws Exception {
        ToolRegistry registry = registryWith(
                fake("b_purchase", WorkdeskRole.PURCHASER),
                fake("a_quality", WorkdeskRole.QUALITY_SUPERVISOR),
                fake("c_none", null));

        List<ToolExecutor> got = registry.getExecutorsByWorkdeskRole(WorkdeskRole.PURCHASER);

        assertEquals(1, got.size());
        assertEquals("b_purchase", got.get(0).getToolName());
    }

    /** 读出 toolMap 的<b>原始</b>迭代顺序，用来证明 fixture 能区分「排过序」与「没排序」。 */
    @SuppressWarnings("unchecked")
    private List<String> rawIterationOrder(ToolRegistry registry) throws Exception {
        Field f = ToolRegistry.class.getDeclaredField("toolMap");
        f.setAccessible(true);
        return ((Map<String, ToolExecutor>) f.get(registry)).values().stream()
                .map(ToolExecutor::getToolName).toList();
    }

    /**
     * UT-TRW-02 的 fixture 必须满足：ConcurrentHashMap 的原始迭代顺序 ≠ 升序。
     *
     * <p>否则删掉 {@code .sorted(...)} 测试照样过 —— 变异验证实测过这件事：
     * 早先的 3-key fixture（z_tool/a_tool/m_tool）恰好按升序迭代，那条断言
     * 其实什么都没测。下面的前置断言让这种退化<b>显式失败</b>，而不是悄悄变绿。
     */
    private static final List<String> UNSORTED_FIXTURE_NAMES = List.of(
            "zeta_tool", "alpha_tool", "mu_tool", "beta_tool", "omega_tool",
            "kappa_tool", "delta_tool", "sigma_tool");

    @Test
    @DisplayName("UT-TRW-02: 🔴 按 toolName 升序 —— 顺序必须确定, 不能随 map 迭代顺序漂")
    void sortedByToolName() throws Exception {
        ToolExecutor[] fakes = UNSORTED_FIXTURE_NAMES.stream()
                .map(n -> fake(n, WorkdeskRole.PURCHASER))
                .toArray(ToolExecutor[]::new);
        ToolRegistry registry = registryWith(fakes);

        List<String> expected = UNSORTED_FIXTURE_NAMES.stream().sorted().toList();

        // 前置断言: fixture 本身必须能区分排序与不排序。若这条挂了, 说明这批 key
        // 在 ConcurrentHashMap 里恰好按升序迭代 —— 此时下面那条断言是恒真的,
        // 必须换 key 而不是无视。
        assertNotEquals(expected, rawIterationOrder(registry),
                "fixture 退化: toolMap 的原始迭代顺序已经是升序, 这条测试无法区分"
                        + "「排过序」与「没排序」, 换一批 key 再测");

        List<String> names = registry.getExecutorsByWorkdeskRole(WorkdeskRole.PURCHASER)
                .stream().map(ToolExecutor::getToolName).toList();

        assertEquals(expected, names,
                "顺序不确定会让「我能干什么」每次刷新都换一个排法");
    }

    @Test
    @DisplayName("UT-TRW-03: 没有该岗位工具时返回空列表, 不返回 null")
    void emptyWhenNoneMatch() throws Exception {
        ToolRegistry registry = registryWith(fake("c_none", null));

        List<ToolExecutor> got = registry.getExecutorsByWorkdeskRole(WorkdeskRole.PURCHASER);

        assertNotNull(got);
        assertTrue(got.isEmpty());
    }

    @Test
    @DisplayName("UT-TRW-04: role 传 null 抛异常 —— 不得被当作「查全部」")
    void nullRoleThrows() throws Exception {
        ToolRegistry registry = registryWith(fake("b_purchase", WorkdeskRole.PURCHASER));

        assertThrows(IllegalArgumentException.class,
                () -> registry.getExecutorsByWorkdeskRole(null));
    }
}
