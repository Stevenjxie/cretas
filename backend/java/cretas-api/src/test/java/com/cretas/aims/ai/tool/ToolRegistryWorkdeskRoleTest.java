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

    @Test
    @DisplayName("UT-TRW-02: 🔴 按 toolName 升序 —— 顺序必须确定, 不能随 map 迭代顺序漂")
    void sortedByToolName() throws Exception {
        ToolRegistry registry = registryWith(
                fake("z_tool", WorkdeskRole.PURCHASER),
                fake("a_tool", WorkdeskRole.PURCHASER),
                fake("m_tool", WorkdeskRole.PURCHASER));

        List<String> names = registry.getExecutorsByWorkdeskRole(WorkdeskRole.PURCHASER)
                .stream().map(ToolExecutor::getToolName).toList();

        assertEquals(List.of("a_tool", "m_tool", "z_tool"), names,
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
