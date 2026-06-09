package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.service.OssService;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.util.ErrorSanitizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件上传 Controller — 通用文件/图片 OSS 上传入口
 *
 * <p>P0-NEW-1 配套: 出库签收照片上传端点。
 * 复用 {@link OssService#uploadImage} 实现,上传到 media bucket。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/upload")
@RequiredArgsConstructor
@Tag(name = "文件上传", description = "通用 OSS 文件上传接口")
public class FileUploadController {

    private final OssService ossService;

    private static final long MAX_SIGNATURE_PHOTO_SIZE = 5L * 1024 * 1024; // 5MB
    private static final long MAX_YIELD_EVIDENCE_VIDEO_SIZE = 100L * 1024 * 1024; // 100MB
    private static final long MAX_CONTRACT_SIZE = 20L * 1024 * 1024; // 20MB
    private static final long MAX_RECEIPT_SIZE = 10L * 1024 * 1024; // 10MB
    private static final Set<String> ALLOWED_PHOTO_TYPES = Set.of("image/jpeg", "image/jpg", "image/png");
    private static final Set<String> ALLOWED_YIELD_EVIDENCE_VIDEO_TYPES = Set.of(
            "video/mp4", "video/quicktime", "video/webm"
    );
    private static final Set<String> ALLOWED_RECEIPT_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/jpg", "image/png"
    );
    private static final Set<String> ALLOWED_CONTRACT_TYPES = Set.of(
            "application/pdf",
            "image/jpeg", "image/jpg", "image/png",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private String normalizeContentType(String contentType) {
        return contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    }

    /**
     * 上传签收照片 (出库签收配套)
     *
     * <p>文件限制: ≤5MB,仅支持 image/jpeg, image/png。
     * 上传成功后返回公网 URL,前端拿到 URL 后调用
     * {@code POST /sales/deliveries/{id}/signature} 提交签收。
     */
    @RequirePermission({"production:read_write"})
    @PostMapping(value = "/signature-photo", consumes = "multipart/form-data")
    @Operation(summary = "上传签收照片", description = "出库签收配套;≤5MB,仅 JPEG/PNG")
    public ApiResponse<Map<String, String>> uploadSignaturePhoto(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "签收照片(JPEG/PNG,≤5MB)", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("上传签收照片: factoryId={}, filename={}, size={}, contentType={}",
                factoryId, file.getOriginalFilename(), file.getSize(), file.getContentType());

        if (file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }
        if (file.getSize() > MAX_SIGNATURE_PHOTO_SIZE) {
            throw new BusinessException(400, "签收照片不能超过 5MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_PHOTO_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessException(400, "仅支持 JPEG/PNG 格式,当前: " + contentType).withHint("请上传支持的文件格式");
        }

        try {
            // category = "signatures" → OSS 路径: {factoryId}/images/signatures/yyyy/MM/dd/{uuid}_{filename}
            String url = ossService.uploadImage(file, "signatures", factoryId);
            log.info("签收照片上传成功: factoryId={}, url={}", factoryId, url);
            return ApiResponse.success("上传成功", Map.of("url", url));
        } catch (IllegalArgumentException e) {
            log.warn("签收照片上传失败(参数错误): {}", e.getMessage());
            throw new BusinessException(400, ErrorSanitizer.sanitize(e), e);
        } catch (Exception e) {
            log.error("签收照片上传失败: factoryId={}", factoryId, e);
            throw new BusinessException(500, "上传失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    /**
     * 逐道报工图片/视频证据上传 (产品+电子秤+盒数照片或现场视频) — 后端中转到 OSS, 返回 URL 供 submitReport 的 evidenceImages 引用
     *
     * <p>文件限制: 图片 ≤5MB,视频 ≤50MB; 支持 image/jpeg, image/png, video/mp4, video/quicktime, video/webm。
     * 上传成功后返回公网 URL,前端拿到 URL 后写入逐道报工的 evidenceImages 字段。
     */
    @RequirePermission({"production:read_write"})
    @PostMapping(value = "/yield-evidence", consumes = "multipart/form-data")
    @Operation(summary = "上传逐道报工图片/视频证据", description = "图片≤5MB,视频≤50MB; 支持 JPEG/PNG/MP4/MOV/WEBM")
    public ApiResponse<Map<String, String>> uploadYieldEvidence(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "报工证据文件(图片 JPEG/PNG ≤5MB,视频 MP4/MOV/WEBM ≤50MB)", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("上传逐道报工图片/视频证据: factoryId={}, filename={}, size={}, contentType={}",
                factoryId, file.getOriginalFilename(), file.getSize(), file.getContentType());

        if (file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }
        String contentType = file.getContentType();
        String normalizedContentType = normalizeContentType(contentType);
        boolean isImage = ALLOWED_PHOTO_TYPES.contains(normalizedContentType);
        boolean isVideo = ALLOWED_YIELD_EVIDENCE_VIDEO_TYPES.contains(normalizedContentType);
        if (!isImage && !isVideo) {
            throw new BusinessException(400, "仅支持 JPEG/PNG/MP4/MOV/WEBM 格式, 当前: " + contentType)
                    .withHint("请上传支持的报工证据文件");
        }
        if (isImage && file.getSize() > MAX_SIGNATURE_PHOTO_SIZE) {
            throw new BusinessException(400, "报工证据照片不能超过 5MB");
        }
        if (isVideo && file.getSize() > MAX_YIELD_EVIDENCE_VIDEO_SIZE) {
            throw new BusinessException(400, "报工证据视频不能超过 100MB");
        }

        try {
            // Images use images/yield-evidence; videos use the existing videos path from OssService.uploadVideo.
            // evidenceImages legacy field now stores both image and video evidence URLs.
            String url = isVideo
                    ? ossService.uploadVideo(file, factoryId)
                    : ossService.uploadImage(file, "yield-evidence", factoryId);
            log.info("逐道报工图片/视频证据上传成功: factoryId={}, url={}", factoryId, url);
            return ApiResponse.success("上传成功", Map.of("url", url));
        } catch (IllegalArgumentException e) {
            log.warn("逐道报工图片/视频证据上传失败(参数错误): {}", e.getMessage());
            throw new BusinessException(400, ErrorSanitizer.sanitize(e), e);
        } catch (Exception e) {
            log.error("逐道报工图片/视频证据上传失败: factoryId={}", factoryId, e);
            throw new BusinessException(500, "上传失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    /**
     * 上传收款凭证附件 (P0-3d, v1 §2.3.2 客户要求: 登记收款时可附上回款凭证截图/PDF).
     * 接受 PDF/图片, ≤10MB. 上传成功后返回 URL, 前端绑定到 paymentForm.receiptUrl 后
     * 调用 finance/payments/record 接口一并提交.
     */
    @RequirePermission({"production:read_write"})
    @PostMapping(value = "/receipt", consumes = "multipart/form-data")
    @Operation(summary = "上传收款凭证", description = "PDF/JPG/PNG, ≤10MB (P0-3d)")
    public ApiResponse<Map<String, String>> uploadReceipt(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "收款凭证 (PDF/JPG/PNG, ≤10MB)", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("上传收款凭证: factoryId={}, filename={}, size={}, contentType={}",
                factoryId, file.getOriginalFilename(), file.getSize(), file.getContentType());

        if (file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }
        if (file.getSize() > MAX_RECEIPT_SIZE) {
            throw new BusinessException(400, "收款凭证不能超过 10MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_RECEIPT_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessException(400, "仅支持 PDF/JPG/PNG 格式,当前: " + contentType).withHint("请上传支持的文件格式");
        }

        try {
            // category = "receipts" → OSS 路径: {factoryId}/files/receipts/yyyy/MM/dd/{uuid}_{filename}
            String url = ossService.uploadFile(file, "receipts", factoryId);
            log.info("收款凭证上传成功: factoryId={}, url={}", factoryId, url);
            return ApiResponse.success("上传成功", Map.of(
                    "url", url,
                    "fileName", file.getOriginalFilename() == null ? "" : file.getOriginalFilename()
            ));
        } catch (IllegalArgumentException e) {
            log.warn("收款凭证上传失败(参数错误): {}", e.getMessage());
            throw new BusinessException(400, ErrorSanitizer.sanitize(e), e);
        } catch (Exception e) {
            log.error("收款凭证上传失败: factoryId={}", factoryId, e);
            throw new BusinessException(500, "上传失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    /**
     * 上传销售合同附件 (P1-7, v1 §2.4.3 客户会议 2257s).
     * 接受 PDF/图片/Word, ≤20MB. 上传成功后返回 URL, 前端绑定到
     * salesOrderForm.contractFileUrl + contractFileName 后 createOrder.
     */
    @RequirePermission({"production:read_write"})
    @PostMapping(value = "/contract", consumes = "multipart/form-data")
    @Operation(summary = "上传销售合同附件", description = "PDF/图片/Word, ≤20MB (P1-7)")
    public ApiResponse<Map<String, String>> uploadContract(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "合同文件 (PDF/JPG/PNG/DOC/DOCX, ≤20MB)", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("上传销售合同: factoryId={}, filename={}, size={}, contentType={}",
                factoryId, file.getOriginalFilename(), file.getSize(), file.getContentType());

        if (file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }
        if (file.getSize() > MAX_CONTRACT_SIZE) {
            throw new BusinessException(400, "合同文件不能超过 20MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTRACT_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessException(400, "仅支持 PDF/JPG/PNG/DOC/DOCX,当前: " + contentType).withHint("请上传支持的文件格式");
        }

        try {
            // category = "contracts" → OSS 路径: {factoryId}/files/contracts/yyyy/MM/dd/{uuid}_{filename}
            String url = ossService.uploadFile(file, "contracts", factoryId);
            log.info("销售合同上传成功: factoryId={}, url={}", factoryId, url);
            return ApiResponse.success("上传成功", Map.of(
                    "url", url,
                    "fileName", file.getOriginalFilename() == null ? "" : file.getOriginalFilename()
            ));
        } catch (IllegalArgumentException e) {
            log.warn("销售合同上传失败(参数错误): {}", e.getMessage());
            throw new BusinessException(400, ErrorSanitizer.sanitize(e), e);
        } catch (Exception e) {
            log.error("销售合同上传失败: factoryId={}", factoryId, e);
            throw new BusinessException(500, "上传失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }
}
