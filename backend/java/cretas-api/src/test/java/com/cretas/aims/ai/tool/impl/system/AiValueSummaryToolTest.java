package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.repository.alerts.AlertEventRepository;
import com.cretas.aims.repository.calibration.ToolCallRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AiValueSummaryTool}. */
@ExtendWith(MockitoExtension.class)
class AiValueSummaryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private AiValueSummaryTool tool;

    @Mock
    private ToolCallRecordRepository toolCallRecordRepository;

    @Mock
    private AlertEventRepository alertEventRepository;

    private static ToolCallRecord call(Integer in, Integer out) {
        ToolCallRecord r = new ToolCallRecord();
        r.setInputTokens(in);
        r.setOutputTokens(out);
        return r;
    }

    private static AlertEvent alert(AlertEventStatus status, String entityId) {
        AlertEvent a = new AlertEvent();
        a.setStatus(status);
        a.setBusinessEntityType("MATERIAL_BATCH");
        a.setBusinessEntityId(entityId);
        a.setMessage("批次临期");
        return a;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(Map<String, Object> params) throws Exception {
        Method m = AiValueSummaryTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(tool, FACTORY_ID, params, Map.of());
    }

    private void stubEmpty() {
        when(toolCallRecordRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        when(alertEventRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("UT-AVS-01: metadata —— 只读声明必须是 READ (CI 会交叉校验)")
    void metadata() {
        assertEquals("system_ai_value_summary", tool.getToolName());
        assertEquals(ToolExecutor.AccessMode.READ, tool.getAccessMode());
        assertEquals(ToolExecutor.ActionType.READ, tool.getActionType());
        assertTrue(tool.getDescription().contains("不折算金额"), tool.getDescription());
    }

    @Test
    @DisplayName("UT-AVS-02: 🔴 绝不报金额 —— costInYuan 必须为 null 且给出可解释的原因")
    void neverFabricatesMoney() throws Exception {
        stubEmpty();

        Map<String, Object> result = execute(Map.of());

        assertTrue(result.containsKey("costInYuan"),
                "字段必须存在且为 null, 不能让它悄悄消失 —— 消失了调用方会以为忘了实现");
        assertNull(result.get("costInYuan"));
        String reason = (String) result.get("costUnavailableReason");
        assertNotNull(reason);
        assertFalse(reason.isBlank(), "没有金额必须是可解释的, 不能只是缺个字段");
        assertTrue(reason.contains("单价"), reason);
    }

    @Test
    @DisplayName("UT-AVS-03: token 求和 —— null 记 0 而不是跳过该行")
    void sumsTokensTreatingNullAsZero() throws Exception {
        when(toolCallRecordRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(call(100, 50), call(null, 30), call(7, null)));
        when(alertEventRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = execute(Map.of());

        assertEquals(3, ((Number) result.get("aiCalls")).intValue(),
                "3 次调用都要计数 —— 跳过没记 token 的行会让次数与 token 口径对不上");
        assertEquals(107L, ((Number) result.get("inputTokens")).longValue());
        assertEquals(80L, ((Number) result.get("outputTokens")).longValue());
        assertEquals(187L, ((Number) result.get("totalTokens")).longValue());
    }

    @Test
    @DisplayName("UT-AVS-04: 告警按状态分组, 明细带 businessEntityId 可点开")
    @SuppressWarnings("unchecked")
    void groupsAlertsAndCarriesDrilldown() throws Exception {
        when(toolCallRecordRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        when(alertEventRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(
                        alert(AlertEventStatus.OPEN, "MB-001"),
                        alert(AlertEventStatus.OPEN, "MB-002"),
                        alert(AlertEventStatus.RESOLVED, "MB-003")));

        Map<String, Object> result = execute(Map.of());

        assertEquals(3, ((Number) result.get("alertsTotal")).intValue());
        Map<String, Integer> byStatus = (Map<String, Integer>) result.get("alertsByStatus");
        assertEquals(2, byStatus.get("OPEN"));
        assertEquals(1, byStatus.get("RESOLVED"));

        List<Map<String, Object>> details = (List<Map<String, Object>>) result.get("alertDetails");
        assertEquals("MB-001", details.get(0).get("businessEntityId"),
                "没有 businessEntityId 就点不开, 「可追溯」就只是句口号");
        assertEquals("MATERIAL_BATCH", details.get(0).get("businessEntityType"));
    }

    @Test
    @DisplayName("UT-AVS-05: 🔴 查询失败必须抛出, 不得渲染成「0 次 / 0 条」")
    void repositoryFailureMustNotRenderZeros() {
        when(toolCallRecordRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThrows(Exception.class, () -> execute(Map.of()),
                "把查询失败吞掉再报 0, 等于把「查不到」说成「什么都没发生」—— 这是两件事");
    }

    @Test
    @DisplayName("UT-AVS-06: 真的什么都没发生时, 明说「没有被调用过」而不是伪装成运行良好")
    void genuinelyEmptySaysSo() throws Exception {
        stubEmpty();

        String message = (String) execute(Map.of()).get("message");

        assertTrue(message.contains("没有被调用过"), message);
        assertFalse(message.contains("正常"), "0 调用不是「运行正常」: " + message);
    }

    @Test
    @DisplayName("UT-AVS-07: days 越界被夹紧, 不抛异常也不透传")
    void clampsDays() throws Exception {
        stubEmpty();

        assertEquals(1, ((Number) execute(Map.of("days", 0)).get("windowDays")).intValue());
        assertEquals(365, ((Number) execute(Map.of("days", 9999)).get("windowDays")).intValue());
        assertEquals(30, ((Number) execute(Map.of()).get("windowDays")).intValue());
    }

    @Test
    @DisplayName("UT-AVS-08: 有数据时 message 报出三段状态计数")
    void messageReportsLifecycleCounts() throws Exception {
        when(toolCallRecordRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(call(10, 20)));
        when(alertEventRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(
                        alert(AlertEventStatus.OPEN, "MB-001"),
                        alert(AlertEventStatus.ACKNOWLEDGED, "MB-002"),
                        alert(AlertEventStatus.RESOLVED, "MB-003")));

        String message = (String) execute(Map.of()).get("message");

        assertTrue(message.contains("已确认 1 条"), message);
        assertTrue(message.contains("已解决 1 条"), message);
        assertTrue(message.contains("仍敞开 1 条"), message);
        assertTrue(message.contains("不折算金额"), "口径说明必须出现在给人看的那句话里: " + message);
    }

    @Test
    @DisplayName("UT-AVS-09: 时间窗真的传给了仓储 —— 否则 days 参数是摆设")
    void passesTimeWindowToRepositories() throws Exception {
        stubEmpty();

        LocalDateTime before = LocalDateTime.now().minusDays(7).minusMinutes(1);
        execute(Map.of("days", 7));
        LocalDateTime after = LocalDateTime.now().minusDays(7).plusMinutes(1);

        org.mockito.ArgumentCaptor<LocalDateTime> startCaptor =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(toolCallRecordRepository)
                .findByFactoryIdAndCreatedAtBetween(anyString(), startCaptor.capture(), any());

        LocalDateTime start = startCaptor.getValue();
        assertTrue(start.isAfter(before) && start.isBefore(after),
                "起点应落在 7 天前附近, 实际=" + start);
    }
}
