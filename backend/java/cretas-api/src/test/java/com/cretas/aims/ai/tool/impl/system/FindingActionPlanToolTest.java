package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.grounding.GroundedNumberValidator;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Unit tests for {@link FindingActionPlanTool}. */
@ExtendWith(MockitoExtension.class)
class FindingActionPlanToolTest {

    private static final String FACTORY_ID = "F006";

    @Mock
    private FindingService findingService;

    @Mock
    private DashScopeClient dashScopeClient;

    /** 用真的校验器 —— 这条链路的价值就在它身上, mock 掉等于没测。 */
    private final GroundedNumberValidator validator = new GroundedNumberValidator();

    private FindingActionPlanTool tool() {
        return new FindingActionPlanTool(findingService, dashScopeClient, validator);
    }

    private static Finding lowStock(String name, int current, int safety, int gap) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("currentStock", current);
        facts.put("safetyStock", safety);
        facts.put("gap", gap);
        facts.put("unit", "kg");
        return new Finding("LOW_STOCK", "inventory", Finding.Severity.WARNING, 50,
                "M-" + name, name, facts);
    }

    private static FindingService.Result result(List<Finding> findings, List<String> failed) {
        return new FindingService.Result(findings, List.of("低库存"), findings.size(),
                Map.of("LOW_STOCK", findings.size()), failed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(FindingActionPlanTool t) throws Exception {
        Method m = FindingActionPlanTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(t, FACTORY_ID, Map.of(), Map.of());
    }

    @Test
    @DisplayName("UT-FAP-01: metadata —— 生成类工具, 只读访问")
    void metadata() {
        FindingActionPlanTool t = tool();
        assertEquals("system_finding_action_plan", t.getToolName());
        assertEquals(ToolExecutor.ActionType.GENERATE, t.getActionType());
        assertEquals(ToolExecutor.AccessMode.READ, t.getAccessMode());
    }

    @Test
    @DisplayName("UT-FAP-02: 建议里的数字都有据时正常返回")
    void returnsGroundedPlan() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory"))
                .thenReturn(result(List.of(lowStock("鲈鱼", 12, 50, 38)), List.of()));
        when(dashScopeClient.chatLowTemp(anyString(), anyString()))
                .thenReturn("· 鲈鱼剩 12kg，低于安全线 50kg\n· 尽快补 38kg");

        Map<String, Object> r = execute(tool());

        assertEquals(true, r.get("hasPlan"));
        assertTrue(((String) r.get("plan")).contains("38kg"));
        assertEquals(1, ((Number) r.get("basedOnFindings")).intValue());
    }

    @Test
    @DisplayName("UT-FAP-03: 🔴 模型编了数字 -> 拒绝返回, 不把文案发出去")
    void rejectsFabricatedNumbers() {
        when(findingService.detectInline(FACTORY_ID, "inventory"))
                .thenReturn(result(List.of(lowStock("鲈鱼", 12, 50, 38)), List.of()));
        when(dashScopeClient.chatLowTemp(anyString(), anyString()))
                .thenReturn("· 尽快补 38kg\n· 预计避免损失 21000 元");

        Exception e = assertThrows(Exception.class, () -> execute(tool()));
        Throwable cause = e.getCause() != null ? e.getCause() : e;

        assertTrue(cause.getMessage().contains("21000"),
                "编了数字的行动建议比没有建议更糟 —— 它会被照着执行: " + cause.getMessage());
    }

    @Test
    @DisplayName("UT-FAP-04: 🔴 发现层没跑完 -> 拒绝生成, 不拿残缺事实指挥行动")
    void refusesWhenDetectionIncomplete() {
        when(findingService.detectInline(FACTORY_ID, "inventory"))
                .thenReturn(result(List.of(lowStock("鲈鱼", 12, 50, 38)), List.of("临期")));

        Exception e = assertThrows(Exception.class, () -> execute(tool()));
        Throwable cause = e.getCause() != null ? e.getCause() : e;

        assertTrue(cause.getMessage().contains("临期"), cause.getMessage());
        verifyNoInteractions(dashScopeClient);
    }

    @Test
    @DisplayName("UT-FAP-05: 无异常时不调模型, 明说无需建议")
    void noFindingsSkipsLlm() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory"))
                .thenReturn(result(List.of(), List.of()));

        Map<String, Object> r = execute(tool());

        assertEquals(false, r.get("hasPlan"));
        assertNull(r.get("plan"));
        assertTrue(((String) r.get("message")).contains("无需"));
        verifyNoInteractions(dashScopeClient);
    }

    @Test
    @DisplayName("UT-FAP-06: 🔴 模型返回空 -> 拒绝, 不返回空方案冒充成功")
    void rejectsEmptyModelOutput() {
        when(findingService.detectInline(FACTORY_ID, "inventory"))
                .thenReturn(result(List.of(lowStock("鲈鱼", 12, 50, 38)), List.of()));
        when(dashScopeClient.chatLowTemp(anyString(), anyString())).thenReturn("   ");

        assertThrows(Exception.class, () -> execute(tool()));
    }

    @Test
    @DisplayName("UT-FAP-07: 提示词明确禁止数字编号 —— 严格校验的前提")
    void promptForbidsNumberedLists() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory"))
                .thenReturn(result(List.of(lowStock("鲈鱼", 12, 50, 38)), List.of()));
        when(dashScopeClient.chatLowTemp(anyString(), anyString())).thenReturn("· 补 38kg");

        execute(tool());

        org.mockito.ArgumentCaptor<String> sys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(dashScopeClient).chatLowTemp(sys.capture(), anyString());
        String prompt = sys.getValue();
        assertTrue(prompt.contains("禁止使用"), prompt);
        assertTrue(prompt.contains("·"), "必须要求「·」分点, 否则序号会逼校验开后门: " + prompt);
    }
}
