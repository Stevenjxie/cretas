package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.attachment.LinkChipCountsDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.entity.Attachment.FileCategory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.security.AttachmentPermissionResolver;
import com.cretas.aims.service.attachment.AttachmentService;
import com.cretas.aims.service.attachment.dto.RegisterAttachmentRequest;
import com.cretas.aims.service.attachment.dto.UpdateAttachmentRequest;
import com.cretas.aims.service.attachment.dto.UploadUrlResponse;
import com.cretas.aims.service.attachment.impl.AttachmentServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Attachment REST API — C-ATT-1.
 *
 * <p>8 endpoint 完整实现 (见 SCHEMA_DESIGN §2.3 API 契约):
 * <ol>
 *   <li>GET /attachments?entityType=X&entityId=Y — 列表</li>
 *   <li>GET /attachments/{id} — 详情</li>
 *   <li>GET /attachments/{id}/download — 302 重定向到签名 GET URL</li>
 *   <li>POST /attachments/upload-url — 取签名 PUT URL (前端直传)</li>
 *   <li>POST /attachments — 注册元数据 (前端 OSS 上传完成后)</li>
 *   <li>PUT /attachments/{id} — 更新 description/businessTag/fileCategory</li>
 *   <li>DELETE /attachments/{id} — 软删</li>
 *   <li>POST /attachments/batch-by-entity — 批量计数 (列表页徽章)</li>
 * </ol>
 *
 * <p><b>RBAC (Sprint1-Fix-K2, 2026-05-15)</b>: 每个 endpoint 标 {@code @RequirePermission} 作
 * coarse 网关 (无 token / 无任何业务模块权限的请求被 {@link com.cretas.aims.config.PermissionInterceptor}
 * 直接 403). Fine-grained 检查由 {@link AttachmentPermissionResolver} 根据 {@link EntityType}
 * 派生模块 (e.g. {@code PAYMENT_VOUCHER → finance:read}, {@code QUALITY_CHECK → quality:read}).
 * 仓库管理员不再能下载任意工厂的财务 / 质检附件 — 详情见 PR #658 follow-up.
 *
 * @author Cretas Team — Track C
 * @since 2026-05-15 (C-ATT-1 Day 3)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/attachments")
@CrossOrigin(origins = {"https://www.cretaceousfuture.com", "http://139.196.165.140:8086", "http://localhost:5173"})
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final AttachmentPermissionResolver permissionResolver;
    private final AttachmentRepository attachmentRepository;

    /** Sprint 6 W3-A — max ids per batch 3-chip request, bounds a single SQL IN-clause. */
    private static final int MAX_BATCH_IDS_3CHIP = 500;

    // ==================== 1. List by entity ====================

    @GetMapping
    @RequirePermission(value = {
            "procurement:read", "procurement:read_write",
            "sales:read", "sales:read_write",
            "finance:read", "finance:read_write",
            "quality:read", "quality:read_write",
            "production:read", "production:read_write",
            "warehouse:read", "warehouse:read_write",
            "hr:read", "hr:read_write",
            "work_report:read", "work_report:read_write",
            "restaurant:read", "restaurant:read_write",
            "rd:read", "rd:read_write",
            "system:read", "system:read_write"
    })
    public ResponseEntity<ApiResponse<List<Attachment>>> listByEntity(
            @PathVariable String factoryId,
            @RequestParam EntityType entityType,
            @RequestParam String entityId,
            HttpServletRequest request) {
        permissionResolver.requireRead(permissionResolver.resolveCurrentUser(request), entityType);
        List<Attachment> list = attachmentService.queryByEntity(factoryId, entityType, entityId);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    // ==================== 2. Get by id ====================

    @GetMapping("/{id}")
    @RequirePermission(value = {
            "procurement:read", "procurement:read_write",
            "sales:read", "sales:read_write",
            "finance:read", "finance:read_write",
            "quality:read", "quality:read_write",
            "production:read", "production:read_write",
            "warehouse:read", "warehouse:read_write",
            "hr:read", "hr:read_write",
            "work_report:read", "work_report:read_write",
            "restaurant:read", "restaurant:read_write",
            "rd:read", "rd:read_write",
            "system:read", "system:read_write"
    })
    public ResponseEntity<ApiResponse<Attachment>> getById(
            @PathVariable String factoryId,
            @PathVariable String id,
            HttpServletRequest request) {
        Attachment attachment = attachmentService.getById(factoryId, id);
        permissionResolver.requireRead(permissionResolver.resolveCurrentUser(request), attachment.getEntityType());
        return ResponseEntity.ok(ApiResponse.success(attachment));
    }

    // ==================== 3. Download (302 redirect to signed URL) ====================

    @GetMapping("/{id}/download")
    @RequirePermission(value = {
            "procurement:read", "procurement:read_write",
            "sales:read", "sales:read_write",
            "finance:read", "finance:read_write",
            "quality:read", "quality:read_write",
            "production:read", "production:read_write",
            "warehouse:read", "warehouse:read_write",
            "hr:read", "hr:read_write",
            "work_report:read", "work_report:read_write",
            "restaurant:read", "restaurant:read_write",
            "rd:read", "rd:read_write",
            "system:read", "system:read_write"
    })
    public ResponseEntity<Void> download(
            @PathVariable String factoryId,
            @PathVariable String id,
            HttpServletRequest request) {
        Attachment attachment = attachmentService.getById(factoryId, id);
        permissionResolver.requireRead(permissionResolver.resolveCurrentUser(request), attachment.getEntityType());
        String signedUrl = attachmentService.generateDownloadUrl(factoryId, id);
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(signedUrl));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    // ==================== 4. Upload URL (pre-signed PUT) ====================

    @PostMapping("/upload-url")
    @RequirePermission(value = {
            "procurement:read_write",
            "sales:read_write",
            "finance:read_write",
            "quality:read_write",
            "production:read_write",
            "warehouse:read_write",
            "hr:read_write",
            "work_report:read_write",
            "restaurant:read_write",
            "rd:read_write",
            "system:read_write"
    })
    public ResponseEntity<ApiResponse<UploadUrlResponse>> generateUploadUrl(
            @PathVariable String factoryId,
            @Valid @RequestBody UploadUrlRequest req) {
        // Content-type whitelist + size hint check (BEFORE signed URL issue —
        // refuses oversize / executable uploads at the OSS boundary).
        permissionResolver.validateUploadRequest(req.getFileType(), req.getFileSize());
        String putUrl = attachmentService.generateUploadUrl(factoryId, req.getFileName(), req.getFileType());
        String fileUrl = AttachmentServiceImpl.stripQuery(putUrl);
        return ResponseEntity.ok(ApiResponse.success(new UploadUrlResponse(putUrl, fileUrl)));
    }

    // ==================== 5. Register metadata (post-upload) ====================

    @PostMapping
    @RequirePermission(value = {
            "procurement:read_write",
            "sales:read_write",
            "finance:read_write",
            "quality:read_write",
            "production:read_write",
            "warehouse:read_write",
            "hr:read_write",
            "work_report:read_write",
            "restaurant:read_write",
            "rd:read_write",
            "system:read_write"
    })
    public ResponseEntity<ApiResponse<Attachment>> register(
            @PathVariable String factoryId,
            @Valid @RequestBody RegisterAttachmentRequest req,
            HttpServletRequest request) {
        User currentUser = permissionResolver.resolveCurrentUser(request);
        permissionResolver.requireWrite(currentUser, req.getEntityType());
        if (req.getFileSize() != null && req.getFileSize() > AttachmentPermissionResolver.MAX_UPLOAD_SIZE_BYTES) {
            throw new BusinessException(400, "文件超出 10MB 上限");
        }
        if (req.getFileType() != null
                && !AttachmentPermissionResolver.ALLOWED_CONTENT_TYPES.contains(req.getFileType().toLowerCase())) {
            throw new BusinessException(400,
                    "不支持的文件类型: " + req.getFileType() + " — 仅允许 pdf / image / xlsx / docx / mp4");
        }
        Long userId = currentUser.getId();
        Attachment saved = attachmentService.register(factoryId, req, userId);
        return ResponseEntity.ok(ApiResponse.success("上传成功", saved));
    }

    // ==================== 6. Update (description/businessTag/fileCategory) ====================

    @PutMapping("/{id}")
    @RequirePermission(value = {
            "procurement:read_write",
            "sales:read_write",
            "finance:read_write",
            "quality:read_write",
            "production:read_write",
            "warehouse:read_write",
            "hr:read_write",
            "work_report:read_write",
            "restaurant:read_write",
            "rd:read_write",
            "system:read_write"
    })
    public ResponseEntity<ApiResponse<Attachment>> update(
            @PathVariable String factoryId,
            @PathVariable String id,
            @Valid @RequestBody UpdateAttachmentRequest req,
            HttpServletRequest request) {
        Attachment existing = attachmentService.getById(factoryId, id);
        User currentUser = permissionResolver.resolveCurrentUser(request);
        permissionResolver.requireWrite(currentUser, existing.getEntityType());
        Long userId = currentUser.getId();
        return ResponseEntity.ok(ApiResponse.success(attachmentService.update(factoryId, id, req, userId)));
    }

    // ==================== 7. Soft delete ====================
    //
    // RBAC: 服务层已 enforce owner-or-admin (AttachmentServiceImpl.softDelete). 此处只加
    // coarse @RequirePermission 网关 (拒绝 anonymous / 完全无权限的请求) — 不调
    // resolver.requireWrite, 因为非 owner 的 module-write 用户(如同部门同事)不应能删除
    // 别人传的附件; "owner-or-admin" 才是正确语义.

    @DeleteMapping("/{id}")
    @RequirePermission(value = {
            "procurement:read_write",
            "sales:read_write",
            "finance:read_write",
            "quality:read_write",
            "production:read_write",
            "warehouse:read_write",
            "hr:read_write",
            "work_report:read_write",
            "restaurant:read_write",
            "rd:read_write",
            "system:read_write"
    })
    public ResponseEntity<ApiResponse<Void>> softDelete(
            @PathVariable String factoryId,
            @PathVariable String id,
            HttpServletRequest request) {
        Long userId = currentUserId(request);
        attachmentService.softDelete(factoryId, id, userId);
        return ResponseEntity.ok(ApiResponse.successMessage("删除成功"));
    }

    // ==================== 8. Batch count by entity ====================

    @PostMapping("/batch-by-entity")
    @RequirePermission(value = {
            "procurement:read", "procurement:read_write",
            "sales:read", "sales:read_write",
            "finance:read", "finance:read_write",
            "quality:read", "quality:read_write",
            "production:read", "production:read_write",
            "warehouse:read", "warehouse:read_write",
            "hr:read", "hr:read_write",
            "work_report:read", "work_report:read_write",
            "restaurant:read", "restaurant:read_write",
            "rd:read", "rd:read_write",
            "system:read", "system:read_write"
    })
    public ResponseEntity<ApiResponse<Map<String, Long>>> batchCount(
            @PathVariable String factoryId,
            @Valid @RequestBody BatchCountRequest req,
            HttpServletRequest request) {
        permissionResolver.requireRead(permissionResolver.resolveCurrentUser(request), req.getEntityType());
        Map<String, Long> counts = attachmentService.countByEntities(factoryId, req.getEntityType(), req.getEntityIds());
        return ResponseEntity.ok(ApiResponse.success(counts));
    }

    // ==================== 9. Batch 3-chip count (Sprint 6 W3-A) ====================

    /**
     * Sprint 6 W3-A — batch-fetch 3-chip (文件/图片/合同) counts for a list of
     * entity ids. Reusable across 4 list views (sales / procurement /
     * inventory / voucher) by passing the correct {@link EntityType}.
     *
     * <p>Returns one {@link LinkChipCountsDTO} per id requested; absent counts
     * default to zero. Caller paginates if {@code entityIds.size() > 500}.
     *
     * <p>Source: Round 12 §B.6 X2 + Round 13 §3 — HJ baseline 销售单/采购单
     * list 行内 link counter 是 3 类 (file/image/contract). Replaces the
     * unified {@code 链:N} chip from Sprint 5 PR #58 with the 3-chip trio.
     *
     * <p>POST chosen over GET because the {@code entityIds} list can exceed
     * URL-length safe limits (200+ rows × 36-char UUIDs).
     */
    @PostMapping("/batch-3chip-counts")
    @RequirePermission(value = {
            "procurement:read", "procurement:read_write",
            "sales:read", "sales:read_write",
            "finance:read", "finance:read_write",
            "quality:read", "quality:read_write",
            "production:read", "production:read_write",
            "warehouse:read", "warehouse:read_write",
            "hr:read", "hr:read_write",
            "work_report:read", "work_report:read_write",
            "restaurant:read", "restaurant:read_write",
            "rd:read", "rd:read_write",
            "system:read", "system:read_write"
    })
    public ResponseEntity<ApiResponse<List<LinkChipCountsDTO>>> batch3ChipCounts(
            @PathVariable String factoryId,
            @Valid @RequestBody Batch3ChipCountRequest req,
            HttpServletRequest request) {
        permissionResolver.requireRead(permissionResolver.resolveCurrentUser(request), req.getEntityType());
        List<String> ids = req.getEntityIds() == null
                ? java.util.Collections.emptyList()
                : req.getEntityIds();
        if (ids.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(java.util.Collections.emptyList()));
        }
        if (ids.size() > MAX_BATCH_IDS_3CHIP) {
            throw new BusinessException(400,
                    "entityIds size > " + MAX_BATCH_IDS_3CHIP + ", 请分页查询");
        }

        // One round-trip GROUP BY entityId+fileCategory — no N+1.
        java.util.Map<String, LinkChipCountsDTO> bucket = new java.util.HashMap<>();
        for (String id : ids) {
            bucket.put(id, LinkChipCountsDTO.builder()
                    .entityId(id)
                    .fileCount(0L).imageCount(0L).contractCount(0L)
                    .build());
        }
        for (Object[] row : attachmentRepository.countByEntitiesGroupedByCategory(
                factoryId, req.getEntityType(), ids)) {
            String entityId = (String) row[0];
            FileCategory category = (FileCategory) row[1];
            long count = ((Number) row[2]).longValue();
            LinkChipCountsDTO dto = bucket.get(entityId);
            if (dto == null) continue;  // defensive (shouldn't happen given pre-seed)
            // 3-chip mapping per LinkChipCountsDTO javadoc:
            // DOCUMENT/OTHER → file, PHOTO/VIDEO → image, CONTRACT → contract;
            // VOUCHER/SIGNATURE skipped (shown in dedicated columns).
            switch (category) {
                case DOCUMENT:
                case OTHER:
                    dto.setFileCount(dto.getFileCount() + count);
                    break;
                case PHOTO:
                case VIDEO:
                    dto.setImageCount(dto.getImageCount() + count);
                    break;
                case CONTRACT:
                    dto.setContractCount(dto.getContractCount() + count);
                    break;
                case VOUCHER:
                case SIGNATURE:
                default:
                    // intentionally skipped — not part of 3-chip total
                    break;
            }
        }

        // Preserve caller's input order.
        List<LinkChipCountsDTO> result = new java.util.ArrayList<>(ids.size());
        for (String id : ids) {
            result.add(bucket.get(id));
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ==================== Request DTOs (inner — only used by this Controller) ====================

    @Data
    public static class UploadUrlRequest {
        @NotBlank
        private String fileName;
        @NotBlank
        private String fileType;
        /** Optional declared size hint — when provided, must be ≤ 10 MB. */
        @Min(1)
        private Long fileSize;
    }

    @Data
    public static class BatchCountRequest {
        @NotNull
        private EntityType entityType;
        @NotNull
        private List<String> entityIds;
    }

    /**
     * Sprint 6 W3-A — request body for {@link #batch3ChipCounts}. Same shape
     * as {@link BatchCountRequest} but kept separate so the two endpoints can
     * evolve independently (e.g. batch-3chip may grow a category filter later).
     */
    @Data
    public static class Batch3ChipCountRequest {
        @NotNull
        private EntityType entityType;
        @NotNull
        private List<String> entityIds;
    }

    private Long currentUserId(HttpServletRequest request) {
        User currentUser = permissionResolver.resolveCurrentUser(request);
        return currentUser == null ? null : currentUser.getId();
    }
}
