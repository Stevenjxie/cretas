package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.service.aivalue.AiValueSummaryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link AiValueSummaryTool} 的<b>渲染</b>测试。
 *
 * <p>口径断言不在这里 —— 它们在 {@code AiValueSummaryServiceTest}。本类只验
 * 「同一份汇总怎么变成对话里的一句话」，以及 Tool 没有绕过 service 自己查表。
 */
@ExtendWith(MockitoExtension.class)
class AiValueSummaryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private AiValueSummaryTool tool;

    @Mock
    private AiValueSummaryService aiValueSummaryService;

    private static AiValueSummaryService.Summary summary(
            int calls, long tokens, int alertsTotal, Map<String, Integer> byStatus) {
        return new AiValueSummaryService.Summary(
                30, calls, tokens, 0L, tokens, alertsTotal, byStatus,
                List.of(new AiValueSummaryService.AlertDetail(
                        "MATERIAL_BATCH", "MB-001", "WARNING", "OPEN", "批次临期")),
                null, "系统未配置 token 单价，无法把 token 折算成金额。");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(Map<String, Object> params) throws Exception {
        Method m = AiValueSummaryTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(tool, FACTORY_ID, params, Map.of());
    }

    @Test
    @DisplayName("UT-AVT-01: metadata —— 只读声明必须是 READ (CI 会交叉校验)")
    void metadata() {
        assertEquals("system_ai_value_summary", tool.getToolName());
        assertEquals(ToolExecutor.AccessMode.READ, tool.getAccessMode());
        assertEquals(ToolExecutor.ActionType.READ, tool.getActionType());
        assertTrue(tool.getDescription().contains("不折算金额"), tool.getDescription());
    }

    @Test
    @DisplayName("UT-AVT-02: 🔴 口径来自 service —— Tool 不得自己查表")
    void delegatesToService() throws Exception {
        when(aiValueSummaryService.summarize(anyString(), any()))
                .thenReturn(summary(0, 0L, 0, Map.of()));

        execute(Map.of("days", 7));

        verify(aiValueSummaryService).summarize(FACTORY_ID, 7);
        verifyNoMoreInteractions(aiValueSummaryService);
    }

    @Test
    @DisplayName("UT-AVT-03: 金额字段原样透出 null + 原因, 渲染层不得补一个数")
    void passesThroughMoneyAbsence() throws Exception {
        when(aiValueSummaryService.summarize(anyString(), any()))
                .thenReturn(summary(3, 100L, 0, Map.of()));

        Map<String, Object> r = execute(Map.of());

        assertTrue(r.containsKey("costInYuan"), "字段必须存在且为 null, 不能让它悄悄消失");
        assertNull(r.get("costInYuan"));
        assertNotNull(r.get("costUnavailableReason"));
    }

    @Test
    @DisplayName("UT-AVT-04: 真的零活动时明说「没有被调用过」, 不伪装成运行正常")
    void genuinelyEmptySaysSo() throws Exception {
        when(aiValueSummaryService.summarize(anyString(), any()))
                .thenReturn(summary(0, 0L, 0, Map.of()));

        String message = (String) execute(Map.of()).get("message");

        assertTrue(message.contains("没有被调用过"), message);
        assertFalse(message.contains("正常"), "0 调用不是「运行正常」: " + message);
    }

    @Test
    @DisplayName("UT-AVT-05: 有数据时 message 报出三段状态计数与口径说明")
    void messageReportsLifecycleCounts() throws Exception {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        byStatus.put("OPEN", 1);
        byStatus.put("ACKNOWLEDGED", 1);
        byStatus.put("RESOLVED", 1);
        when(aiValueSummaryService.summarize(anyString(), any()))
                .thenReturn(summary(1, 30L, 3, byStatus));

        String message = (String) execute(Map.of()).get("message");

        assertTrue(message.contains("已确认 1 条"), message);
        assertTrue(message.contains("已解决 1 条"), message);
        assertTrue(message.contains("仍敞开 1 条"), message);
        assertTrue(message.contains("不折算金额"), "口径说明必须出现在给人看的那句话里: " + message);
    }

    @Test
    @DisplayName("UT-AVT-06: 明细带 businessEntityId 透到结果里")
    @SuppressWarnings("unchecked")
    void exposesDrilldown() throws Exception {
        when(aiValueSummaryService.summarize(anyString(), any()))
                .thenReturn(summary(1, 30L, 1, Map.of("OPEN", 1)));

        List<Map<String, Object>> details =
                (List<Map<String, Object>>) execute(Map.of()).get("alertDetails");

        assertEquals("MB-001", details.get(0).get("businessEntityId"));
    }
}
