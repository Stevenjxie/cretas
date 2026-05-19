package com.cretas.aims.service.notify;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotifySenderRegistry} — Phase 3 Canvas-Notify Step T6.
 *
 * <p>Covers:
 * <ul>
 *   <li>UT-NR-01: dispatch 路由到正确 sender (channels = IN_APP → InAppSender)</li>
 *   <li>UT-NR-02: 多 channel fan-out → 多个 sender 各被调用 1 次</li>
 *   <li>UT-NR-03: 不支持的 channel → FAILED result + 不调用其他 sender</li>
 *   <li>UT-NR-04: sender 抛异常 → 捕获 + FAILED result, 不阻塞其他 channel</li>
 *   <li>UT-NR-05: sender 抛 NotifyAuditException → 捕获 + FAILED 文案保留 audit-specific 信息,
 *       不阻塞其他 channel (Phase 3 review High #2/#3)</li>
 * </ul>
 *
 * @since 2026-05-18 (Phase 3 impl)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotifySenderRegistryTest {

    private static final String FACTORY_ID = "F001";

    @Mock
    private NotifySender inAppSender;

    @Mock
    private NotifySender emailSender;

    private NotifySenderRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = new NotifySenderRegistry();
        Field sendersField = NotifySenderRegistry.class.getDeclaredField("senders");
        sendersField.setAccessible(true);
        sendersField.set(registry, List.of(inAppSender, emailSender));

        when(inAppSender.supports(NotifyChannel.IN_APP)).thenReturn(true);
        when(emailSender.supports(NotifyChannel.EMAIL)).thenReturn(true);
    }

    @Test
    @DisplayName("UT-NR-01: dispatch IN_APP → InAppSender 调用 1 次")
    void dispatchSingleChannel() {
        NotifyResult expected = new NotifyResult(NotifyChannel.IN_APP, NotifyStatus.SENT, null);
        when(inAppSender.send(any(NotifyRequest.class))).thenReturn(expected);

        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L), List.of(NotifyChannel.IN_APP),
                "T1", Map.of());
        List<NotifyResult> results = registry.sendAll(req);

        assertEquals(1, results.size());
        assertSame(expected, results.get(0));
        verify(inAppSender).send(req);
        verify(emailSender, never()).send(any());
    }

    @Test
    @DisplayName("UT-NR-02: 多 channel fan-out — IN_APP + EMAIL 各 1 次")
    void dispatchMultiChannel() {
        when(inAppSender.send(any())).thenReturn(
                new NotifyResult(NotifyChannel.IN_APP, NotifyStatus.SENT, null));
        when(emailSender.send(any())).thenReturn(
                new NotifyResult(NotifyChannel.EMAIL, NotifyStatus.FAILED, "smtp down"));

        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L),
                List.of(NotifyChannel.IN_APP, NotifyChannel.EMAIL),
                "T1", Map.of());
        List<NotifyResult> results = registry.sendAll(req);

        assertEquals(2, results.size());
        assertEquals(NotifyChannel.IN_APP, results.get(0).channel());
        assertEquals(NotifyChannel.EMAIL, results.get(1).channel());
        verify(inAppSender).send(req);
        verify(emailSender).send(req);
    }

    @Test
    @DisplayName("UT-NR-03: 不支持的 channel → FAILED result, 其他 sender 不被影响调用")
    void unsupportedChannel() throws Exception {
        // Override senders to ONLY have inAppSender — EMAIL has no sender
        Field sendersField = NotifySenderRegistry.class.getDeclaredField("senders");
        sendersField.setAccessible(true);
        sendersField.set(registry, List.of(inAppSender));

        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L),
                List.of(NotifyChannel.EMAIL),
                "T1", Map.of());
        List<NotifyResult> results = registry.sendAll(req);

        assertEquals(1, results.size());
        assertEquals(NotifyStatus.FAILED, results.get(0).status());
        assertNotNull(results.get(0).errorMsg());
        verify(inAppSender, never()).send(any());
    }

    @Test
    @DisplayName("UT-NR-04: sender 抛异常 → 捕获 FAILED, 不阻塞其他 channel")
    void senderThrowsException() {
        when(inAppSender.send(any())).thenThrow(new RuntimeException("DB connection lost"));
        when(emailSender.send(any())).thenReturn(
                new NotifyResult(NotifyChannel.EMAIL, NotifyStatus.SENT, null));

        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L),
                List.of(NotifyChannel.IN_APP, NotifyChannel.EMAIL),
                "T1", Map.of());
        List<NotifyResult> results = registry.sendAll(req);

        assertEquals(2, results.size());
        assertEquals(NotifyStatus.FAILED, results.get(0).status());
        assertEquals("Sender 抛异常: DB connection lost", results.get(0).errorMsg());
        assertEquals(NotifyStatus.SENT, results.get(1).status());
    }

    @Test
    @DisplayName("UT-NR-05: NotifyAuditException → audit 文案保留, 不阻塞其他 channel (review High #2/#3)")
    void senderThrowsNotifyAuditException() {
        String auditMsg = "通知发送审计写入失败 — 请联系运维 (channel=IN_APP, recipient=1001)";
        when(inAppSender.send(any())).thenThrow(
                new NotifyAuditException(auditMsg, new RuntimeException("connection refused")));
        when(emailSender.send(any())).thenReturn(
                new NotifyResult(NotifyChannel.EMAIL, NotifyStatus.SENT, null));

        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L),
                List.of(NotifyChannel.IN_APP, NotifyChannel.EMAIL),
                "T1", Map.of());
        List<NotifyResult> results = registry.sendAll(req);

        assertEquals(2, results.size());
        // IN_APP audit-failure: 保留 audit-specific 文案 (不被 wrap 成 "Sender 抛异常: ...")
        assertEquals(NotifyStatus.FAILED, results.get(0).status());
        assertEquals(auditMsg, results.get(0).errorMsg(),
                "应保留 NotifyAuditException.getMessage() 原文, 不 prepend 'Sender 抛异常:'");
        assertTrue(results.get(0).errorMsg().contains("请联系运维"),
                "errorMsg 必含 next-action 提示 (per fool-proof-design rule)");
        // EMAIL 不被 IN_APP audit failure 影响
        assertEquals(NotifyStatus.SENT, results.get(1).status());
    }
}
