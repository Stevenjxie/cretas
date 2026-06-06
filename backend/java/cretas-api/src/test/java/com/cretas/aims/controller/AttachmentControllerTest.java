package com.cretas.aims.controller;

import com.cretas.aims.dto.attachment.LinkChipCountsDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.entity.Attachment.FileCategory;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.security.AttachmentPermissionResolver;
import com.cretas.aims.service.attachment.AttachmentService;
import com.cretas.aims.service.attachment.dto.RegisterAttachmentRequest;
import com.cretas.aims.service.attachment.dto.UpdateAttachmentRequest;
import com.cretas.aims.service.attachment.dto.UploadUrlResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AttachmentController 单元测试 — C-ATT-1 Day 5.
 *
 * 覆盖 8 endpoint 的 controller 层契约: 路径绑定 / DTO 解析 / 响应包装 /
 * 302 redirect / stripQuery 在 fileUrl 派生.
 *
 * <p>RBAC 行为在 {@link AttachmentControllerRBACTest} 单独覆盖, 这里 resolver
 * 默认是 silent mock (调用不抛异常 = "管理员行为"), 让契约测试聚焦 controller 编排.
 *
 * @author Cretas Team — Track C
 * @since 2026-05-15
 */
@DisplayName("AttachmentController 单元测试")
@ExtendWith(MockitoExtension.class)
class AttachmentControllerTest {

    @Mock AttachmentService attachmentService;
    @Mock AttachmentPermissionResolver permissionResolver;
    @Mock AttachmentRepository attachmentRepository;
    @Mock HttpServletRequest request;
    @InjectMocks AttachmentController controller;

    private static final String FACTORY_ID = "F006";
    private static final String ATT_ID = "att-uuid-001";

    private void stubCurrentUser() {
        User user = new User();
        user.setId(42L);
        when(permissionResolver.resolveCurrentUser(request)).thenReturn(user);
    }

    @Test
    @DisplayName("✅ GET /attachments?entityType=PURCHASE_ORDER&entityId=PO-001 返列表")
    void listByEntity_returnsList() {
        Attachment a = new Attachment();
        a.setId(ATT_ID);
        when(attachmentService.queryByEntity(FACTORY_ID, EntityType.PURCHASE_ORDER, "PO-001"))
                .thenReturn(List.of(a));

        ResponseEntity<ApiResponse<List<Attachment>>> resp =
                controller.listByEntity(FACTORY_ID, EntityType.PURCHASE_ORDER, "PO-001", request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().getSuccess());
        assertEquals(1, resp.getBody().getData().size());
        assertEquals(ATT_ID, resp.getBody().getData().get(0).getId());
        verify(permissionResolver).requireRead(any(), eq(EntityType.PURCHASE_ORDER));
    }

    @Test
    @DisplayName("✅ GET /attachments/{id} 返详情")
    void getById_returnsAttachment() {
        Attachment a = new Attachment();
        a.setId(ATT_ID);
        a.setEntityType(EntityType.PURCHASE_ORDER);
        when(attachmentService.getById(FACTORY_ID, ATT_ID)).thenReturn(a);

        ResponseEntity<ApiResponse<Attachment>> resp = controller.getById(FACTORY_ID, ATT_ID, request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(ATT_ID, resp.getBody().getData().getId());
        verify(permissionResolver).requireRead(any(), eq(EntityType.PURCHASE_ORDER));
    }

    @Test
    @DisplayName("✅ GET /attachments/{id}/download 返 302 + Location 签名 URL")
    void download_returns302WithSignedUrl() {
        Attachment a = new Attachment();
        a.setId(ATT_ID);
        a.setEntityType(EntityType.PURCHASE_ORDER);
        when(attachmentService.getById(FACTORY_ID, ATT_ID)).thenReturn(a);
        String signedUrl = "https://cretas-media.oss-cn-shanghai.aliyuncs.com/x?Signature=s&Expires=t";
        when(attachmentService.generateDownloadUrl(FACTORY_ID, ATT_ID)).thenReturn(signedUrl);

        ResponseEntity<Void> resp = controller.download(FACTORY_ID, ATT_ID, request);

        assertEquals(HttpStatus.FOUND, resp.getStatusCode());
        assertEquals(signedUrl, resp.getHeaders().getLocation().toString());
        verify(permissionResolver).requireRead(any(), eq(EntityType.PURCHASE_ORDER));
    }

    @Test
    @DisplayName("✅ POST /attachments/upload-url 返 { uploadUrl, fileUrl } — fileUrl 是 stripQuery 结果")
    void generateUploadUrl_returnsBothUrls() {
        String presigned = "https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/attachments/2026/05/15/abc.jpg?Signature=s&Expires=t";
        when(attachmentService.generateUploadUrl(eq(FACTORY_ID), eq("test.jpg"), eq("image/jpeg")))
                .thenReturn(presigned);

        AttachmentController.UploadUrlRequest req = new AttachmentController.UploadUrlRequest();
        req.setFileName("test.jpg");
        req.setFileType("image/jpeg");

        ResponseEntity<ApiResponse<UploadUrlResponse>> resp =
                controller.generateUploadUrl(FACTORY_ID, req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        UploadUrlResponse data = resp.getBody().getData();
        assertEquals(presigned, data.getUploadUrl(), "uploadUrl = 完整签名 URL");
        assertEquals("https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/attachments/2026/05/15/abc.jpg",
                data.getFileUrl(),
                "fileUrl = stripQuery (去除签名查询串)");
        verify(permissionResolver).validateUploadRequest("image/jpeg", null);
    }

    @Test
    @DisplayName("✅ POST /attachments 注册 — 路径 factoryId 透传给 service")
    void register_passesFactoryIdToService() {
        stubCurrentUser();
        RegisterAttachmentRequest req = new RegisterAttachmentRequest();
        req.setEntityType(EntityType.PURCHASE_ORDER);
        req.setEntityId("PO-001");
        req.setFileName("a.jpg");
        req.setFileUrl("https://x/a.jpg");
        req.setFileSize(100L);
        req.setFileType("image/jpeg");

        Attachment saved = new Attachment();
        saved.setId(ATT_ID);
        when(attachmentService.register(eq(FACTORY_ID), any(RegisterAttachmentRequest.class), any()))
                .thenReturn(saved);

        ResponseEntity<ApiResponse<Attachment>> resp = controller.register(FACTORY_ID, req, request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(ATT_ID, resp.getBody().getData().getId());

        ArgumentCaptor<RegisterAttachmentRequest> cap = ArgumentCaptor.forClass(RegisterAttachmentRequest.class);
        verify(attachmentService).register(eq(FACTORY_ID), cap.capture(), any());
        assertEquals(EntityType.PURCHASE_ORDER, cap.getValue().getEntityType());
        assertEquals("PO-001", cap.getValue().getEntityId());
        verify(permissionResolver).requireWrite(any(), eq(EntityType.PURCHASE_ORDER));
    }

    @Test
    @DisplayName("✅ PUT /attachments/{id} 更新")
    void update_invokesService() {
        stubCurrentUser();
        Attachment existing = new Attachment();
        existing.setId(ATT_ID);
        existing.setEntityType(EntityType.PURCHASE_ORDER);
        when(attachmentService.getById(FACTORY_ID, ATT_ID)).thenReturn(existing);

        UpdateAttachmentRequest req = new UpdateAttachmentRequest();
        req.setDescription("new desc");
        Attachment updated = new Attachment();
        updated.setId(ATT_ID);
        updated.setDescription("new desc");
        when(attachmentService.update(eq(FACTORY_ID), eq(ATT_ID), any(UpdateAttachmentRequest.class), any()))
                .thenReturn(updated);

        ResponseEntity<ApiResponse<Attachment>> resp = controller.update(FACTORY_ID, ATT_ID, req, request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("new desc", resp.getBody().getData().getDescription());
        verify(permissionResolver).requireWrite(any(), eq(EntityType.PURCHASE_ORDER));
    }

    @Test
    @DisplayName("✅ DELETE /attachments/{id} 软删, 返成功消息")
    void softDelete_returnsSuccessMessage() {
        ResponseEntity<ApiResponse<Void>> resp = controller.softDelete(FACTORY_ID, ATT_ID, request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().getSuccess());
        verify(attachmentService).softDelete(eq(FACTORY_ID), eq(ATT_ID), any());
    }

    @Test
    @DisplayName("✅ POST /attachments/batch-by-entity 返计数 Map")
    void batchCount_returnsMap() {
        Map<String, Long> mockCounts = Map.of("PO-001", 3L, "PO-002", 7L);
        when(attachmentService.countByEntities(FACTORY_ID, EntityType.PURCHASE_ORDER, List.of("PO-001", "PO-002")))
                .thenReturn(mockCounts);

        AttachmentController.BatchCountRequest req = new AttachmentController.BatchCountRequest();
        req.setEntityType(EntityType.PURCHASE_ORDER);
        req.setEntityIds(List.of("PO-001", "PO-002"));

        ResponseEntity<ApiResponse<Map<String, Long>>> resp = controller.batchCount(FACTORY_ID, req, request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().getData().size());
        assertEquals(3L, resp.getBody().getData().get("PO-001"));
        assertEquals(7L, resp.getBody().getData().get("PO-002"));
        verify(permissionResolver).requireRead(any(), eq(EntityType.PURCHASE_ORDER));
    }

    @Test
    @DisplayName("❌ getById 不存在 — Service 抛 BusinessException 404 沿 controller 边界透传 (由 GlobalExceptionHandler 转 HTTP)")
    void getById_notFound_propagatesException() {
        when(attachmentService.getById(FACTORY_ID, ATT_ID))
                .thenThrow(new BusinessException(404, "附件不存在"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.getById(FACTORY_ID, ATT_ID, request));
        assertEquals(404, ex.getCode());
    }

    // ==================== Sprint 6 W3-A — 3-chip batch count ====================

    @Test
    @DisplayName("✅ POST /attachments/batch-3chip-counts 按 fileCategory 分桶 (DOCUMENT/OTHER → file, PHOTO/VIDEO → image, CONTRACT → contract)")
    void batch3ChipCounts_groupsCategoriesIntoThreeChips() {
        // Mock grouped repository response: 2 SO ids × multiple categories.
        // SO-001: 2 DOCUMENT + 1 OTHER + 3 PHOTO + 1 CONTRACT + 1 VOUCHER (skipped)
        // SO-002: 2 VIDEO + 0 of everything else
        List<Object[]> mockGrouped = List.of(
                new Object[]{"SO-001", FileCategory.DOCUMENT, 2L},
                new Object[]{"SO-001", FileCategory.OTHER, 1L},
                new Object[]{"SO-001", FileCategory.PHOTO, 3L},
                new Object[]{"SO-001", FileCategory.CONTRACT, 1L},
                new Object[]{"SO-001", FileCategory.VOUCHER, 1L},  // intentionally skipped
                new Object[]{"SO-002", FileCategory.VIDEO, 2L}
        );
        when(attachmentRepository.countByEntitiesGroupedByCategory(
                eq(FACTORY_ID), eq(EntityType.SALES_ORDER), eq(List.of("SO-001", "SO-002", "SO-003"))))
                .thenReturn(mockGrouped);

        AttachmentController.Batch3ChipCountRequest req = new AttachmentController.Batch3ChipCountRequest();
        req.setEntityType(EntityType.SALES_ORDER);
        req.setEntityIds(List.of("SO-001", "SO-002", "SO-003"));

        ResponseEntity<ApiResponse<List<LinkChipCountsDTO>>> resp =
                controller.batch3ChipCounts(FACTORY_ID, req, request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<LinkChipCountsDTO> data = resp.getBody().getData();
        assertEquals(3, data.size(), "all 3 ids returned (incl. SO-003 with zeros)");

        // Preserved input order check + category mapping.
        LinkChipCountsDTO so001 = data.get(0);
        assertEquals("SO-001", so001.getEntityId());
        assertEquals(3L, so001.getFileCount(), "DOCUMENT(2) + OTHER(1) → file");
        assertEquals(3L, so001.getImageCount(), "PHOTO(3) only (no VIDEO) → image");
        assertEquals(1L, so001.getContractCount(), "CONTRACT(1) → contract");
        // VOUCHER(1) NOT counted — sum is 7, not 8.
        assertEquals(7L,
                so001.getFileCount() + so001.getImageCount() + so001.getContractCount(),
                "VOUCHER and SIGNATURE NOT in 3-chip total");

        LinkChipCountsDTO so002 = data.get(1);
        assertEquals("SO-002", so002.getEntityId());
        assertEquals(0L, so002.getFileCount());
        assertEquals(2L, so002.getImageCount(), "VIDEO(2) → image");
        assertEquals(0L, so002.getContractCount());

        LinkChipCountsDTO so003 = data.get(2);
        assertEquals("SO-003", so003.getEntityId());
        assertEquals(0L, so003.getFileCount());
        assertEquals(0L, so003.getImageCount());
        assertEquals(0L, so003.getContractCount());

        verify(permissionResolver).requireRead(any(), eq(EntityType.SALES_ORDER));
    }

    @Test
    @DisplayName("❌ POST /attachments/batch-3chip-counts > 500 ids 抛 BusinessException 400")
    void batch3ChipCounts_over500Ids_throws400() {
        AttachmentController.Batch3ChipCountRequest req = new AttachmentController.Batch3ChipCountRequest();
        req.setEntityType(EntityType.SALES_ORDER);
        // 501 ids -> over cap
        List<String> tooMany = new java.util.ArrayList<>(501);
        for (int i = 0; i < 501; i++) tooMany.add("SO-" + i);
        req.setEntityIds(tooMany);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.batch3ChipCounts(FACTORY_ID, req, request));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("500"), "error message mentions cap");

        // Repo never queried for over-cap input.
        verify(attachmentRepository, never()).countByEntitiesGroupedByCategory(
                anyString(), any(EntityType.class), anyList());
    }

    @Test
    @DisplayName("✅ POST /attachments/batch-3chip-counts 空 entityIds 返空 List, 不查 DB")
    void batch3ChipCounts_emptyIds_returnsEmptyWithoutDbHit() {
        AttachmentController.Batch3ChipCountRequest req = new AttachmentController.Batch3ChipCountRequest();
        req.setEntityType(EntityType.INVENTORY);
        req.setEntityIds(List.of());

        ResponseEntity<ApiResponse<List<LinkChipCountsDTO>>> resp =
                controller.batch3ChipCounts(FACTORY_ID, req, request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().getData().isEmpty());
        verify(attachmentRepository, never()).countByEntitiesGroupedByCategory(
                anyString(), any(EntityType.class), anyList());
    }
}
