package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.TemplateEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InAppSender} — Phase 3 Canvas-Notify Step T4.
 *
 * <p>Covers:
 * <ul>
 *   <li>UT-IA-01: supports(IN_APP) = true, supports(其他) = false</li>
 *   <li>UT-IA-02: happy path — 模板存在 + render OK → SENT, 写 NotifyLog per recipient</li>
 *   <li>UT-IA-03: 模板不存在 → FAILED + 写 1 条 FAILED log</li>
 *   <li>UT-IA-04: render 抛 IAE (缺变量) → FAILED + 每 recipient 写 FAILED log</li>
 * </ul>
 *
 * @since 2026-05-18 (Phase 3 impl)
 */
@ExtendWith(MockitoExtension.class)
class InAppSenderTest {

    private static final String FACTORY_ID = "F001";
    private static final String TEMPLATE_CODE = "PO_APPROVAL_PENDING";

    @Mock
    private NotifyTemplateRepository templateRepository;

    @Mock
    private NotifyLogRepository logRepository;

    @Spy
    private TemplateEngine templateEngine = new TemplateEngine();

    @InjectMocks
    private InAppSender sender;

    @Test
    @DisplayName("UT-IA-01: supports(IN_APP) only")
    void supportsInAppOnly() {
        assertTrue(sender.supports(NotifyChannel.IN_APP));
        for (NotifyChannel ch : NotifyChannel.values()) {
            if (ch != NotifyChannel.IN_APP) {
                assertEquals(false, sender.supports(ch), "should not support " + ch);
            }
        }
    }

    @Test
    @DisplayName("UT-IA-02: happy path — 3 recipients → 3 NotifyLog SENT")
    void happyPathThreeRecipients() {
        NotifyTemplate template = NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("您有 {{count}} 笔待审单")
                .bodyTemplate("请审核 {{poNumber}}, 金额 {{amount}} 元")
                .channels(List.of(NotifyChannel.IN_APP))
                .build();
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(template));

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID,
                List.of(1001L, 1002L, 1003L),
                List.of(NotifyChannel.IN_APP),
                TEMPLATE_CODE,
                Map.of("count", 2, "poNumber", "PO-001", "amount", 5000));

        NotifyResult result = sender.send(request);

        assertEquals(NotifyChannel.IN_APP, result.channel());
        assertEquals(NotifyStatus.SENT, result.status());
        assertEquals(null, result.errorMsg());

        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        long sentCount = captor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.SENT)
                .filter(l -> l.getChannel() == NotifyChannel.IN_APP)
                .filter(l -> FACTORY_ID.equals(l.getFactoryId()))
                .count();
        assertEquals(3, sentCount, "应有 3 条 SENT log per recipient");
    }

    @Test
    @DisplayName("UT-IA-03: 模板不存在 → FAILED + 1 条 FAILED log")
    void templateNotFound() {
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.empty());

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L), List.of(NotifyChannel.IN_APP),
                TEMPLATE_CODE, Map.of());

        NotifyResult result = sender.send(request);

        assertEquals(NotifyStatus.FAILED, result.status());
        assertNotNull(result.errorMsg());
        assertTrue(result.errorMsg().contains(TEMPLATE_CODE));

        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(l -> l.getStatus() == NotifyStatus.FAILED));
    }

    @Test
    @DisplayName("UT-IA-04: render 抛 IAE (缺变量) → FAILED per recipient")
    void renderFailsMissingVar() {
        NotifyTemplate template = NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("您有 {{count}} 笔")
                .bodyTemplate("总额 {{amount}}")
                .channels(List.of(NotifyChannel.IN_APP))
                .build();
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(template));

        // params 缺 amount 变量
        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L, 1002L),
                List.of(NotifyChannel.IN_APP), TEMPLATE_CODE,
                Map.of("count", 5));

        NotifyResult result = sender.send(request);

        assertEquals(NotifyStatus.FAILED, result.status());
        assertNotNull(result.errorMsg());

        // 每 recipient 一条 FAILED log
        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        long failedCount = captor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.FAILED)
                .count();
        assertTrue(failedCount >= 2, "应至少 2 条 FAILED log (per recipient), got " + failedCount);
    }
}
