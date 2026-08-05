package com.cretas.aims.service.aivalue;

import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.repository.alerts.AlertEventRepository;
import com.cretas.aims.repository.calibration.ToolCallRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiValueSummaryService} 的口径测试。
 *
 * <p>口径断言全部落在这里 —— 它是 AI 工具与 HTTP 端点的<b>唯一</b>定义处，
 * 两个出口的测试只负责各自的渲染。
 */
@ExtendWith(MockitoExtension.class)
class AiValueSummaryServiceTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private AiValueSummaryService service;

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

    private void stubEmpty() {
        when(toolCallRecordRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        when(alertEventRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("UT-AVS-02: 🔴 绝不报金额 —— costInYuan 恒 null 且给出可解释的原因")
    void neverFabricatesMoney() {
        stubEmpty();

        AiValueSummaryService.Summary s = service.summarize(FACTORY_ID, null);

        assertNull(s.costInYuan());
        assertNotNull(s.costUnavailableReason());
        assertFalse(s.costUnavailableReason().isBlank(),
                "没有金额必须是可解释的, 不能只是缺个字段");
        assertTrue(s.costUnavailableReason().contains("单价"), s.costUnavailableReason());
    }

    @Test
    @DisplayName("UT-AVS-03: token 求和 —— null 记 0 而不是跳过该行")
    void sumsTokensTreatingNullAsZero() {
        when(toolCallRecordRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(call(100, 50), call(null, 30), call(7, null)));
        when(alertEventRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());

        AiValueSummaryService.Summary s = service.summarize(FACTORY_ID, null);

        assertEquals(3, s.aiCalls(),
                "3 次调用都要计数 —— 跳过没记 token 的行会让次数与 token 口径对不上");
        assertEquals(107L, s.inputTokens());
        assertEquals(80L, s.outputTokens());
        assertEquals(187L, s.totalTokens());
    }

    @Test
    @DisplayName("UT-AVS-04: 告警按状态分组, 明细带 businessEntityId 可点开")
    void groupsAlertsAndCarriesDrilldown() {
        when(toolCallRecordRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        when(alertEventRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(
                        alert(AlertEventStatus.OPEN, "MB-001"),
                        alert(AlertEventStatus.OPEN, "MB-002"),
                        alert(AlertEventStatus.RESOLVED, "MB-003")));

        AiValueSummaryService.Summary s = service.summarize(FACTORY_ID, null);

        assertEquals(3, s.alertsTotal());
        assertEquals(2, s.alertsByStatus().get("OPEN"));
        assertEquals(1, s.alertsByStatus().get("RESOLVED"));
        assertEquals("MB-001", s.alertDetails().get(0).businessEntityId(),
                "没有 businessEntityId 就点不开, 「可追溯」就只是句口号");
        assertEquals("MATERIAL_BATCH", s.alertDetails().get(0).businessEntityType());
    }

    @Test
    @DisplayName("UT-AVS-05: 🔴 查询失败必须抛出, 不得渲染成「0 次 / 0 条」")
    void repositoryFailureMustNotRenderZeros() {
        when(toolCallRecordRepository.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> service.summarize(FACTORY_ID, null),
                "把查询失败吞掉再报 0, 等于把「查不到」说成「什么都没发生」—— 这是两件事");
    }

    @Test
    @DisplayName("UT-AVS-07: days 越界被夹紧, 不抛异常也不透传")
    void clampsDays() {
        stubEmpty();

        assertEquals(1, service.summarize(FACTORY_ID, 0).windowDays());
        assertEquals(365, service.summarize(FACTORY_ID, 9999).windowDays());
        assertEquals(30, service.summarize(FACTORY_ID, null).windowDays());
    }

    @Test
    @DisplayName("UT-AVS-09: 时间窗真的传给了仓储 —— 否则 days 参数是摆设")
    void passesTimeWindowToRepositories() {
        stubEmpty();

        LocalDateTime before = LocalDateTime.now().minusDays(7).minusMinutes(1);
        service.summarize(FACTORY_ID, 7);
        LocalDateTime after = LocalDateTime.now().minusDays(7).plusMinutes(1);

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(toolCallRecordRepository)
                .findByFactoryIdAndCreatedAtBetween(anyString(), startCaptor.capture(), any());

        LocalDateTime start = startCaptor.getValue();
        assertTrue(start.isAfter(before) && start.isBefore(after),
                "起点应落在 7 天前附近, 实际=" + start);
    }
}
