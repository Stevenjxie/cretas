package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import com.cretas.aims.service.notify.NotifyAuditException;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.TemplateEngine;
import com.cretas.aims.service.notify.WeChatNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WeChatSender} — Phase 3 follow-up real impl.
 *
 * <p>Covers:
 * <ul>
 *   <li>UT-WC-01: supports(WECHAT) only</li>
 *   <li>UT-WC-02: happy path — 2 recipients, real notifier call, 2 SENT logs</li>
 *   <li>UT-WC-03: template not found → FAILED log + return FAILED</li>
 *   <li>UT-WC-04: render fails (missing var) → FAILED per recipient</li>
 *   <li>UT-WC-05: empty recipients → FAILED (WeChat requires touser)</li>
 *   <li>UT-WC-06: notifier WeChatNotConfiguredException → FAILED per recipient</li>
 *   <li>UT-WC-07: notifier IOException (HTTP failure) → FAILED per recipient</li>
 *   <li>UT-WC-08: logRepository.save throws → NotifyAuditException</li>
 *   <li>UT-WC-09: buildContent — title + body, body only, neither (defensive)</li>
 *   <li>UT-WC-10: null request → FAILED</li>
 * </ul>
 *
 * @since 2026-05-21 (Phase 3 follow-up)
 */
@ExtendWith(MockitoExtension.class)
class WeChatSenderTest {

    private static final String FACTORY_ID = "F001";
    private static final String TEMPLATE_CODE = "PO_APPROVAL_PENDING";

    @Mock
    private NotifyTemplateRepository templateRepository;

    @Mock
    private NotifyLogRepository logRepository;

    @Spy
    private TemplateEngine templateEngine = new TemplateEngine();

    @Mock
    private WeChatNotifier wechatNotifier;

    @InjectMocks
    private WeChatSender sender;

    @Test
    @DisplayName("UT-WC-01: supports(WECHAT) only")
    void supportsWeChatOnly() {
        assertTrue(sender.supports(NotifyChannel.WECHAT));
        for (NotifyChannel ch : NotifyChannel.values()) {
            if (ch != NotifyChannel.WECHAT) {
                assertFalse(sender.supports(ch), "should not support " + ch);
            }
        }
    }

    @Test
    @DisplayName("UT-WC-02: happy path — 2 recipients → 2 SENT logs, notifier called once")
    void happyPathTwoRecipients() throws Exception {
        NotifyTemplate template = NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("您有 {{count}} 笔待审单")
                .bodyTemplate("请审核 {{poNumber}}, 金额 {{amount}} 元")
                .channels(List.of(NotifyChannel.WECHAT))
                .build();
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(template));
        when(wechatNotifier.send(anyList(), anyString()))
                .thenReturn(Map.of("errcode", 0, "msgid", "msg-001"));

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID,
                List.of(1001L, 1002L),
                List.of(NotifyChannel.WECHAT),
                TEMPLATE_CODE,
                Map.of("count", 1, "poNumber", "PO-001", "amount", 5000));

        NotifyResult result = sender.send(request);

        assertEquals(NotifyChannel.WECHAT, result.channel());
        assertEquals(NotifyStatus.SENT, result.status());
        assertNull(result.errorMsg());

        // Verify notifier called with both userids and rendered content
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> recipientsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(wechatNotifier).send(recipientsCaptor.capture(), contentCaptor.capture());

        assertEquals(List.of("1001", "1002"), recipientsCaptor.getValue(),
                "应转换 Long→String 占位 (Phase 3 follow-up 真实 wechatUserId 映射)");
        String content = contentCaptor.getValue();
        assertTrue(content.contains("您有 1 笔待审单"), "应渲染 title");
        assertTrue(content.contains("PO-001"), "应渲染 body 中的 poNumber");
        assertTrue(content.contains("5000"), "应渲染 body 中的 amount");

        // Verify NotifyLog SENT per recipient
        ArgumentCaptor<NotifyLog> logCaptor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(logCaptor.capture());
        long sentCount = logCaptor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.SENT)
                .filter(l -> l.getChannel() == NotifyChannel.WECHAT)
                .count();
        assertEquals(2, sentCount, "应有 2 条 SENT log per recipient");
    }

    @Test
    @DisplayName("UT-WC-03: template not found → FAILED + 1 FAILED log + notifier NOT called")
    void templateNotFound() throws Exception {
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.empty());

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L), List.of(NotifyChannel.WECHAT),
                TEMPLATE_CODE, Map.of());

        NotifyResult result = sender.send(request);

        assertEquals(NotifyStatus.FAILED, result.status());
        assertNotNull(result.errorMsg());
        assertTrue(result.errorMsg().contains(TEMPLATE_CODE));

        // Notifier should NOT have been called when template is missing
        verify(wechatNotifier, never()).send(anyList(), anyString());

        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(l -> l.getStatus() == NotifyStatus.FAILED));
    }

    @Test
    @DisplayName("UT-WC-04: render fails (missing var) → FAILED per recipient + notifier NOT called")
    void renderFailsMissingVar() throws Exception {
        NotifyTemplate template = NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("您有 {{count}} 笔")
                .bodyTemplate("总额 {{amount}}")
                .channels(List.of(NotifyChannel.WECHAT))
                .build();
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(template));

        // params 缺 amount
        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L, 1002L),
                List.of(NotifyChannel.WECHAT), TEMPLATE_CODE,
                Map.of("count", 5));

        NotifyResult result = sender.send(request);

        assertEquals(NotifyStatus.FAILED, result.status());
        assertNotNull(result.errorMsg());

        verify(wechatNotifier, never()).send(anyList(), anyString());

        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        long failedCount = captor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.FAILED)
                .count();
        assertTrue(failedCount >= 2, "应至少 2 条 FAILED log (per recipient), got " + failedCount);
    }

    @Test
    @DisplayName("UT-WC-05: empty recipients → FAILED (WeChat requires touser)")
    void emptyRecipients() throws Exception {
        NotifyTemplate template = NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("hi")
                .bodyTemplate("body")
                .channels(List.of(NotifyChannel.WECHAT))
                .build();
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(template));

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(), List.of(NotifyChannel.WECHAT),
                TEMPLATE_CODE, Map.of());

        NotifyResult result = sender.send(request);

        assertEquals(NotifyStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("recipientUserIds"));
        verify(wechatNotifier, never()).send(anyList(), anyString());
    }

    @Test
    @DisplayName("UT-WC-06: notifier WeChatNotConfiguredException → FAILED per recipient")
    void notifierNotConfigured() throws Exception {
        NotifyTemplate template = NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("hi")
                .bodyTemplate("body")
                .channels(List.of(NotifyChannel.WECHAT))
                .build();
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(template));
        when(wechatNotifier.send(anyList(), anyString()))
                .thenThrow(new WeChatNotifier.WeChatNotConfiguredException("creds missing"));

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L, 1002L),
                List.of(NotifyChannel.WECHAT), TEMPLATE_CODE, Map.of());

        NotifyResult result = sender.send(request);

        assertEquals(NotifyStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("未配置"),
                "errorMsg 应含 '未配置', got: " + result.errorMsg());

        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        long failedCount = captor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.FAILED)
                .count();
        assertEquals(2, failedCount, "应有 2 条 FAILED log per recipient");
    }

    @Test
    @DisplayName("UT-WC-07: notifier IOException → FAILED per recipient")
    void notifierIoFailure() throws Exception {
        NotifyTemplate template = NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("hi")
                .bodyTemplate("body")
                .channels(List.of(NotifyChannel.WECHAT))
                .build();
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(template));
        when(wechatNotifier.send(anyList(), anyString()))
                .thenThrow(new IOException("connection refused"));

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L),
                List.of(NotifyChannel.WECHAT), TEMPLATE_CODE, Map.of());

        NotifyResult result = sender.send(request);

        assertEquals(NotifyStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("connection refused"),
                "errorMsg 应含 IO 异常详情, got: " + result.errorMsg());
    }

    @Test
    @DisplayName("UT-WC-08: logRepository.save throws → NotifyAuditException (review High #2/#3)")
    void auditWriteFailure() throws Exception {
        NotifyTemplate template = NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("hi")
                .bodyTemplate("body")
                .channels(List.of(NotifyChannel.WECHAT))
                .build();
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(template));
        when(wechatNotifier.send(anyList(), anyString()))
                .thenReturn(Map.of("errcode", 0));
        when(logRepository.save(any(NotifyLog.class)))
                .thenThrow(new RuntimeException("PG down"));

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L),
                List.of(NotifyChannel.WECHAT), TEMPLATE_CODE, Map.of());

        NotifyAuditException ex = assertThrows(NotifyAuditException.class,
                () -> sender.send(request));
        assertTrue(ex.getMessage().contains("请联系运维"),
                "errorMsg 应含 next-action 提示, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("WECHAT"),
                "errorMsg 应含 channel 信息, got: " + ex.getMessage());
        assertNotNull(ex.getCause(), "应保留 root cause");
        assertEquals("PG down", ex.getCause().getMessage());
    }

    @Test
    @DisplayName("UT-WC-09: buildContent — title+body / body-only / empty defensive")
    void buildContentVariants() {
        assertEquals("您有 1 笔待审\n\nPO-001",
                WeChatSender.buildContent("您有 1 笔待审", "PO-001"));
        assertEquals("PO-001 only", WeChatSender.buildContent(null, "PO-001 only"));
        assertEquals("PO-001 only", WeChatSender.buildContent("", "PO-001 only"));
        assertEquals("PO-001 only", WeChatSender.buildContent("  ", "PO-001 only"));
        assertEquals("title only", WeChatSender.buildContent("title only", null));
        assertEquals("title only", WeChatSender.buildContent("title only", ""));
        // Defensive: never send empty content
        assertEquals("(空消息)", WeChatSender.buildContent(null, null));
        assertEquals("(空消息)", WeChatSender.buildContent("", ""));
    }

    @Test
    @DisplayName("UT-WC-10: null request → FAILED no exception")
    void nullRequest() {
        NotifyResult result = sender.send(null);
        assertEquals(NotifyStatus.FAILED, result.status());
        assertEquals(NotifyChannel.WECHAT, result.channel());
    }
}
