package com.cretas.aims.service.attachment.impl;

import com.aliyun.oss.OSS;
import com.cretas.aims.config.OssConfig;
import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.attachment.dto.RegisterAttachmentRequest;
import com.cretas.aims.service.attachment.dto.UpdateAttachmentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AttachmentServiceImpl 单元测试 — C-ATT-1 Day 3.
 *
 * 覆盖: register (成功 / dedup / 未登录) / queryByEntity / countByEntities /
 * getById (not found) / softDelete (owner / non-owner / 未登录) / update.
 *
 * @author Cretas Team — Track C
 * @since 2026-05-15
 */
@DisplayName("AttachmentServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class AttachmentServiceImplTest {

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private ProductionReportRepository productionReportRepository;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private OssService ossService;

    @Mock
    private OssConfig ossConfig;

    @Mock
    private OSS ossClient;

    private AttachmentServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String OTHER_FACTORY = "F001";
    private static final Long UPLOADER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final String ATTACHMENT_ID = "att-uuid-001";

    @BeforeEach
    void setUp() {
        service = new AttachmentServiceImpl(attachmentRepository, productionReportRepository,
                purchaseOrderRepository, ossService, ossConfig);
        PurchaseOrder order = new PurchaseOrder();
        order.setId("PO-20260514-001");
        order.setFactoryId(FACTORY_ID);
        lenient().when(purchaseOrderRepository.findById(anyString())).thenReturn(Optional.of(order));
        lenient().when(attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                anyString(), any(EntityType.class), anyString())).thenReturn(0L);
        // ossClient is @Autowired(required=false) — inject via reflection for tests
        try {
            Field f = AttachmentServiceImpl.class.getDeclaredField("ossClient");
            f.setAccessible(true);
            f.set(service, ossClient);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RegisterAttachmentRequest sampleRequest() {
        RegisterAttachmentRequest req = new RegisterAttachmentRequest();
        req.setEntityType(EntityType.PURCHASE_ORDER);
        req.setEntityId("PO-20260514-001");
        req.setFileName("delivery-proof.jpg");
        req.setFileUrl("https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/attachments/2026/05/15/abc_delivery-proof.jpg");
        req.setFileSize(245680L);
        req.setFileType("image/jpeg");
        req.setFileHash("sha256-abc123");
        req.setBusinessTag("DELIVERY_PROOF");
        return req;
    }

    @Nested
    @DisplayName("register")
    class RegisterTests {

        @Test
        @DisplayName("✅ 成功注册附件 — 无 hash 冲突")
        void register_success_noDedup() {
            when(attachmentRepository.findByFactoryIdAndEntityTypeAndEntityIdAndFileHash(
                    FACTORY_ID, EntityType.PURCHASE_ORDER, "PO-20260514-001", "sha256-abc123"))
                    .thenReturn(Optional.empty());
            when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> {
                Attachment a = inv.getArgument(0);
                a.setId(ATTACHMENT_ID);
                return a;
            });

            Attachment saved = service.register(FACTORY_ID, sampleRequest(), UPLOADER_ID);

            assertEquals(ATTACHMENT_ID, saved.getId());
            assertEquals(FACTORY_ID, saved.getFactoryId());
            assertEquals(EntityType.PURCHASE_ORDER, saved.getEntityType());
            assertEquals(UPLOADER_ID, saved.getUploadedBy());
            assertEquals(Attachment.FileCategory.PHOTO, saved.getFileCategory(),
                    "image/jpeg 应自动推断为 PHOTO");
            assertEquals("OSS", saved.getFileStorage(), "默认 OSS storage");
            assertEquals("WEB", saved.getUploadSource(), "默认 WEB source");
            verify(attachmentRepository).save(any(Attachment.class));
        }

        @Test
        @DisplayName("✅ Dedup 命中 — 同 factory 同 hash 返回已有 Attachment, 不二次 save")
        void register_dedupReturnsExisting() {
            Attachment existing = new Attachment();
            existing.setId("existing-uuid");
            existing.setFactoryId(FACTORY_ID);
            existing.setFileHash("sha256-abc123");
            when(attachmentRepository.findByFactoryIdAndEntityTypeAndEntityIdAndFileHash(
                    FACTORY_ID, EntityType.PURCHASE_ORDER, "PO-20260514-001", "sha256-abc123"))
                    .thenReturn(Optional.of(existing));

            Attachment result = service.register(FACTORY_ID, sampleRequest(), UPLOADER_ID);

            assertEquals("existing-uuid", result.getId());
            verify(attachmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("❌ 未登录 (userId null) 抛 401")
        void register_unauth_throws401() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.register(FACTORY_ID, sampleRequest(), null));
            assertEquals(401, ex.getCode());
        }

        @Test
        @DisplayName("✅ 无 hash 字段时跳过 dedup, 直接保存")
        void register_noHashSkipsDedup() {
            RegisterAttachmentRequest req = sampleRequest();
            req.setFileHash(null);
            when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));

            service.register(FACTORY_ID, req, UPLOADER_ID);

            verify(attachmentRepository, never()).findByFactoryIdAndFileHash(any(), any());
            verify(attachmentRepository).save(any(Attachment.class));
        }

        @Test
        void purchaseOrderAttachmentRejectsUnknownEntityId() {
            when(purchaseOrderRepository.findById("PO-20260514-001")).thenReturn(Optional.empty());

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.register(FACTORY_ID, sampleRequest(), UPLOADER_ID));

            assertEquals(404, error.getCode());
            verify(attachmentRepository, never()).save(any());
        }

        @Test
        void purchaseOrderAttachmentRejectsCrossFactoryBinding() {
            PurchaseOrder otherFactory = new PurchaseOrder();
            otherFactory.setId("PO-20260514-001"); otherFactory.setFactoryId(OTHER_FACTORY);
            when(purchaseOrderRepository.findById("PO-20260514-001")).thenReturn(Optional.of(otherFactory));

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.register(FACTORY_ID, sampleRequest(), UPLOADER_ID));

            assertEquals(403, error.getCode());
        }

        @Test
        void purchaseOrderAttachmentEnforcesMaximumCount() {
            when(attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                    FACTORY_ID, EntityType.PURCHASE_ORDER, "PO-20260514-001")).thenReturn(10L);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.register(FACTORY_ID, sampleRequest(), UPLOADER_ID));

            assertEquals(400, error.getCode());
            verify(attachmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("queryByEntity / countByEntities / getById")
    class QueryTests {

        @Test
        @DisplayName("✅ queryByEntity 委托给 repository")
        void queryByEntity_delegates() {
            Attachment a1 = new Attachment();
            a1.setId("a1");
            List<Attachment> mockList = List.of(a1);
            when(attachmentRepository.findByFactoryIdAndEntityTypeAndEntityIdOrderByUploadedAtDesc(
                    FACTORY_ID, EntityType.PURCHASE_ORDER, "PO-001"))
                    .thenReturn(mockList);

            List<Attachment> result = service.queryByEntity(FACTORY_ID, EntityType.PURCHASE_ORDER, "PO-001");
            assertEquals(1, result.size());
            assertEquals("a1", result.get(0).getId());
        }

        @Test
        @DisplayName("✅ countByEntities 把 Object[][] 折成 Map<String, Long>")
        void countByEntities_buildsMap() {
            List<Object[]> rows = List.of(
                    new Object[]{"PO-001", 3L},
                    new Object[]{"PO-002", 7L}
            );
            when(attachmentRepository.countByEntities(FACTORY_ID, EntityType.PURCHASE_ORDER, List.of("PO-001", "PO-002")))
                    .thenReturn(rows);

            Map<String, Long> counts = service.countByEntities(
                    FACTORY_ID, EntityType.PURCHASE_ORDER, List.of("PO-001", "PO-002"));

            assertEquals(2, counts.size());
            assertEquals(3L, counts.get("PO-001"));
            assertEquals(7L, counts.get("PO-002"));
        }

        @Test
        @DisplayName("✅ countByEntities 空列表直接返空 Map (不打 DB)")
        void countByEntities_emptyShortCircuits() {
            Map<String, Long> result = service.countByEntities(FACTORY_ID, EntityType.PURCHASE_ORDER, List.of());
            assertTrue(result.isEmpty());
            verify(attachmentRepository, never()).countByEntities(any(), any(), any());
        }

        @Test
        @DisplayName("❌ getById 不存在抛 404")
        void getById_notFoundThrows404() {
            when(attachmentRepository.findByFactoryIdAndId(FACTORY_ID, ATTACHMENT_ID))
                    .thenReturn(Optional.empty());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.getById(FACTORY_ID, ATTACHMENT_ID));
            assertEquals(404, ex.getCode());
        }

        @Test
        @DisplayName("✅ getById 多租户隔离 — repository 用 factoryId 过滤")
        void getById_enforcesFactoryIsolation() {
            Attachment a = new Attachment();
            a.setId(ATTACHMENT_ID);
            a.setFactoryId(FACTORY_ID);
            when(attachmentRepository.findByFactoryIdAndId(FACTORY_ID, ATTACHMENT_ID))
                    .thenReturn(Optional.of(a));
            when(attachmentRepository.findByFactoryIdAndId(OTHER_FACTORY, ATTACHMENT_ID))
                    .thenReturn(Optional.empty());

            assertEquals(ATTACHMENT_ID, service.getById(FACTORY_ID, ATTACHMENT_ID).getId());
            assertThrows(BusinessException.class,
                    () -> service.getById(OTHER_FACTORY, ATTACHMENT_ID));
        }
    }

    @Nested
    @DisplayName("softDelete")
    class SoftDeleteTests {

        @Test
        @DisplayName("✅ 上传者本人软删 OK")
        void softDelete_byOwner_succeeds() {
            Attachment a = new Attachment();
            a.setId(ATTACHMENT_ID);
            a.setFactoryId(FACTORY_ID);
            a.setUploadedBy(UPLOADER_ID);
            when(attachmentRepository.findByFactoryIdAndId(FACTORY_ID, ATTACHMENT_ID))
                    .thenReturn(Optional.of(a));

            assertDoesNotThrow(() -> service.softDelete(FACTORY_ID, ATTACHMENT_ID, UPLOADER_ID));

            assertNotNull(a.getDeletedAt(), "deletedAt 应被置位");
            verify(attachmentRepository).save(a);
        }

        @Test
        @DisplayName("❌ 非上传者非 admin 软删抛 403")
        void softDelete_byNonOwnerNonAdmin_throws403() {
            Attachment a = new Attachment();
            a.setId(ATTACHMENT_ID);
            a.setFactoryId(FACTORY_ID);
            a.setUploadedBy(UPLOADER_ID);
            when(attachmentRepository.findByFactoryIdAndId(FACTORY_ID, ATTACHMENT_ID))
                    .thenReturn(Optional.of(a));

            // SecurityUtils.hasRole 返回 false (无 mock = static 调用走真实路径, 测试上下文无 auth → false)
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.softDelete(FACTORY_ID, ATTACHMENT_ID, OTHER_USER_ID));
            assertEquals(403, ex.getCode());
            assertNull(a.getDeletedAt(), "未授权 — deletedAt 不能被置位");
            verify(attachmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("❌ 未登录 (userId null) 软删抛 401")
        void softDelete_unauth_throws401() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.softDelete(FACTORY_ID, ATTACHMENT_ID, null));
            assertEquals(401, ex.getCode());
        }
    }

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("✅ 上传者本人改 description/businessTag/fileCategory")
        void update_byOwner_succeeds() {
            Attachment a = new Attachment();
            a.setId(ATTACHMENT_ID);
            a.setFactoryId(FACTORY_ID);
            a.setUploadedBy(UPLOADER_ID);
            a.setDescription("old desc");
            a.setBusinessTag("OLD_TAG");
            a.setFileCategory(Attachment.FileCategory.OTHER);
            when(attachmentRepository.findByFactoryIdAndId(FACTORY_ID, ATTACHMENT_ID))
                    .thenReturn(Optional.of(a));
            when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateAttachmentRequest req = new UpdateAttachmentRequest();
            req.setDescription("new desc");
            req.setBusinessTag("NEW_TAG");
            req.setFileCategory(Attachment.FileCategory.PHOTO);

            Attachment result = service.update(FACTORY_ID, ATTACHMENT_ID, req, UPLOADER_ID);

            assertEquals("new desc", result.getDescription());
            assertEquals("NEW_TAG", result.getBusinessTag());
            assertEquals(Attachment.FileCategory.PHOTO, result.getFileCategory());
        }

        @Test
        @DisplayName("❌ 非上传者非 admin update 抛 403")
        void update_byNonOwnerNonAdmin_throws403() {
            Attachment a = new Attachment();
            a.setId(ATTACHMENT_ID);
            a.setFactoryId(FACTORY_ID);
            a.setUploadedBy(UPLOADER_ID);
            when(attachmentRepository.findByFactoryIdAndId(FACTORY_ID, ATTACHMENT_ID))
                    .thenReturn(Optional.of(a));

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    service.update(FACTORY_ID, ATTACHMENT_ID, new UpdateAttachmentRequest(), OTHER_USER_ID));
            assertEquals(403, ex.getCode());
        }
    }

    @Nested
    @DisplayName("stripQuery (helper for fileUrl extraction)")
    class StripQueryTests {

        @Test
        @DisplayName("✅ 移除 OSS pre-signed URL 的查询串")
        void stripQuery_removesQueryString() {
            String presigned = "https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/attachments/2026/05/15/abc.jpg?Expires=1234&OSSAccessKeyId=k&Signature=s";
            String stripped = AttachmentServiceImpl.stripQuery(presigned);
            assertEquals("https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/attachments/2026/05/15/abc.jpg", stripped);
        }

        @Test
        @DisplayName("✅ 无查询串时原样返回")
        void stripQuery_noQuery_unchanged() {
            String plain = "https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/attachments/2026/05/15/abc.jpg";
            assertEquals(plain, AttachmentServiceImpl.stripQuery(plain));
        }

        @Test
        @DisplayName("✅ null 安全 — 返回 null")
        void stripQuery_null_safe() {
            assertNull(AttachmentServiceImpl.stripQuery(null));
        }
    }
}
