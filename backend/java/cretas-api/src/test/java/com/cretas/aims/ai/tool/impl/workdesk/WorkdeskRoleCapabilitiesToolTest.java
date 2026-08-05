package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WorkdeskRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link WorkdeskRoleCapabilitiesTool}. */
@ExtendWith(MockitoExtension.class)
class WorkdeskRoleCapabilitiesToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private WorkdeskRoleCapabilitiesTool tool;

    @Mock
    private ToolRegistry toolRegistry;

    private static ToolExecutor fake(String name, String desc) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return desc; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public String execute(ToolCall toolCall, Map<String, Object> context) { return "{}"; }
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(Map<String, Object> params) throws Exception {
        Method m = WorkdeskRoleCapabilitiesTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(tool, FACTORY_ID, params, Map.of());
    }

    @Test
    @DisplayName("UT-WRC-01: metadata —— 只读声明必须是 READ (CI 会交叉校验)")
    void metadata() {
        assertEquals("workdesk_role_capabilities", tool.getToolName());
        assertTrue(tool.getDescription().contains("采购员"), tool.getDescription());
        assertEquals(ToolExecutor.AccessMode.READ, tool.getAccessMode());
        assertEquals(ToolExecutor.ActionType.READ, tool.getActionType());
    }

    @Test
    @DisplayName("UT-WRC-02: 列出该岗位的工具, 含名称与描述")
    @SuppressWarnings("unchecked")
    void listsCapabilities() throws Exception {
        when(toolRegistry.getExecutorsByWorkdeskRole(WorkdeskRole.PURCHASER)).thenReturn(List.of(
                fake("workdesk_stock_alert", "采购员低库存预警"),
                fake("workdesk_supplier_eta", "供应商到货预计")));

        Map<String, Object> result = execute(Map.of("role", "采购员"));

        assertEquals("采购员", result.get("role"));
        assertEquals(2, ((Number) result.get("total")).intValue());
        List<Map<String, Object>> caps = (List<Map<String, Object>>) result.get("capabilities");
        assertEquals("workdesk_stock_alert", caps.get(0).get("toolName"));
        assertEquals("采购员低库存预警", caps.get(0).get("description"));
        assertTrue(((String) result.get("message")).contains("采购员"));
        assertTrue(((String) result.get("message")).contains("2"));
    }

    @Test
    @DisplayName("UT-WRC-03: 🔴 认不出的岗位名必须报错, 不得返回空清单")
    void unknownRoleFailsLoud() {
        Exception e = assertThrows(Exception.class, () -> execute(Map.of("role", "采够员")));
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        assertTrue(cause.getMessage() != null && cause.getMessage().contains("采够员"),
                "打错一个字就被告知「你没有可干的事」, 是把输入错误渲染成了空结果: " + cause.getMessage());
    }

    @Test
    @DisplayName("UT-WRC-04: 该岗位确实没有工具时, 说的是「暂无」而不是报错")
    void genuinelyEmptyIsNotAnError() throws Exception {
        when(toolRegistry.getExecutorsByWorkdeskRole(WorkdeskRole.QUALITY_SUPERVISOR))
                .thenReturn(List.of());

        Map<String, Object> result = execute(Map.of("role", "质量主管"));

        assertEquals(0, ((Number) result.get("total")).intValue());
        assertTrue(((String) result.get("message")).contains("暂无"),
                "真的没有 与 名字打错 必须给出不同的话: " + result.get("message"));
    }

    @Test
    @DisplayName("UT-WRC-05: 不传 role 时列出所有岗位及各自数量")
    @SuppressWarnings("unchecked")
    void noRoleListsAllRoles() throws Exception {
        when(toolRegistry.getExecutorsByWorkdeskRole(WorkdeskRole.WAREHOUSE_KEEPER)).thenReturn(List.of());
        when(toolRegistry.getExecutorsByWorkdeskRole(WorkdeskRole.PURCHASER))
                .thenReturn(List.of(fake("a", "d")));
        when(toolRegistry.getExecutorsByWorkdeskRole(WorkdeskRole.QUALITY_SUPERVISOR))
                .thenReturn(List.of(fake("b", "d"), fake("c", "d")));

        Map<String, Object> result = execute(Map.of());

        assertNull(result.get("role"));
        List<Map<String, Object>> roles = (List<Map<String, Object>>) result.get("roles");
        assertEquals(3, roles.size());
        Map<String, Object> purchaser = roles.stream()
                .filter(r -> "采购员".equals(r.get("role"))).findFirst().orElseThrow();
        assertEquals(1, ((Number) purchaser.get("total")).intValue());
        assertEquals(3, ((Number) result.get("total")).intValue());
    }
}
