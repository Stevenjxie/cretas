package com.cretas.aims.service;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.labelqc.LabelQcDtos.*;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.event.LabelQcAnalysisRequestedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.attachment.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LabelQcService {

    private static final int MAX_PHOTOS = 6;

    private final LabelQcTaskRepository taskRepository;
    private final LabelQcPhotoRepository photoRepository;
    private final LabelQcAnnotationRepository annotationRepository;
    private final ProductTypeRepository productTypeRepository;
    private final AttachmentService attachmentService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public TaskDetailResponse createTask(String factoryId, Long userId, CreateTaskRequest request) {
        requireUser(userId);
        Optional<LabelQcTask> existing = taskRepository
                .findByFactoryIdAndCreatedByAndIdempotencyKey(
                        factoryId, userId, request.idempotencyKey().trim());
        if (existing.isPresent()) {
            return detail(factoryId, existing.get().getId());
        }

        ProductType product = productTypeRepository
                .findByIdAndFactoryId(request.productTypeId().trim(), factoryId)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new BusinessException(404, "SKU 不存在或已停用")
                        .withCode("LABEL_QC_SKU_NOT_AVAILABLE")
                        .withHint("请重新选择当前工厂的有效 SKU"));
        if (product.getCode() == null || product.getCode().isBlank()) {
            throw new BusinessException("SKU 编码未配置，不能创建拍检任务")
                    .withCode("LABEL_QC_SKU_CODE_REQUIRED")
                    .withHint("请先完善产品类型的 SKU 编码");
        }
        if (request.productionDate().isAfter(LocalDate.now())) {
            throw new BusinessException("生产日期不能晚于今天")
                    .withCode("LABEL_QC_PRODUCTION_DATE_FUTURE");
        }

        LabelQcTask task = LabelQcTask.builder()
                .factoryId(factoryId)
                .productTypeId(product.getId())
                .skuCode(product.getCode())
                .skuName(product.getName())
                .batchNumber(request.batchNumber().trim())
                .productionDate(request.productionDate())
                .createdBy(userId)
                .idempotencyKey(request.idempotencyKey().trim())
                .status(LabelQcTaskStatus.DRAFT)
                .photoCount(0)
                .aiCandidateCount(0)
                .finalDefectCount(0)
                .build();
        taskRepository.save(task);
        return new TaskDetailResponse(toSummary(task), List.of());
    }

    @Transactional
    public PhotoResponse addPhoto(
            String factoryId,
            String taskId,
            AddPhotoRequest request) {
        LabelQcTask task = requireTask(factoryId, taskId);
        if (task.getStatus() != LabelQcTaskStatus.DRAFT
                && task.getStatus() != LabelQcTaskStatus.UPLOADING) {
            throw invalidState("只有草稿或上传中的任务可以登记照片");
        }
        Optional<LabelQcPhoto> existing = photoRepository
                .findByFactoryIdAndTaskIdAndAttachmentId(
                        factoryId, taskId, request.attachmentId());
        if (existing.isPresent()) {
            return toPhoto(factoryId, existing.get(), List.of(), false);
        }
        if (task.getPhotoCount() >= MAX_PHOTOS) {
            throw new BusinessException("每个拍检任务最多上传 6 张照片")
                    .withCode("LABEL_QC_PHOTO_LIMIT");
        }
        if (photoRepository.existsByFactoryIdAndTaskIdAndOrderIndex(
                factoryId, taskId, request.orderIndex())) {
            throw new BusinessException("照片顺序已被占用，请刷新后重试")
                    .withCode("LABEL_QC_PHOTO_ORDER_DUPLICATE");
        }

        Attachment attachment = attachmentService.getById(factoryId, request.attachmentId());
        if (attachment.getEntityType() != EntityType.QUALITY_CHECK
                || !taskId.equals(attachment.getEntityId())) {
            throw new BusinessException(400, "附件没有绑定到当前拍检任务")
                    .withCode("LABEL_QC_ATTACHMENT_MISMATCH");
        }
        if (attachment.getFileType() == null
                || !attachment.getFileType().toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BusinessException(400, "拍检附件必须是图片")
                    .withCode("LABEL_QC_ATTACHMENT_NOT_IMAGE");
        }
        if (attachment.getFileSize() != null && attachment.getFileSize() > 10 * 1024 * 1024L) {
            throw new BusinessException(400, "单张拍检照片不能超过 10 MB")
                    .withCode("LABEL_QC_ATTACHMENT_TOO_LARGE");
        }

        LabelQcPhoto photo = LabelQcPhoto.builder()
                .factoryId(factoryId)
                .taskId(taskId)
                .attachmentId(attachment.getId())
                .orderIndex(request.orderIndex())
                .imageWidth(request.imageWidth())
                .imageHeight(request.imageHeight())
                .status(LabelQcPhotoStatus.UPLOADED)
                .build();
        photoRepository.save(photo);
        task.setPhotoCount(task.getPhotoCount() + 1);
        task.setStatus(LabelQcTaskStatus.UPLOADING);
        taskRepository.save(task);
        return toPhoto(factoryId, photo, List.of(), false);
    }

    @Transactional
    public TaskDetailResponse submit(String factoryId, String taskId) {
        LabelQcTask task = requireTask(factoryId, taskId);
        if (EnumSet.of(
                LabelQcTaskStatus.QUEUED,
                LabelQcTaskStatus.ANALYZING,
                LabelQcTaskStatus.NEEDS_REVIEW,
                LabelQcTaskStatus.REVIEWED).contains(task.getStatus())) {
            return detail(factoryId, taskId);
        }
        if (task.getStatus() != LabelQcTaskStatus.DRAFT
                && task.getStatus() != LabelQcTaskStatus.UPLOADING) {
            throw invalidState("当前任务不能提交分析");
        }
        List<LabelQcPhoto> photos =
                photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(factoryId, taskId);
        if (photos.isEmpty()) {
            throw new BusinessException("请至少上传 1 张照片后再提交")
                    .withCode("LABEL_QC_PHOTO_REQUIRED")
                    .withHint("返回拍照页添加照片");
        }
        photos.forEach(photo -> photo.setStatus(LabelQcPhotoStatus.QUEUED));
        photoRepository.saveAll(photos);
        task.setStatus(LabelQcTaskStatus.QUEUED);
        taskRepository.save(task);
        eventPublisher.publishEvent(new LabelQcAnalysisRequestedEvent(taskId));
        return detail(factoryId, taskId);
    }

    @Transactional
    public TaskDetailResponse retryAnalysis(String factoryId, String taskId) {
        LabelQcTask task = requireTask(factoryId, taskId);
        if (task.getStatus() == LabelQcTaskStatus.REVIEWED) {
            throw invalidState("已完成人工审核的任务不能重新分析");
        }
        if (task.getStatus() == LabelQcTaskStatus.QUEUED
                || task.getStatus() == LabelQcTaskStatus.ANALYZING) {
            return detail(factoryId, taskId);
        }
        List<LabelQcPhoto> photos =
                photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(factoryId, taskId);
        if (photos.isEmpty()) {
            throw new BusinessException("任务没有可分析的照片")
                    .withCode("LABEL_QC_PHOTO_REQUIRED");
        }
        annotationRepository.deleteByFactoryIdAndTaskIdAndSource(
                factoryId, taskId, LabelQcAnnotationSource.AI);
        photos.forEach(photo -> {
            photo.setStatus(LabelQcPhotoStatus.QUEUED);
            photo.setAiModel(null);
            photo.setPromptVersion(null);
            photo.setAnalysisError(null);
        });
        photoRepository.saveAll(photos);
        task.setStatus(LabelQcTaskStatus.QUEUED);
        task.setAiCandidateCount(0);
        taskRepository.save(task);
        eventPublisher.publishEvent(new LabelQcAnalysisRequestedEvent(taskId));
        return detail(factoryId, taskId);
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskSummaryResponse> list(
            String factoryId,
            Collection<LabelQcTaskStatus> statuses,
            int page,
            int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(
                safePage - 1,
                safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<LabelQcTask> result = statuses == null || statuses.isEmpty()
                ? taskRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable)
                : taskRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                        factoryId, statuses, pageable);
        List<TaskSummaryResponse> content = result.getContent().stream()
                .map(this::toSummary)
                .toList();
        return PageResponse.of(content, safePage, safeSize, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public StatusCountsResponse statusCounts(String factoryId) {
        EnumMap<LabelQcTaskStatus, Long> counts = new EnumMap<>(LabelQcTaskStatus.class);
        for (LabelQcTaskStatus status : LabelQcTaskStatus.values()) {
            counts.put(status, taskRepository.countByFactoryIdAndStatus(factoryId, status));
        }
        return new StatusCountsResponse(counts);
    }

    @Transactional(readOnly = true)
    public TaskDetailResponse detail(String factoryId, String taskId) {
        LabelQcTask task = requireTask(factoryId, taskId);
        List<LabelQcPhoto> photos =
                photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(factoryId, taskId);
        Map<String, List<LabelQcAnnotation>> annotations = annotationRepository
                .findByFactoryIdAndTaskIdOrderByCreatedAtAsc(factoryId, taskId)
                .stream()
                .collect(Collectors.groupingBy(LabelQcAnnotation::getPhotoId));
        return new TaskDetailResponse(
                toSummary(task),
                photos.stream()
                        .map(photo -> toPhoto(
                                factoryId,
                                photo,
                                annotations.getOrDefault(photo.getId(), List.of()),
                                true))
                        .toList());
    }

    @Transactional
    public TaskDetailResponse review(
            String factoryId,
            String taskId,
            Long reviewerId,
            ReviewTaskRequest request) {
        requireUser(reviewerId);
        String reviewRequestId = resolveReviewRequestId(taskId, reviewerId, request);
        LabelQcTask task = taskRepository.findByFactoryIdAndIdForUpdate(factoryId, taskId)
                .orElseThrow(() -> new BusinessException(404, "标签拍检任务不存在")
                        .withCode("LABEL_QC_TASK_NOT_FOUND"));
        if (task.getStatus() == LabelQcTaskStatus.REVIEWED) {
            if (Objects.equals(task.getReviewRequestId(), reviewRequestId)) {
                return detail(factoryId, taskId);
            }
            throw new BusinessException(409, "这条质检任务已由另一台设备或页面完成，未覆盖已保存结果")
                    .withCode("LABEL_QC_ALREADY_REVIEWED")
                    .withHint("返回待审核列表并选择下一条任务")
                    .withSeverity("warning");
        }
        if (request.expectedVersion() != null
                && !Objects.equals(task.getVersion(), request.expectedVersion())) {
            throw new BusinessException(409, "这条质检任务已发生变化，当前修改尚未提交")
                    .withCode("LABEL_QC_REVIEW_STALE")
                    .withHint("返回待审核列表刷新后再处理")
                    .withSeverity("warning");
        }
        if (task.getStatus() != LabelQcTaskStatus.NEEDS_REVIEW
                && task.getStatus() != LabelQcTaskStatus.ANALYSIS_FAILED) {
            throw invalidState("任务尚未进入人工审核状态");
        }

        List<LabelQcPhoto> photos =
                photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(factoryId, taskId);
        Map<String, LabelQcPhoto> photoById = photos.stream()
                .collect(Collectors.toMap(LabelQcPhoto::getId, Function.identity()));
        if (request.photos().size() != photos.size()) {
            throw new BusinessException("必须逐张完成审核后才能提交")
                    .withCode("LABEL_QC_REVIEW_INCOMPLETE");
        }

        List<LabelQcAnnotation> existing = annotationRepository
                .findByFactoryIdAndTaskIdOrderByCreatedAtAsc(factoryId, taskId);
        Map<String, LabelQcAnnotation> existingById = existing.stream()
                .collect(Collectors.toMap(LabelQcAnnotation::getId, Function.identity()));
        Set<String> aiAnnotationIds = existing.stream()
                .filter(value -> value.getSource() == LabelQcAnnotationSource.AI)
                .map(LabelQcAnnotation::getId)
                .collect(Collectors.toSet());
        Set<String> reviewedAiIds = new HashSet<>();
        Set<String> reviewedPhotoIds = new HashSet<>();
        List<LabelQcAnnotation> additions = new ArrayList<>();
        int finalDefects = 0;

        for (PhotoReviewRequest photoReview : request.photos()) {
            LabelQcPhoto photo = photoById.get(photoReview.photoId());
            if (photo == null) {
                throw new BusinessException("审核请求包含不属于当前任务的照片")
                        .withCode("LABEL_QC_REVIEW_PHOTO_MISMATCH");
            }
            if (!reviewedPhotoIds.add(photo.getId())) {
                throw new BusinessException("同一张照片不能重复提交审核")
                        .withCode("LABEL_QC_REVIEW_PHOTO_DUPLICATE");
            }
            for (AnnotationReviewRequest annotationReview : photoReview.annotations()) {
                validateReviewAnnotation(annotationReview);
                if (isDefect(annotationReview.label())) {
                    finalDefects++;
                }
                if (annotationReview.annotationId() != null
                        && !annotationReview.annotationId().isBlank()) {
                    LabelQcAnnotation annotation = existingById.get(annotationReview.annotationId());
                    if (annotation == null
                            || annotation.getSource() != LabelQcAnnotationSource.AI
                            || !photo.getId().equals(annotation.getPhotoId())) {
                        throw new BusinessException("AI 候选框与照片不匹配")
                                .withCode("LABEL_QC_ANNOTATION_MISMATCH");
                    }
                    if (!reviewedAiIds.add(annotation.getId())) {
                        throw new BusinessException("同一个 AI 候选框不能重复提交审核")
                                .withCode("LABEL_QC_ANNOTATION_DUPLICATE");
                    }
                    annotation.setHumanLabel(annotationReview.label());
                    annotation.setReviewerNotes(trimToNull(annotationReview.notes()));
                    annotation.setReviewedBy(reviewerId);
                    if (annotationReview.bbox() != null) {
                        applyBox(annotation, annotationReview.bbox());
                    }
                } else {
                    LabelQcAnnotation annotation = LabelQcAnnotation.builder()
                            .factoryId(factoryId)
                            .taskId(taskId)
                            .photoId(photo.getId())
                            .source(LabelQcAnnotationSource.HUMAN)
                            .humanLabel(annotationReview.label())
                            .reviewerNotes(trimToNull(annotationReview.notes()))
                            .reviewedBy(reviewerId)
                            .build();
                    if (annotationReview.bbox() != null) {
                        applyBox(annotation, annotationReview.bbox());
                    }
                    additions.add(annotation);
                }
            }
            photo.setStatus(LabelQcPhotoStatus.REVIEWED);
        }
        if (!reviewedPhotoIds.equals(photoById.keySet())) {
            throw new BusinessException("必须逐张完成审核后才能提交")
                    .withCode("LABEL_QC_REVIEW_INCOMPLETE");
        }
        if (!reviewedAiIds.equals(aiAnnotationIds)) {
            throw new BusinessException("仍有 AI 候选框未确认")
                    .withCode("LABEL_QC_AI_CANDIDATE_UNREVIEWED");
        }

        annotationRepository.saveAll(existing);
        annotationRepository.saveAll(additions);
        photoRepository.saveAll(photos);
        task.setStatus(LabelQcTaskStatus.REVIEWED);
        task.setFinalDefectCount(finalDefects);
        task.setReviewedBy(reviewerId);
        task.setReviewedAt(LocalDateTime.now());
        task.setReviewRequestId(reviewRequestId);
        taskRepository.save(task);
        return detail(factoryId, taskId);
    }

    @Transactional(readOnly = true)
    public List<TrainingPhoto> exportTrainingData(
            String factoryId,
            LocalDateTime from,
            LocalDateTime to,
            int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        Page<LabelQcTask> tasks = taskRepository
                .findByFactoryIdAndStatusAndReviewedAtBetweenOrderByReviewedAtAsc(
                        factoryId,
                        LabelQcTaskStatus.REVIEWED,
                        from,
                        to,
                        PageRequest.of(0, safeLimit));
        List<TrainingPhoto> result = new ArrayList<>();
        for (LabelQcTask task : tasks) {
            List<LabelQcPhoto> photos =
                    photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(factoryId, task.getId());
            Map<String, List<LabelQcAnnotation>> byPhoto = annotationRepository
                    .findByFactoryIdAndTaskIdOrderByCreatedAtAsc(factoryId, task.getId())
                    .stream()
                    .filter(value -> value.getHumanLabel() != null)
                    .collect(Collectors.groupingBy(LabelQcAnnotation::getPhotoId));
            for (LabelQcPhoto photo : photos) {
                result.add(new TrainingPhoto(
                        task.getId(),
                        photo.getId(),
                        attachmentService.generateDownloadUrl(factoryId, photo.getAttachmentId()),
                        photo.getImageWidth(),
                        photo.getImageHeight(),
                        task.getSkuCode(),
                        task.getSkuName(),
                        task.getBatchNumber(),
                        task.getProductionDate(),
                        task.getReviewedAt(),
                        byPhoto.getOrDefault(photo.getId(), List.of()).stream()
                                .map(this::toAnnotation)
                                .toList()));
            }
        }
        return result;
    }

    private LabelQcTask requireTask(String factoryId, String taskId) {
        return taskRepository.findByFactoryIdAndId(factoryId, taskId)
                .orElseThrow(() -> new BusinessException(404, "标签拍检任务不存在")
                        .withCode("LABEL_QC_TASK_NOT_FOUND"));
    }

    private String resolveReviewRequestId(
            String taskId,
            Long reviewerId,
            ReviewTaskRequest request) {
        String supplied = trimToNull(request.reviewRequestId());
        if (supplied != null) {
            return supplied;
        }
        String legacyFingerprint = taskId + "|" + reviewerId + "|" + request.photos();
        return "legacy-" + UUID.nameUUIDFromBytes(
                legacyFingerprint.getBytes(StandardCharsets.UTF_8));
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "登录状态无效，请重新登录")
                    .withCode("AUTH_REQUIRED");
        }
    }

    private BusinessException invalidState(String message) {
        return new BusinessException(409, message)
                .withCode("LABEL_QC_INVALID_STATE")
                .withHint("请刷新任务状态后重试");
    }

    private void validateReviewAnnotation(AnnotationReviewRequest request) {
        if (isDefect(request.label()) && request.bbox() == null) {
            throw new BusinessException("缺标结论必须保留或补画问题框")
                    .withCode("LABEL_QC_BBOX_REQUIRED");
        }
        if (request.bbox() != null
                && (request.bbox().xMin() >= request.bbox().xMax()
                || request.bbox().yMin() >= request.bbox().yMax())) {
            throw new BusinessException("标注框范围无效")
                    .withCode("LABEL_QC_BBOX_INVALID");
        }
    }

    private boolean isDefect(LabelQcLabel label) {
        return label == LabelQcLabel.MISSING_WHITE_LABEL
                || label == LabelQcLabel.MISSING_COLOR_LABEL;
    }

    private void applyBox(LabelQcAnnotation target, BoundingBox box) {
        target.setXMin(box.xMin());
        target.setYMin(box.yMin());
        target.setXMax(box.xMax());
        target.setYMax(box.yMax());
    }

    private TaskSummaryResponse toSummary(LabelQcTask task) {
        return new TaskSummaryResponse(
                task.getId(),
                task.getProductTypeId(),
                task.getSkuCode(),
                task.getSkuName(),
                task.getBatchNumber(),
                task.getProductionDate(),
                task.getCreatedBy(),
                task.getStatus(),
                task.getVersion(),
                task.getPhotoCount(),
                task.getAiCandidateCount(),
                task.getFinalDefectCount(),
                task.getReviewedBy(),
                task.getReviewedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    private PhotoResponse toPhoto(
            String factoryId,
            LabelQcPhoto photo,
            List<LabelQcAnnotation> annotations,
            boolean includeUrl) {
        return new PhotoResponse(
                photo.getId(),
                photo.getAttachmentId(),
                photo.getOrderIndex(),
                photo.getImageWidth(),
                photo.getImageHeight(),
                photo.getStatus(),
                includeUrl
                        ? attachmentService.generateDownloadUrl(factoryId, photo.getAttachmentId())
                        : null,
                photo.getAiModel(),
                photo.getPromptVersion(),
                photo.getAnalysisError(),
                annotations.stream().map(this::toAnnotation).toList());
    }

    private AnnotationResponse toAnnotation(LabelQcAnnotation annotation) {
        BoundingBox box = annotation.getXMin() == null
                ? null
                : new BoundingBox(
                        annotation.getXMin(),
                        annotation.getYMin(),
                        annotation.getXMax(),
                        annotation.getYMax());
        return new AnnotationResponse(
                annotation.getId(),
                annotation.getSource(),
                annotation.getAiCandidateId(),
                annotation.getAiLabel(),
                annotation.getAiConfidence(),
                annotation.getAiEvidence(),
                annotation.getHumanLabel(),
                box,
                annotation.getReviewerNotes());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
