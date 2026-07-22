package com.cretas.aims.service.attachment.impl;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.cretas.aims.config.OssConfig;
import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.entity.Attachment.FileCategory;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderSuppliedMaterialRequirementRepository;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.attachment.AttachmentService;
import com.cretas.aims.service.attachment.dto.RegisterAttachmentRequest;
import com.cretas.aims.service.attachment.dto.UpdateAttachmentRequest;
import com.cretas.aims.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AttachmentService 实现 — C-ATT-1 Day 3.
 *
 * <p>OSS pre-signed URL 策略:
 * <ul>
 *   <li>PUT (上传): 5 分钟有效, 前端直传不经 Java 字节流</li>
 *   <li>GET (下载): 1 小时有效, 跟随 entity 权限</li>
 * </ul>
 *
 * <p>OSS bean 在 dev / test 环境 conditional 关闭 ({@code aliyun.oss.enabled=false}),
 * 此时 {@link #ossClient} 为 null, upload/download URL 方法会返回明确错误.
 * Day 5 集成测试时启用真实 OSS.
 *
 * @author Cretas Team — Track C
 * @since 2026-05-15 (C-ATT-1)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentServiceImpl implements AttachmentService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final int UPLOAD_URL_TTL_SECONDS = 300;     // 5 分钟
    private static final int DOWNLOAD_URL_TTL_SECONDS = 3600;  // 1 小时
    private static final int THUMBNAIL_MAX_DIM = 200;          // 200x200 fit

    private final AttachmentRepository attachmentRepository;
    private final ProductionReportRepository productionReportRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final OssService ossService;
    private final OssConfig ossConfig;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PurchaseReceiveRecordRepository purchaseReceiveRecordRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SalesOrderSuppliedMaterialRequirementRepository suppliedMaterialRequirementRepository;

    /** OSS 直接客户端 — pre-signed PUT 用; {@code aliyun.oss.enabled=false} 时为 null. */
    @Autowired(required = false)
    private OSS ossClient;

    // ==================== register ====================

    @Override
    @Transactional
    public Attachment register(String factoryId, RegisterAttachmentRequest req, Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录 — 无法注册附件");
        }
        if (req.getFileHash() != null && !req.getFileHash().isBlank()) {
            Optional<Attachment> replay = attachmentRepository
                    .findByFactoryIdAndEntityTypeAndEntityIdAndFileHash(
                            factoryId, req.getEntityType(), req.getEntityId(), req.getFileHash());
            if (replay.isPresent()) return replay.get();
        }
        validateEntityBinding(factoryId, req);

        // 去重: 同 factory 同 hash 视为同一文件
        if (req.getFileHash() != null && !req.getFileHash().isBlank()) {
            Optional<Attachment> existing = attachmentRepository
                    .findByFactoryIdAndEntityTypeAndEntityIdAndFileHash(
                            factoryId, req.getEntityType(), req.getEntityId(), req.getFileHash());
            if (existing.isPresent()) {
                log.info("Attachment 去重命中: factory={} hash={} existingId={}",
                        factoryId, req.getFileHash(), existing.get().getId());
                return existing.get();
            }
        }

        Attachment a = new Attachment();
        a.setFactoryId(factoryId);
        a.setEntityType(req.getEntityType());
        a.setEntityId(req.getEntityId());
        a.setFileName(req.getFileName());
        a.setFileUrl(req.getFileUrl());
        a.setThumbnailUrl(req.getThumbnailUrl());
        a.setFileSize(req.getFileSize());
        a.setFileType(req.getFileType());
        a.setFileCategory(req.getFileCategory() != null ? req.getFileCategory() : inferCategory(req.getFileType()));
        a.setFileStorage(req.getFileStorage() != null ? req.getFileStorage() : "OSS");
        a.setFileHash(req.getFileHash());
        a.setBusinessTag(req.getBusinessTag());
        a.setDescription(req.getDescription());
        a.setUploadedBy(userId);
        a.setUploadedAt(LocalDateTime.now());
        a.setUploadSource(req.getUploadSource() != null ? req.getUploadSource() : "WEB");

        Attachment saved = attachmentRepository.save(a);
        log.info("Attachment 注册: factory={} entityType={} entityId={} id={} fileName={}",
                factoryId, req.getEntityType(), req.getEntityId(), saved.getId(), req.getFileName());

        if (EntityType.PRODUCTION_REPORT.equals(req.getEntityType())) {
            syncProductionReportPhoto(factoryId, req.getEntityId(), req.getFileUrl());
        }

        // Async 异步生成图片缩略图 — 失败不阻塞 register
        if (saved.getFileCategory() == FileCategory.PHOTO && saved.getThumbnailUrl() == null) {
            try {
                generateThumbnailAsync(saved.getId());
            } catch (Exception e) {
                log.warn("缩略图异步触发失败 (attachment 仍 register OK): {}", e.getMessage());
            }
        }

        return saved;
    }

    private void validateEntityBinding(String factoryId, RegisterAttachmentRequest req) {
        if (EntityType.CUSTOMER_SUPPLIED_RECEIPT == req.getEntityType()) {
            if (suppliedMaterialRequirementRepository == null) {
                throw new BusinessException(503, "客供料任务归属校验服务暂不可用，附件未绑定");
            }
            var requirement = suppliedMaterialRequirementRepository.findById(req.getEntityId())
                    .orElseThrow(() -> new BusinessException(404,
                            "客供料待收货任务不存在，附件不能绑定到临时或伪造ID"));
            if (!factoryId.equals(requirement.getFactoryId())) {
                throw new BusinessException(403, "客供料收货凭证跨工厂绑定被拒绝");
            }
            return;
        }
        if (EntityType.PURCHASE_RECEIPT == req.getEntityType()) {
            if (purchaseReceiveRecordRepository == null) {
                throw new BusinessException(503, "收货单归属校验服务暂不可用，附件未绑定");
            }
            var receipt = purchaseReceiveRecordRepository.findById(req.getEntityId())
                    .orElseThrow(() -> new BusinessException(404, "收货单不存在，附件不能绑定到临时或伪造ID"));
            if (!factoryId.equals(receipt.getFactoryId())) {
                throw new BusinessException(403, "收货凭证跨工厂绑定被拒绝");
            }
            return;
        }
        if (EntityType.PURCHASE_ORDER != req.getEntityType()) return;
        var order = purchaseOrderRepository.findById(req.getEntityId())
                .orElseThrow(() -> new BusinessException(404, "采购订单不存在，附件不能绑定到临时或伪造ID"));
        if (!factoryId.equals(order.getFactoryId())) {
            throw new BusinessException(403, "采购订单附件跨工厂绑定被拒绝");
        }
        long count = attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                factoryId, EntityType.PURCHASE_ORDER, req.getEntityId());
        if (count >= 10) {
            throw new BusinessException(400, "单张采购订单最多允许10个附件");
        }
    }

    // ==================== 缩略图 (异步) ====================

    /**
     * 异步生成 200x200 缩略图 — 仅图片. 失败仅 log, 不影响 register.
     *
     * <p>Spring @Async 默认 SimpleAsyncTaskExecutor (新线程, 不池化). 量大时考虑
     * 加专属线程池, 但 attachment 频率低 (<1QPS), 默认足够. 不在事务中执行.
     */
    @Async
    public void generateThumbnailAsync(String attachmentId) {
        if (ossClient == null) {
            log.debug("OSS 未启用, 跳过缩略图: id={}", attachmentId);
            return;
        }
        try {
            Attachment a = attachmentRepository.findById(attachmentId).orElse(null);
            if (a == null || a.getFileCategory() != FileCategory.PHOTO) return;
            if (a.getThumbnailUrl() != null) return;  // 已有不重做

            // 1. 下载原图 bytes
            byte[] srcBytes;
            try (InputStream in = new URL(a.getFileUrl()).openStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                srcBytes = out.toByteArray();
            }

            // 2. 用 BufferedImage resize
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(srcBytes));
            if (src == null) {
                log.warn("缩略图: ImageIO 无法解析 {} (可能格式不支持)", a.getFileUrl());
                return;
            }
            int w = src.getWidth(), h = src.getHeight();
            double scale = Math.min((double) THUMBNAIL_MAX_DIM / w, (double) THUMBNAIL_MAX_DIM / h);
            if (scale >= 1.0) {
                // 原图已小于 200x200, 直接 thumbnailUrl = fileUrl
                a.setThumbnailUrl(a.getFileUrl());
                attachmentRepository.save(a);
                return;
            }
            int tw = (int) (w * scale), th = (int) (h * scale);
            BufferedImage thumb = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src.getScaledInstance(tw, th, Image.SCALE_SMOOTH), 0, 0, null);
            g.dispose();

            ByteArrayOutputStream thumbOut = new ByteArrayOutputStream();
            ImageIO.write(thumb, "jpg", thumbOut);
            byte[] thumbBytes = thumbOut.toByteArray();

            // 3. 上传到 OSS 单独 key
            String thumbKey = buildThumbnailKey(a);
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(thumbBytes.length);
            meta.setContentType("image/jpeg");
            ossClient.putObject(ossConfig.getMediaBucket(), thumbKey, new ByteArrayInputStream(thumbBytes), meta);

            // 4. 写回 thumbnailUrl
            String thumbUrl = ossConfig.getMediaUrlPrefix() + "/" + thumbKey;
            a.setThumbnailUrl(thumbUrl);
            attachmentRepository.save(a);
            log.info("缩略图生成: id={} {}x{} -> {}", attachmentId, tw, th, thumbUrl);
        } catch (Exception e) {
            log.warn("缩略图生成失败 id={}: {}", attachmentId, e.getMessage());
        }
    }

    private String buildThumbnailKey(Attachment a) {
        // 同 factory 同日期目录, suffix _thumb.jpg
        String dateDir = LocalDate.now().format(DATE_FORMAT);
        String shortId = a.getId().replace("-", "").substring(0, 16);
        return String.format("%s/attachments/%s/thumbs/%s_thumb.jpg", a.getFactoryId(), dateDir, shortId);
    }

    // ==================== query / count ====================

    @Override
    @Transactional(readOnly = true)
    public List<Attachment> queryByEntity(String factoryId, EntityType type, String entityId) {
        return attachmentRepository
                .findByFactoryIdAndEntityTypeAndEntityIdOrderByUploadedAtDesc(factoryId, type, entityId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> countByEntities(String factoryId, EntityType type, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Object[]> rows = attachmentRepository.countByEntities(factoryId, type, ids);
        return rows.stream().collect(Collectors.toMap(
                row -> (String) row[0],
                row -> ((Number) row[1]).longValue()
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public Attachment getById(String factoryId, String id) {
        return attachmentRepository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new BusinessException(404, "附件不存在或无权访问"));
    }

    // ==================== OSS URLs ====================

    @Override
    public String generateUploadUrl(String factoryId, String fileName, String fileType) {
        if (ossClient == null) {
            throw new BusinessException(503, "OSS 服务未启用 — 请联系管理员配置 aliyun.oss.enabled=true");
        }
        String objectKey = buildObjectKey(factoryId, fileName);
        Date expiration = new Date(System.currentTimeMillis() + UPLOAD_URL_TTL_SECONDS * 1000L);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                ossConfig.getMediaBucket(), objectKey, HttpMethod.PUT);
        request.setExpiration(expiration);
        if (fileType != null && !fileType.isBlank()) {
            request.setContentType(fileType);
        }
        String url = ossClient.generatePresignedUrl(request).toString();
        log.debug("Generated upload URL: factory={} key={} ttl={}s", factoryId, objectKey, UPLOAD_URL_TTL_SECONDS);
        return url;
    }

    @Override
    public String generateDownloadUrl(String factoryId, String id) {
        Attachment a = getById(factoryId, id);
        String signed = ossService.generatePresignedUrl(a.getFileUrl(), DOWNLOAD_URL_TTL_SECONDS);
        if (signed == null) {
            throw new BusinessException(503, "下载 URL 生成失败 — OSS 不可用");
        }
        return signed;
    }

    // ==================== delete / update ====================

    @Override
    @Transactional
    public void softDelete(String factoryId, String id, Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录 — 无法删除附件");
        }
        Attachment a = getById(factoryId, id);
        if (!a.getUploadedBy().equals(userId) && !isAdmin()) {
            throw new BusinessException(403, "仅上传者本人或管理员可删除");
        }
        a.softDelete();
        attachmentRepository.save(a);
        log.info("Attachment 软删: factory={} id={} byUser={}", factoryId, id, userId);
    }

    @Override
    @Transactional
    public Attachment update(String factoryId, String id, UpdateAttachmentRequest req, Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录 — 无法更新附件");
        }
        Attachment a = getById(factoryId, id);
        if (!a.getUploadedBy().equals(userId) && !isAdmin()) {
            throw new BusinessException(403, "仅上传者本人或管理员可更新");
        }
        if (req.getDescription() != null) {
            a.setDescription(req.getDescription());
        }
        if (req.getBusinessTag() != null) {
            a.setBusinessTag(req.getBusinessTag());
        }
        if (req.getFileCategory() != null) {
            a.setFileCategory(req.getFileCategory());
        }
        return attachmentRepository.save(a);
    }

    // ==================== helpers ====================

    /**
     * 推断 file_category 从 MIME type — register 时未提供 fileCategory 时调用.
     */
    private FileCategory inferCategory(String mimeType) {
        if (mimeType == null) return FileCategory.OTHER;
        String lower = mimeType.toLowerCase();
        if (lower.startsWith("image/")) return FileCategory.PHOTO;
        if (lower.startsWith("video/")) return FileCategory.VIDEO;
        if (lower.equals("application/pdf") || lower.startsWith("application/vnd.")
                || lower.equals("application/msword") || lower.startsWith("text/")) {
            return FileCategory.DOCUMENT;
        }
        return FileCategory.OTHER;
    }

    /**
     * 构造 OSS object key: {factoryId}/attachments/{yyyy}/{MM}/{dd}/{uuid}_{fileName}
     */
    private String buildObjectKey(String factoryId, String fileName) {
        String dateDir = LocalDate.now().format(DATE_FORMAT);
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String safeName = fileName != null ? fileName.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_") : "file";
        return String.format("%s/attachments/%s/%s_%s", factoryId, dateDir, uuid, safeName);
    }

    /**
     * 从 pre-signed PUT URL 提取最终 fileUrl (脱去查询串).
     * Controller 在调 {@link #generateUploadUrl} 后用本方法把 fileUrl 一起返回给前端.
     */
    public static String stripQuery(String presignedUrl) {
        if (presignedUrl == null) return null;
        try {
            URI uri = URI.create(presignedUrl);
            return uri.getScheme() + "://" + uri.getHost() + uri.getRawPath();
        } catch (Exception e) {
            return presignedUrl;
        }
    }

    private boolean isAdmin() {
        return SecurityUtils.hasRole("FACTORY_SUPER_ADMIN")
                || SecurityUtils.hasRole("PLATFORM_ADMIN")
                || SecurityUtils.hasRole("FACTORY_ADMIN");
    }

    /**
     * PRODUCTION_REPORT 附件注册后, 把 OSS URL 同步进 production_reports.photos,
     * 供 Web 审批页 row.photos 直接渲染 (审计 2026-06-05 六扇门闭环).
     */
    private void syncProductionReportPhoto(String factoryId, String entityId, String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return;
        }
        try {
            Long reportId = Long.parseLong(entityId);
            ProductionReport report = productionReportRepository.findById(reportId).orElse(null);
            if (report == null || !factoryId.equals(report.getFactoryId())) {
                return;
            }
            List<String> photos = report.getPhotos() != null
                    ? new ArrayList<>(report.getPhotos())
                    : new ArrayList<>();
            if (!photos.contains(fileUrl)) {
                photos.add(fileUrl);
                report.setPhotos(photos);
                productionReportRepository.save(report);
            }
        } catch (NumberFormatException e) {
            log.warn("PRODUCTION_REPORT entityId 非数字, 跳过 photos 同步: {}", entityId);
        } catch (Exception e) {
            log.warn("同步报工 photos 失败 (attachment 已注册): entityId={} err={}", entityId, e.getMessage());
        }
    }
}
