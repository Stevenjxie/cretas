package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import com.cretas.aims.service.notify.NotifySenderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link NotifyTemplateController} — Canvas-Notify Phase 3 follow-up.
 *
 * <p>Replaces the prior stub-asserting test file. Now that create / update / delete
 * have real impls (Phase 3 follow-up issue #41 partial close), this exercises:
 * <ul>
 *   <li>Length validation (AUD-5 B-A3 sister sweep — still in place)</li>
 *   <li>Create success + UNIQUE conflict 409</li>
 *   <li>Update with optimistic lock (version match / mismatch / lenient null)</li>
 *   <li>Delete with factoryId IDOR guard</li>
 *   <li>Cross-factory access guard (404 on factoryId mismatch)</li>
 * </ul>
 *
 * @since 2026-05-20 (AUD-5 B-A3 length pre-check)
 * @since 2026-05-21 (CRUD impl tests — Phase 3 follow-up)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotifyTemplateController CRUD + AUD-5 B-A3 length pre-check")
class NotifyTemplateControllerTest {

    @Mock NotifyTemplateRepository templateRepo;
    @Mock NotifySenderRegistry notifySenderRegistry;
    @InjectMocks NotifyTemplateController controller;

    private static final String FACTORY_ID = "F001";
    private static final String TEMPLATE_CODE = "PO_APPROVAL_PENDING";

    // ==================== testSend: AUD-5 B-A3 length validation ====================

    @Test
    @DisplayName("AUD-5 B-A3: templateCode > 100 字符 → 400 with specific length hint")
    void testLongTemplateCodeRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("templateCode", "X".repeat(101));

        ApiResponse<Map<String, Object>> resp = controller.testSend(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("templateCode"));
        assertTrue(resp.getMessage().contains("100"));
        assertTrue(resp.getMessage().contains("101"));
        assertNotNull(resp.getActionHint());
        assertEquals("warning", resp.getSeverity());
        verify(templateRepo, never()).findByFactoryIdAndTemplateCode(anyString(), anyString());
    }

    @Test
    @DisplayName("AUD-5 B-A3 boundary: templateCode at exactly 100 字符 passes length check")
    void testMaxLengthTemplateCodeAccepted() {
        String exactlyAtCap = "X".repeat(100);
        Map<String, Object> body = new HashMap<>();
        body.put("templateCode", exactlyAtCap);

        ApiResponse<Map<String, Object>> resp = controller.testSend(FACTORY_ID, body);

        assertEquals(404, resp.getCode());
        assertEquals("TEMPLATE_NOT_FOUND", resp.getErrorCode());
        verify(templateRepo).findByFactoryIdAndTemplateCode(FACTORY_ID, exactlyAtCap);
    }

    @Test
    @DisplayName("Pre-existing: blank templateCode still returns specific 400")
    void testBlankTemplateCodeRejectedFirst() {
        Map<String, Object> body = new HashMap<>();
        body.put("templateCode", "");

        ApiResponse<Map<String, Object>> resp = controller.testSend(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("templateCode 必填"));
        verify(templateRepo, never()).findByFactoryIdAndTemplateCode(anyString(), anyString());
    }

    // ==================== POST create ====================

    @Test
    @DisplayName("create: happy path → 201 with saved entity")
    void testCreateHappy() {
        Map<String, Object> body = new HashMap<>();
        body.put("templateCode", TEMPLATE_CODE);
        body.put("title", "审批通知");
        body.put("bodyTemplate", "您有 {{count}} 笔待审");
        body.put("channels", List.of("EMAIL", "IN_APP"));

        when(templateRepo.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.empty());
        when(templateRepo.save(any(NotifyTemplate.class)))
                .thenAnswer(inv -> {
                    NotifyTemplate t = inv.getArgument(0);
                    t.setId(UUID.randomUUID());
                    return t;
                });

        ApiResponse<NotifyTemplate> resp = controller.create(FACTORY_ID, body);

        assertEquals(200, resp.getCode());
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        assertNotNull(resp.getData());
        assertEquals(TEMPLATE_CODE, resp.getData().getTemplateCode());
        assertEquals(FACTORY_ID, resp.getData().getFactoryId());
        assertEquals(2, resp.getData().getChannels().size());
        assertTrue(resp.getData().getChannels().contains(NotifyChannel.EMAIL));
    }

    @Test
    @DisplayName("create: templateCode 缺失 → 400 VALIDATION")
    void testCreateMissingTemplateCode() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "审批通知");

        ApiResponse<NotifyTemplate> resp = controller.create(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("templateCode"));
        verify(templateRepo, never()).save(any());
    }

    @Test
    @DisplayName("create: UNIQUE 冲突 → 409 DUPLICATE")
    void testCreateUniqueConflict() {
        Map<String, Object> body = new HashMap<>();
        body.put("templateCode", TEMPLATE_CODE);

        NotifyTemplate existing = NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .build();
        when(templateRepo.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(existing));

        ApiResponse<NotifyTemplate> resp = controller.create(FACTORY_ID, body);

        assertEquals(409, resp.getCode());
        assertEquals("DUPLICATE", resp.getErrorCode());
        assertTrue(resp.getMessage().contains(TEMPLATE_CODE));
        assertNotNull(resp.getActionHint());
        verify(templateRepo, never()).save(any());
    }

    @Test
    @DisplayName("create: templateCode > 100 → 400 VALIDATION (length cap)")
    void testCreateTemplateCodeTooLong() {
        Map<String, Object> body = new HashMap<>();
        body.put("templateCode", "Z".repeat(101));

        ApiResponse<NotifyTemplate> resp = controller.create(FACTORY_ID, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("100"));
        verify(templateRepo, never()).save(any());
    }

    // ==================== PUT update ====================

    @Test
    @DisplayName("update: happy path with matching version → 200")
    void testUpdateHappyVersionMatch() {
        UUID id = UUID.randomUUID();
        NotifyTemplate existing = NotifyTemplate.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("旧标题")
                .version(3L)
                .build();
        when(templateRepo.findById(id)).thenReturn(Optional.of(existing));
        when(templateRepo.save(any(NotifyTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("title", "新标题");
        body.put("version", 3); // match

        ApiResponse<NotifyTemplate> resp = controller.update(FACTORY_ID, id, body);

        assertEquals(200, resp.getCode());
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        assertEquals("新标题", resp.getData().getTitle());
    }

    @Test
    @DisplayName("update: stale version (client v=2, server v=3) → 409 VERSION_CONFLICT")
    void testUpdateStaleVersion() {
        UUID id = UUID.randomUUID();
        NotifyTemplate existing = NotifyTemplate.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .version(3L)
                .build();
        when(templateRepo.findById(id)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("title", "covertly try to overwrite");
        body.put("version", 2); // stale

        ApiResponse<NotifyTemplate> resp = controller.update(FACTORY_ID, id, body);

        assertEquals(409, resp.getCode());
        assertEquals("VERSION_CONFLICT", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("v=3"));
        assertTrue(resp.getMessage().contains("v=2"));
        assertNotNull(resp.getActionHint());
        verify(templateRepo, never()).save(any());
    }

    @Test
    @DisplayName("update: null version → lenient passthrough (legacy clients)")
    void testUpdateNullVersionLenient() {
        UUID id = UUID.randomUUID();
        NotifyTemplate existing = NotifyTemplate.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .version(3L)
                .build();
        when(templateRepo.findById(id)).thenReturn(Optional.of(existing));
        when(templateRepo.save(any(NotifyTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("title", "新");
        // version absent — legacy client

        ApiResponse<NotifyTemplate> resp = controller.update(FACTORY_ID, id, body);

        assertEquals(200, resp.getCode());
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
    }

    @Test
    @DisplayName("update: factoryId 不一致 (IDOR) → 404")
    void testUpdateCrossFactoryReturns404() {
        UUID id = UUID.randomUUID();
        NotifyTemplate existing = NotifyTemplate.builder()
                .id(id)
                .factoryId("F999")  // different factory
                .templateCode(TEMPLATE_CODE)
                .version(1L)
                .build();
        when(templateRepo.findById(id)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("title", "IDOR attempt");

        ApiResponse<NotifyTemplate> resp = controller.update(FACTORY_ID, id, body);

        assertEquals(404, resp.getCode());
        assertEquals("TEMPLATE_NOT_FOUND", resp.getErrorCode());
        verify(templateRepo, never()).save(any());
    }

    @Test
    @DisplayName("update: id 不存在 → 404")
    void testUpdateNotFound() {
        UUID id = UUID.randomUUID();
        when(templateRepo.findById(id)).thenReturn(Optional.empty());

        ApiResponse<NotifyTemplate> resp = controller.update(FACTORY_ID, id, Map.of("title", "x"));

        assertEquals(404, resp.getCode());
        assertEquals("TEMPLATE_NOT_FOUND", resp.getErrorCode());
        verify(templateRepo, never()).save(any());
    }

    @Test
    @DisplayName("update: partial — only updates fields present in body")
    void testUpdatePartial() {
        UUID id = UUID.randomUUID();
        NotifyTemplate existing = NotifyTemplate.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("旧标题")
                .bodyTemplate("旧 body {{var}}")
                .channels(List.of(NotifyChannel.IN_APP))
                .version(1L)
                .build();
        when(templateRepo.findById(id)).thenReturn(Optional.of(existing));
        ArgumentCaptor<NotifyTemplate> captor = ArgumentCaptor.forClass(NotifyTemplate.class);
        when(templateRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Only update channels, leave title + bodyTemplate as-is
        Map<String, Object> body = new HashMap<>();
        body.put("channels", List.of("EMAIL"));

        ApiResponse<NotifyTemplate> resp = controller.update(FACTORY_ID, id, body);

        assertEquals(200, resp.getCode());
        NotifyTemplate saved = captor.getValue();
        assertEquals("旧标题", saved.getTitle(), "title should remain unchanged");
        assertEquals("旧 body {{var}}", saved.getBodyTemplate(), "bodyTemplate should remain unchanged");
        assertEquals(List.of(NotifyChannel.EMAIL), saved.getChannels(), "channels updated");
    }

    // ==================== DELETE ====================

    @Test
    @DisplayName("delete: happy path → soft-delete with deletedAt set")
    void testDeleteHappy() {
        UUID id = UUID.randomUUID();
        NotifyTemplate existing = NotifyTemplate.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .version(1L)
                .build();
        when(templateRepo.findById(id)).thenReturn(Optional.of(existing));
        ArgumentCaptor<NotifyTemplate> captor = ArgumentCaptor.forClass(NotifyTemplate.class);
        when(templateRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<Void> resp = controller.delete(FACTORY_ID, id);

        assertEquals(200, resp.getCode());
        assertTrue(Boolean.TRUE.equals(resp.getSuccess()));
        assertNotNull(captor.getValue().getDeletedAt(), "soft delete sets deletedAt");
    }

    @Test
    @DisplayName("delete: factoryId 不一致 (IDOR) → 404")
    void testDeleteCrossFactoryReturns404() {
        UUID id = UUID.randomUUID();
        NotifyTemplate existing = NotifyTemplate.builder()
                .id(id)
                .factoryId("F999")
                .templateCode(TEMPLATE_CODE)
                .version(1L)
                .build();
        when(templateRepo.findById(id)).thenReturn(Optional.of(existing));

        ApiResponse<Void> resp = controller.delete(FACTORY_ID, id);

        assertEquals(404, resp.getCode());
        assertEquals("TEMPLATE_NOT_FOUND", resp.getErrorCode());
        verify(templateRepo, never()).save(any());
    }

    @Test
    @DisplayName("delete: id 不存在 → 404")
    void testDeleteNotFound() {
        UUID id = UUID.randomUUID();
        when(templateRepo.findById(id)).thenReturn(Optional.empty());

        ApiResponse<Void> resp = controller.delete(FACTORY_ID, id);

        assertEquals(404, resp.getCode());
        assertEquals("TEMPLATE_NOT_FOUND", resp.getErrorCode());
        verify(templateRepo, never()).save(any());
    }

    // ==================== GET list ====================

    @Test
    @DisplayName("list: returns factory templates from repo")
    void testListDelegatesToRepo() {
        NotifyTemplate t1 = NotifyTemplate.builder()
                .id(UUID.randomUUID()).factoryId(FACTORY_ID).templateCode("A").build();
        NotifyTemplate t2 = NotifyTemplate.builder()
                .id(UUID.randomUUID()).factoryId(FACTORY_ID).templateCode("B").build();
        when(templateRepo.findByFactoryId(FACTORY_ID)).thenReturn(List.of(t1, t2));

        ApiResponse<List<NotifyTemplate>> resp = controller.list(FACTORY_ID);

        assertEquals(200, resp.getCode());
        assertEquals(2, resp.getData().size());
        verify(templateRepo).findByFactoryId(FACTORY_ID);
    }
}
