package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import com.cretas.aims.service.notify.NotifyAuditException;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.TemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailSender} — Canvas-Notify Phase 3 follow-up.
 *
 * <p>Covers:
 * <ul>
 *   <li>UT-EM-01: supports(EMAIL) only</li>
 *   <li>UT-EM-02: happy path (real SMTP configured) — 2 recipients → mailSender.send 2x + 2 SENT logs</li>
 *   <li>UT-EM-03: MOCK mode (SMTP unconfigured) — recipients with email get SENT logs but no SMTP call</li>
 *   <li>UT-EM-04: template not found → FAILED + 1 FAILED log</li>
 *   <li>UT-EM-05: render IAE (missing variable) → FAILED per recipient</li>
 *   <li>UT-EM-06: user without email → that recipient FAILED, others succeed</li>
 *   <li>UT-EM-07: SMTP throws MailException → that recipient FAILED with SMTP error</li>
 *   <li>UT-EM-08: empty recipientUserIds → FAILED with "no recipient" message</li>
 *   <li>UT-EM-09: audit log save throws → NotifyAuditException (review High #2/#3)</li>
 * </ul>
 *
 * @since 2026-05-21 (Phase 3 follow-up — real SMTP impl tests)
 */
@ExtendWith(MockitoExtension.class)
class EmailSenderTest {

    private static final String FACTORY_ID = "F001";
    private static final String TEMPLATE_CODE = "PO_APPROVAL_PENDING";

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private NotifyTemplateRepository templateRepository;

    @Mock
    private NotifyLogRepository logRepository;

    @Mock
    private UserRepository userRepository;

    @Spy
    private TemplateEngine templateEngine = new TemplateEngine();

    @InjectMocks
    private EmailSender sender;

    @BeforeEach
    void setUp() {
        // Default: SMTP configured (real mode). Individual tests can clear for MOCK mode.
        ReflectionTestUtils.setField(sender, "smtpHost", "smtp.example.com");
        ReflectionTestUtils.setField(sender, "smtpUsername", "notify@cretas.example");
        ReflectionTestUtils.setField(sender, "fromAddress", "notify@cretas.example");
    }

    private NotifyTemplate buildTemplate() {
        return NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("您有 {{count}} 笔待审单")
                .bodyTemplate("请审核 {{poNumber}}, 金额 {{amount}} 元")
                .channels(List.of(NotifyChannel.EMAIL))
                .build();
    }

    private User buildUser(Long id, String email) {
        User u = new User();
        u.setId(id);
        u.setUsername("user" + id);
        u.setEmail(email);
        return u;
    }

    @Test
    @DisplayName("UT-EM-01: supports(EMAIL) only")
    void supportsEmailOnly() {
        assertTrue(sender.supports(NotifyChannel.EMAIL));
        for (NotifyChannel ch : NotifyChannel.values()) {
            if (ch != NotifyChannel.EMAIL) {
                assertFalse(sender.supports(ch), "should not support " + ch);
            }
        }
    }

    @Test
    @DisplayName("UT-EM-02: happy path real SMTP — 2 recipients → mailSender.send 2x + 2 SENT logs")
    void happyPathRealSmtp() {
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(buildTemplate()));
        when(userRepository.findById(1001L)).thenReturn(Optional.of(buildUser(1001L, "a@example.com")));
        when(userRepository.findById(1002L)).thenReturn(Optional.of(buildUser(1002L, "b@example.com")));

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID,
                List.of(1001L, 1002L),
                List.of(NotifyChannel.EMAIL),
                TEMPLATE_CODE,
                Map.of("count", 2, "poNumber", "PO-001", "amount", 5000));

        NotifyResult result = sender.send(request);

        assertEquals(NotifyChannel.EMAIL, result.channel());
        assertEquals(NotifyStatus.SENT, result.status());
        assertNull(result.errorMsg());

        // Verify mailSender called 2x with rendered content
        ArgumentCaptor<SimpleMailMessage> mailCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, atLeastOnce()).send(mailCaptor.capture());
        assertEquals(2, mailCaptor.getAllValues().size(), "应有 2 次 SMTP 发送");
        SimpleMailMessage first = mailCaptor.getAllValues().get(0);
        assertEquals("您有 2 笔待审单", first.getSubject());
        assertNotNull(first.getText());
        assertTrue(first.getText().contains("PO-001"));
        assertTrue(first.getText().contains("5000"));

        // Verify 2 SENT NotifyLog rows
        ArgumentCaptor<NotifyLog> logCaptor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(logCaptor.capture());
        long sentCount = logCaptor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.SENT)
                .filter(l -> l.getChannel() == NotifyChannel.EMAIL)
                .count();
        assertEquals(2, sentCount);
    }

    @Test
    @DisplayName("UT-EM-03: MOCK mode (SMTP unconfigured) — SENT logs but mailSender NOT called")
    void mockModeWhenSmtpUnconfigured() {
        // Clear SMTP config → MOCK mode
        ReflectionTestUtils.setField(sender, "smtpHost", "");
        ReflectionTestUtils.setField(sender, "smtpUsername", "");

        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(buildTemplate()));
        when(userRepository.findById(1001L)).thenReturn(Optional.of(buildUser(1001L, "a@example.com")));

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L), List.of(NotifyChannel.EMAIL), TEMPLATE_CODE,
                Map.of("count", 1, "poNumber", "PO-002", "amount", 100));

        NotifyResult result = sender.send(request);

        assertEquals(NotifyStatus.SENT, result.status());
        // mailSender NOT called in mock mode
        verify(mailSender, never()).send(any(SimpleMailMessage.class));

        // SENT log with [MOCK] errorMsg
        ArgumentCaptor<NotifyLog> logCaptor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(logCaptor.capture());
        NotifyLog sentLog = logCaptor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.SENT)
                .findFirst()
                .orElseThrow();
        assertNotNull(sentLog.getErrorMsg());
        assertTrue(sentLog.getErrorMsg().contains("[MOCK]"));
    }

    @Test
    @DisplayName("UT-EM-04: template not found → FAILED + 1 FAILED log")
    void templateNotFound() {
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.empty());

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L), List.of(NotifyChannel.EMAIL),
                TEMPLATE_CODE, Map.of());

        NotifyResult result = sender.send(request);

        assertEquals(NotifyStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains(TEMPLATE_CODE));
        // No SMTP call
        verify(mailSender, never()).send(any(SimpleMailMessage.class));

        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(l -> l.getStatus() == NotifyStatus.FAILED));
    }

    @Test
    @DisplayName("UT-EM-05: render IAE (missing variable) → FAILED per recipient")
    void renderFailsMissingVar() {
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(buildTemplate()));

        // params missing 'amount' var
        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L, 1002L), List.of(NotifyChannel.EMAIL),
                TEMPLATE_CODE, Map.of("count", 1, "poNumber", "PO-A"));

        NotifyResult result = sender.send(request);

        assertEquals(NotifyStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("amount"));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));

        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        long failedCount = captor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.FAILED)
                .count();
        assertTrue(failedCount >= 2, "应至少 2 条 FAILED log (per recipient), got " + failedCount);
    }

    @Test
    @DisplayName("UT-EM-06: user without email → that recipient FAILED, others succeed")
    void userMissingEmailIsolated() {
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(buildTemplate()));
        when(userRepository.findById(1001L)).thenReturn(Optional.of(buildUser(1001L, "a@example.com")));
        when(userRepository.findById(1002L)).thenReturn(Optional.of(buildUser(1002L, null))); // no email

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L, 1002L), List.of(NotifyChannel.EMAIL),
                TEMPLATE_CODE, Map.of("count", 1, "poNumber", "PO-A", "amount", 100));

        NotifyResult result = sender.send(request);

        // Aggregate: 1 success + 1 failure → SENT with partial err
        assertEquals(NotifyStatus.SENT, result.status());
        assertNotNull(result.errorMsg());
        assertTrue(result.errorMsg().contains("1/2"));

        // mailSender called only once (for the user with email)
        verify(mailSender, atLeastOnce()).send(any(SimpleMailMessage.class));

        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        long sentCount = captor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.SENT).count();
        long failedCount = captor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.FAILED).count();
        assertEquals(1, sentCount);
        assertEquals(1, failedCount);
    }

    @Test
    @DisplayName("UT-EM-07: SMTP throws MailException → that recipient FAILED with SMTP error")
    void smtpThrowsMailException() {
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(buildTemplate()));
        when(userRepository.findById(1001L)).thenReturn(Optional.of(buildUser(1001L, "a@example.com")));
        doThrow(new MailSendException("connection refused"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L), List.of(NotifyChannel.EMAIL),
                TEMPLATE_CODE, Map.of("count", 1, "poNumber", "PO-A", "amount", 100));

        NotifyResult result = sender.send(request);

        assertEquals(NotifyStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("connection refused")
                || result.errorMsg().contains("SMTP"));

        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        NotifyLog failedLog = captor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.FAILED)
                .findFirst()
                .orElseThrow();
        assertTrue(failedLog.getErrorMsg().contains("SMTP")
                || failedLog.getErrorMsg().contains("connection refused"));
    }

    @Test
    @DisplayName("UT-EM-08: empty recipientUserIds → FAILED with 'no recipient' message")
    void emptyRecipientsFail() {
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(buildTemplate()));

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(), List.of(NotifyChannel.EMAIL),
                TEMPLATE_CODE, Map.of("count", 1, "poNumber", "PO-A", "amount", 100));

        NotifyResult result = sender.send(request);

        assertEquals(NotifyStatus.FAILED, result.status());
        assertNotNull(result.errorMsg());
        assertTrue(result.errorMsg().contains("recipient"));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("UT-EM-09: audit log save throws → NotifyAuditException (review High #2/#3)")
    void auditWriteFailureThrowsNotifyAuditException() {
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(buildTemplate()));
        when(userRepository.findById(1001L)).thenReturn(Optional.of(buildUser(1001L, "a@example.com")));
        when(logRepository.save(any(NotifyLog.class)))
                .thenThrow(new RuntimeException("connection refused"));

        NotifyRequest request = new NotifyRequest(
                FACTORY_ID, List.of(1001L), List.of(NotifyChannel.EMAIL),
                TEMPLATE_CODE, Map.of("count", 1, "poNumber", "PO-A", "amount", 100));

        NotifyAuditException ex = assertThrows(NotifyAuditException.class, () -> sender.send(request));
        assertTrue(ex.getMessage().contains("请联系运维"),
                "errorMsg 应含 next-action 提示, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("EMAIL"),
                "errorMsg 应含 channel 信息, got: " + ex.getMessage());
        assertNotNull(ex.getCause());
        assertEquals("connection refused", ex.getCause().getMessage());
    }
}
