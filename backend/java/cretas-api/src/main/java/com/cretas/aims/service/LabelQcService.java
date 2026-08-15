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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LabelQcService {

    private static final int MAX_PHOTOS = 6;
    /**
     * 允许提前生产：车间 3 号可能在做 4 号、5 号的货，拍检时要能选到未来日期。
     * 上限 3 天来自客户口径（"往后一天，或者往后两到三天都行"），再远就当输错拦住。
     */
    private static final int MAX_PRODUCTION_DATE_LOOKAHEAD_DAYS = 3;
    private static final int MAX_PAGE_SIZE = 500;
    /** 生产日期筛选只给了一端时，用这两个哨兵把另一端放开，避免为单端再加一组派生查询。 */
    private static final LocalDate PRODUCTION_DATE_FLOOR = LocalDate.of(2000, 1, 1);
    private static final LocalDate PRODUCTION_DATE_CEILING = LocalDate.of(9999, 12, 31);
    private static final double TRAY_CROP_PADDING_RATIO = 0.03;
    private static final String TRAY_CROP_ALGORITHM_VERSION = "tray-union-pad-v1";

    private final LabelQcTaskRepository taskRepository;
    private final LabelQcPhotoRepository photoRepository;
    private final LabelQcAnnotationRepository annotationRepository;
    private final LabelQcTrayCropRepository trayCropRepository;
    private final ProductTypeRepository productTypeRepository;
    private final AttachmentService attachmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

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
        String productCategory = Optional.ofNullable(product.getProductCategory())
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .orElse("");
        if (!ProductCategory.FINISHED_PRODUCT.equals(productCategory)) {
            throw new BusinessException(400, "该 SKU 不是成品，不能用于标签拍检")
                    .withCode("LABEL_QC_FINISHED_SKU_REQUIRED")
                    .withHint("请重新选择当前工厂已启用的成品 SKU");
        }
        if (product.getCode() == null || product.getCode().isBlank()) {
            throw new BusinessException("SKU 编码未配置，不能创建拍检任务")
                    .withCode("LABEL_QC_SKU_CODE_REQUIRED")
                    .withHint("请先完善产品类型的 SKU 编码");
        }
        LocalDate latestProductionDate =
                LocalDate.now().plusDays(MAX_PRODUCTION_DATE_LOOKAHEAD_DAYS);
        if (request.productionDate().isAfter(latestProductionDate)) {
            throw new BusinessException(
                    "生产日期最多只能选到 " + latestProductionDate + "（今天起 "
                            + MAX_PRODUCTION_DATE_LOOKAHEAD_DAYS + " 天内，支持提前生产）")
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
            photo.setScreeningDetail(null);
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
            boolean archived,
            LocalDate productionDateFrom,
            LocalDate productionDateTo,
            int page,
            int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(
                safePage - 1,
                safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        boolean filterByProductionDate = productionDateFrom != null || productionDateTo != null;
        LocalDate fromDate = productionDateFrom != null ? productionDateFrom : PRODUCTION_DATE_FLOOR;
        LocalDate toDate = productionDateTo != null ? productionDateTo : PRODUCTION_DATE_CEILING;
        if (filterByProductionDate && fromDate.isAfter(toDate)) {
            throw new BusinessException("生产日期起始不能晚于结束日期")
                    .withCode("LABEL_QC_PRODUCTION_DATE_RANGE_INVALID");
        }
        Page<LabelQcTask> result;
        if (statuses == null || statuses.isEmpty()) {
            result = filterByProductionDate
                    ? taskRepository
                            .findByFactoryIdAndArchivedAndProductionDateBetweenOrderByCreatedAtDesc(
                                    factoryId, archived, fromDate, toDate, pageable)
                    : taskRepository.findByFactoryIdAndArchivedOrderByCreatedAtDesc(
                            factoryId, archived, pageable);
        } else {
            result = filterByProductionDate
                    ? taskRepository
                            .findByFactoryIdAndArchivedAndStatusInAndProductionDateBetweenOrderByCreatedAtDesc(
                                    factoryId, archived, statuses, fromDate, toDate, pageable)
                    : taskRepository.findByFactoryIdAndArchivedAndStatusInOrderByCreatedAtDesc(
                            factoryId, archived, statuses, pageable);
        }
        List<TaskSummaryResponse> content = result.getContent().stream()
                .map(this::toSummary)
                .toList();
        return PageResponse.of(content, safePage, safeSize, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public StatusCountsResponse statusCounts(String factoryId) {
        EnumMap<LabelQcTaskStatus, Long> counts = new EnumMap<>(LabelQcTaskStatus.class);
        for (LabelQcTaskStatus status : LabelQcTaskStatus.values()) {
            counts.put(
                    status,
                    taskRepository.countByFactoryIdAndArchivedAndStatus(factoryId, false, status));
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
            if (photoReview.objectReview() != null) {
                validateObjectReview(photo, photoReview.objectReview());
                photo.setObjectReviewDetail(writeObjectReview(photoReview.objectReview()));
                photo.setObjectReviewedBy(reviewerId);
                photo.setObjectReviewedAt(LocalDateTime.now());
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
        createTrayCropQueue(factoryId, taskId, photos);
        task.setStatus(LabelQcTaskStatus.REVIEWED);
        task.setFinalDefectCount(finalDefects);
        task.setReviewedBy(reviewerId);
        task.setReviewedAt(LocalDateTime.now());
        task.setReviewRequestId(reviewRequestId);
        task.setTrainingStatus(LabelQcTrainingStatus.PENDING);
        task.setTrainingDecidedBy(null);
        task.setTrainingDecidedAt(null);
        task.setTrainingDecisionNotes(null);
        taskRepository.save(task);
        return detail(factoryId, taskId);
    }

    @Transactional
    public TaskDetailResponse archive(
            String factoryId,
            String taskId,
            Long userId) {
        requireUser(userId);
        LabelQcTask task = requireReviewedTaskForUpdate(factoryId, taskId);
        if (!Boolean.TRUE.equals(task.getArchived())) {
            task.setArchived(true);
            task.setArchivedBy(userId);
            task.setArchivedAt(LocalDateTime.now());
            taskRepository.save(task);
        }
        return detail(factoryId, taskId);
    }

    @Transactional
    public TaskDetailResponse restore(
            String factoryId,
            String taskId,
            Long userId) {
        requireUser(userId);
        LabelQcTask task = requireReviewedTaskForUpdate(factoryId, taskId);
        if (Boolean.TRUE.equals(task.getArchived())) {
            task.setArchived(false);
            task.setArchivedBy(null);
            task.setArchivedAt(null);
            taskRepository.save(task);
        }
        return detail(factoryId, taskId);
    }

    @Transactional
    public TaskBackupResponse exportBackup(
            String factoryId,
            String taskId,
            Long userId) {
        requireUser(userId);
        LabelQcTask task = requireReviewedTaskForUpdate(factoryId, taskId);
        LocalDateTime exportedAt = LocalDateTime.now();
        task.setBackupExportedBy(userId);
        task.setBackupExportedAt(exportedAt);
        taskRepository.save(task);
        return new TaskBackupResponse(detail(factoryId, taskId), exportedAt, userId);
    }

    @Transactional
    public TaskDetailResponse decideTraining(
            String factoryId,
            String taskId,
            Long technicalAdminId,
            TrainingDecisionRequest request) {
        requireUser(technicalAdminId);
        LabelQcTask task = requireReviewedTaskForUpdate(factoryId, taskId);
        if (request.expectedVersion() != null
                && !Objects.equals(task.getVersion(), request.expectedVersion())) {
            throw new BusinessException(409, "任务已被其他管理员更新，请刷新后重试")
                    .withCode("LABEL_QC_TRAINING_DECISION_STALE");
        }
        task.setTrainingStatus(Boolean.TRUE.equals(request.approved())
                ? LabelQcTrainingStatus.APPROVED
                : LabelQcTrainingStatus.REJECTED);
        task.setTrainingDecidedBy(technicalAdminId);
        task.setTrainingDecidedAt(LocalDateTime.now());
        task.setTrainingDecisionNotes(trimToNull(request.notes()));
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
                .findByFactoryIdAndStatusAndTrainingStatusAndReviewedAtBetweenOrderByReviewedAtAsc(
                        factoryId,
                        LabelQcTaskStatus.REVIEWED,
                        LabelQcTrainingStatus.APPROVED,
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
                        readObjectReview(photo.getObjectReviewDetail()),
                        byPhoto.getOrDefault(photo.getId(), List.of()).stream()
                                .map(this::toAnnotation)
                                .toList()));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PageResponse<TrayCropResponse> listTrayCrops(
            String factoryId,
            LabelQcTrayCropStatus status,
            int page,
            int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize);
        Page<LabelQcTrayCrop> crops = status == null
                ? trayCropRepository.findByFactoryIdOrderByCreatedAtAsc(factoryId, pageable)
                : trayCropRepository.findByFactoryIdAndStatusOrderByCreatedAtAsc(factoryId, status, pageable);
        List<TrayCropResponse> content = crops.getContent().stream()
                .map(crop -> toTrayCropResponse(factoryId, crop))
                .toList();
        return PageResponse.of(content, safePage, safeSize, crops.getTotalElements());
    }

    @Transactional
    public TrayCropResponse reviewTrayCrop(
            String factoryId,
            String cropId,
            Long reviewerId,
            ReviewTrayCropRequest request) {
        requireUser(reviewerId);
        LabelQcTrayCrop crop = trayCropRepository.findByFactoryIdAndId(factoryId, cropId)
                .orElseThrow(() -> new BusinessException(404, "单盒标签精修任务不存在")
                        .withCode("LABEL_QC_TRAY_CROP_NOT_FOUND"));
        if (!Objects.equals(crop.getRowVersion(), request.expectedVersion())) {
            throw new BusinessException(409, "这个单盒任务已被其他平台人员更新，当前修改未覆盖服务器结果")
                    .withCode("LABEL_QC_TRAY_CROP_STALE")
                    .withHint("刷新队列后继续处理");
        }
        validatePlatformTrayReview(request.review());
        crop.setPlatformReviewDetail(writeJson(request.review(), "平台标签真值保存失败"));
        crop.setStatus(Boolean.TRUE.equals(request.review().unjudgeable())
                ? LabelQcTrayCropStatus.UNJUDGEABLE
                : LabelQcTrayCropStatus.REVIEWED);
        crop.setReviewedBy(reviewerId);
        crop.setReviewedAt(LocalDateTime.now());
        trayCropRepository.saveAndFlush(crop);
        return toTrayCropResponse(factoryId, crop);
    }

    private void createTrayCropQueue(
            String factoryId,
            String taskId,
            List<LabelQcPhoto> photos) {
        for (LabelQcPhoto photo : photos) {
            ObjectReviewPayload review = readObjectReview(photo.getObjectReviewDetail());
            if (review == null || !Boolean.TRUE.equals(review.complete())) continue;
            String reviewJson = writeObjectReview(review);
            String reviewSha = sha256(reviewJson);
            Attachment source = attachmentService.getById(factoryId, photo.getAttachmentId());
            String sourceSha = normalizeSha256(source.getFileHash());
            for (TrayObjectReview tray : review.trays()) {
                if (tray.labels().isEmpty()
                        && tray.whitePresence() == LabelQcPresence.UNJUDGEABLE
                        && tray.colorPresence() == LabelQcPresence.UNJUDGEABLE) {
                    continue;
                }
                BoundingBox cropBox = paddedUnionBox(tray);
                String stableTrayIdentity = tray.aiTrayKey() == null || tray.aiTrayKey().isBlank()
                        ? "human:" + tray.trayIndex()
                        : "ai:" + tray.aiTrayKey().trim();
                String specMaterial = String.join("|",
                        photo.getId(),
                        stableTrayIdentity,
                        reviewSha,
                        TRAY_CROP_ALGORITHM_VERSION,
                        canonicalBox(cropBox));
                String cropSpecSha = sha256(specMaterial);
                if (trayCropRepository.findByFactoryIdAndCropSpecSha256(factoryId, cropSpecSha).isPresent()) {
                    continue;
                }
                PlatformTrayReviewPayload proposals = toCropProposal(tray, cropBox);
                LabelQcTrayCrop crop = LabelQcTrayCrop.builder()
                        .factoryId(factoryId)
                        .taskId(taskId)
                        .photoId(photo.getId())
                        .sourceAttachmentId(photo.getAttachmentId())
                        .sourceImageSha256(sourceSha)
                        .trayIndex(tray.trayIndex())
                        .aiTrayKey(normalizeKey(tray.aiTrayKey()))
                        .sourceDecision(tray.decision())
                        .trayXMin(tray.bbox().xMin())
                        .trayYMin(tray.bbox().yMin())
                        .trayXMax(tray.bbox().xMax())
                        .trayYMax(tray.bbox().yMax())
                        .cropXMin(cropBox.xMin())
                        .cropYMin(cropBox.yMin())
                        .cropXMax(cropBox.xMax())
                        .cropYMax(cropBox.yMax())
                        .paddingRatio(TRAY_CROP_PADDING_RATIO)
                        .cropAlgorithmVersion(TRAY_CROP_ALGORITHM_VERSION)
                        .objectReviewSha256(reviewSha)
                        .cropSpecSha256(cropSpecSha)
                        .coordinateTransform(writeJson(coordinateTransform(cropBox), "裁切坐标变换保存失败"))
                        .factoryLabelProposals(writeJson(proposals, "工厂标签提议保存失败"))
                        .status(LabelQcTrayCropStatus.PENDING)
                        .build();
                trayCropRepository.save(crop);
            }
        }
    }

    private BoundingBox paddedUnionBox(TrayObjectReview tray) {
        double xMin = tray.bbox().xMin();
        double yMin = tray.bbox().yMin();
        double xMax = tray.bbox().xMax();
        double yMax = tray.bbox().yMax();
        for (LabelObjectReview label : tray.labels()) {
            xMin = Math.min(xMin, label.bbox().xMin());
            yMin = Math.min(yMin, label.bbox().yMin());
            xMax = Math.max(xMax, label.bbox().xMax());
            yMax = Math.max(yMax, label.bbox().yMax());
        }
        double width = xMax - xMin;
        double height = yMax - yMin;
        return new BoundingBox(
                Math.max(0, xMin - width * TRAY_CROP_PADDING_RATIO),
                Math.max(0, yMin - height * TRAY_CROP_PADDING_RATIO),
                Math.min(1, xMax + width * TRAY_CROP_PADDING_RATIO),
                Math.min(1, yMax + height * TRAY_CROP_PADDING_RATIO));
    }

    private PlatformTrayReviewPayload toCropProposal(TrayObjectReview tray, BoundingBox cropBox) {
        List<PlatformLabelReview> labels = tray.labels().stream()
                .map(label -> new PlatformLabelReview(
                        label.type(),
                        originalToCrop(label.bbox(), cropBox),
                        label.truncated()))
                .toList();
        boolean unjudgeable = labels.isEmpty()
                && tray.whitePresence() == LabelQcPresence.UNJUDGEABLE
                && tray.colorPresence() == LabelQcPresence.UNJUDGEABLE;
        return new PlatformTrayReviewPayload(
                1, true, unjudgeable,
                tray.whitePresence(), tray.colorPresence(), labels);
    }

    private BoundingBox originalToCrop(BoundingBox value, BoundingBox crop) {
        double width = crop.xMax() - crop.xMin();
        double height = crop.yMax() - crop.yMin();
        return new BoundingBox(
                clamp01((value.xMin() - crop.xMin()) / width),
                clamp01((value.yMin() - crop.yMin()) / height),
                clamp01((value.xMax() - crop.xMin()) / width),
                clamp01((value.yMax() - crop.yMin()) / height));
    }

    private Map<String, Object> coordinateTransform(BoundingBox crop) {
        double width = crop.xMax() - crop.xMin();
        double height = crop.yMax() - crop.yMin();
        return Map.of(
                "version", 1,
                "originalToCrop", Map.of(
                        "scaleX", 1.0 / width,
                        "scaleY", 1.0 / height,
                        "translateX", -crop.xMin() / width,
                        "translateY", -crop.yMin() / height),
                "cropToOriginal", Map.of(
                        "scaleX", width,
                        "scaleY", height,
                        "translateX", crop.xMin(),
                        "translateY", crop.yMin()));
    }

    private void validatePlatformTrayReview(PlatformTrayReviewPayload review) {
        if (!Boolean.TRUE.equals(review.complete())) {
            throw objectReviewError("平台单盒标签尚未完成精修");
        }
        if (Boolean.TRUE.equals(review.unjudgeable())) {
            if (!review.labels().isEmpty()
                    || review.whitePresence() != LabelQcPresence.UNJUDGEABLE
                    || review.colorPresence() != LabelQcPresence.UNJUDGEABLE) {
                throw objectReviewError("整盒无法判断时不能同时保留标签框或确定标签存在性");
            }
            return;
        }
        long white = 0;
        long color = 0;
        for (PlatformLabelReview label : review.labels()) {
            validateBox(label.bbox(), "平台标签");
            if (label.type() == LabelQcObjectType.WHITE_LABEL) white++;
            if (label.type() == LabelQcObjectType.COLOR_LABEL) color++;
        }
        validatePresence(review.whitePresence(), white, "白标");
        validatePresence(review.colorPresence(), color, "彩标");
    }

    private TrayCropResponse toTrayCropResponse(String factoryId, LabelQcTrayCrop crop) {
        LabelQcPhoto photo = photoRepository.findByFactoryIdAndId(factoryId, crop.getPhotoId())
                .orElseThrow(() -> new BusinessException(500, "单盒任务的来源照片不存在")
                        .withCode("LABEL_QC_TRAY_CROP_SOURCE_MISSING"));
        return new TrayCropResponse(
                crop.getId(), crop.getRowVersion(), crop.getTaskId(), crop.getPhotoId(),
                crop.getTrayIndex(), crop.getAiTrayKey(), crop.getSourceDecision(),
                crop.getSourceImageSha256(), crop.getObjectReviewSha256(), crop.getCropSpecSha256(),
                crop.getCropAlgorithmVersion(), crop.getPaddingRatio(),
                new BoundingBox(crop.getTrayXMin(), crop.getTrayYMin(), crop.getTrayXMax(), crop.getTrayYMax()),
                new BoundingBox(crop.getCropXMin(), crop.getCropYMin(), crop.getCropXMax(), crop.getCropYMax()),
                crop.getCoordinateTransform(),
                attachmentService.generateDownloadUrl(factoryId, crop.getSourceAttachmentId()),
                photo.getImageWidth(), photo.getImageHeight(), crop.getStatus(),
                readJson(crop.getFactoryLabelProposals(), PlatformTrayReviewPayload.class, "工厂标签提议损坏"),
                readJson(crop.getPlatformReviewDetail(), PlatformTrayReviewPayload.class, "平台标签真值损坏"),
                crop.getReviewedBy(), crop.getReviewedAt(), crop.getCreatedAt(), crop.getUpdatedAt());
    }

    private String canonicalBox(BoundingBox box) {
        return String.format(Locale.ROOT, "%.12f,%.12f,%.12f,%.12f",
                box.xMin(), box.yMin(), box.xMax(), box.yMax());
    }

    private double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String normalizeSha256(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) return null;
        return value.toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String writeJson(Object value, String message) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, message).withCode("LABEL_QC_JSON_WRITE_FAILED");
        }
    }

    private <T> T readJson(String value, Class<T> type, String message) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, message).withCode("LABEL_QC_JSON_CORRUPT");
        }
    }

    private LabelQcTask requireTask(String factoryId, String taskId) {
        return taskRepository.findByFactoryIdAndId(factoryId, taskId)
                .orElseThrow(() -> new BusinessException(404, "标签拍检任务不存在")
                        .withCode("LABEL_QC_TASK_NOT_FOUND"));
    }

    private LabelQcTask requireReviewedTaskForUpdate(String factoryId, String taskId) {
        LabelQcTask task = taskRepository.findByFactoryIdAndIdForUpdate(factoryId, taskId)
                .orElseThrow(() -> new BusinessException(404, "标签拍检任务不存在")
                        .withCode("LABEL_QC_TASK_NOT_FOUND"));
        if (task.getStatus() != LabelQcTaskStatus.REVIEWED) {
            throw new BusinessException(409, "只有已完成人工审核的任务可以整理")
                    .withCode("LABEL_QC_REVIEW_REQUIRED");
        }
        return task;
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
                Boolean.TRUE.equals(task.getArchived()),
                task.getArchivedBy(),
                task.getArchivedAt(),
                task.getTrainingStatus() == null
                        ? LabelQcTrainingStatus.PENDING
                        : task.getTrainingStatus(),
                task.getTrainingDecidedBy(),
                task.getTrainingDecidedAt(),
                task.getTrainingDecisionNotes(),
                task.getBackupExportedBy(),
                task.getBackupExportedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    private void validateObjectReview(LabelQcPhoto photo, ObjectReviewPayload review) {
        if (!Boolean.TRUE.equals(review.complete())) {
            throw objectReviewError("本图的盒子与标签尚未全部确认");
        }

        ScreeningObjectIndex expected = parseScreeningObjectIndex(photo.getScreeningDetail());
        Set<Integer> trayIndexes = new HashSet<>();
        Set<String> usedAiTrayKeys = new HashSet<>();
        Set<String> usedAiObjectKeys = new HashSet<>();
        Set<String> rejectedAiTrayKeys = normalizedUniqueKeys(
                review.rejectedAiTrayKeys(), "被删除的 AI 盒子");

        for (TrayObjectReview tray : review.trays()) {
            if (!trayIndexes.add(tray.trayIndex())) {
                throw objectReviewError("同一个盒子编号不能重复提交");
            }
            validateBox(tray.bbox(), "盒子");
            String aiTrayKey = normalizeKey(tray.aiTrayKey());
            validateDecision(tray.decision(), aiTrayKey, "盒子");
            if (aiTrayKey != null) {
                Integer owner = expected.trayOwners().get(aiTrayKey);
                if (owner == null || !owner.equals(tray.trayIndex())) {
                    throw objectReviewError("盒子引用的 AI 原始框与当前照片不匹配");
                }
                if (!usedAiTrayKeys.add(aiTrayKey)) {
                    throw objectReviewError("同一个 AI 盒子不能重复确认");
                }
            }

            Set<String> rejectedInTray = normalizedUniqueKeys(
                    tray.rejectedAiObjectKeys(), "被删除的 AI 标签");
            for (String rejectedKey : rejectedInTray) {
                Integer owner = expected.labelOwners().get(rejectedKey);
                if (owner == null || !owner.equals(tray.trayIndex())) {
                    throw objectReviewError("被删除的 AI 标签不属于当前盒子");
                }
                if (!usedAiObjectKeys.add(rejectedKey)) {
                    throw objectReviewError("同一个 AI 标签不能重复处理");
                }
            }

            long whiteCount = 0;
            long colorCount = 0;
            for (LabelObjectReview label : tray.labels()) {
                validateBox(label.bbox(), "标签");
                if (!boxContainsCenter(tray.bbox(), label.bbox())) {
                    throw objectReviewError("标签中心必须位于所属盒子内，请先选择正确盒子");
                }
                String aiObjectKey = normalizeKey(label.aiObjectKey());
                validateDecision(label.decision(), aiObjectKey, "标签");
                if (aiObjectKey != null) {
                    Integer owner = expected.labelOwners().get(aiObjectKey);
                    if (owner == null || !owner.equals(tray.trayIndex())) {
                        throw objectReviewError("标签引用的 AI 原始框不属于当前盒子");
                    }
                    if (!usedAiObjectKeys.add(aiObjectKey)) {
                        throw objectReviewError("同一个 AI 标签不能重复确认");
                    }
                }
                if (label.type() == LabelQcObjectType.WHITE_LABEL) whiteCount++;
                if (label.type() == LabelQcObjectType.COLOR_LABEL) colorCount++;
            }
            validatePresence(tray.whitePresence(), whiteCount, "白标");
            validatePresence(tray.colorPresence(), colorCount, "彩标");
        }

        if (!Collections.disjoint(usedAiTrayKeys, rejectedAiTrayKeys)) {
            throw objectReviewError("同一个 AI 盒子不能既保留又删除");
        }
        Set<String> coveredTrayKeys = new HashSet<>(usedAiTrayKeys);
        coveredTrayKeys.addAll(rejectedAiTrayKeys);
        if (!coveredTrayKeys.equals(expected.trayOwners().keySet())) {
            throw objectReviewError("仍有 AI 盒子未确认，或请求包含了当前照片不存在的盒子");
        }

        Set<String> labelsUnderRejectedTrays = rejectedAiTrayKeys.stream()
                .flatMap(key -> expected.labelsByTray().getOrDefault(key, Set.of()).stream())
                .collect(Collectors.toSet());
        Set<String> expectedLabelKeys = new HashSet<>(expected.labelOwners().keySet());
        expectedLabelKeys.removeAll(labelsUnderRejectedTrays);
        if (!usedAiObjectKeys.equals(expectedLabelKeys)) {
            throw objectReviewError("仍有 AI 白标或彩标未确认，或请求包含了当前照片不存在的标签");
        }
    }

    private ScreeningObjectIndex parseScreeningObjectIndex(String screeningDetail) {
        if (screeningDetail == null || screeningDetail.isBlank()) {
            return new ScreeningObjectIndex(Map.of(), Map.of(), Map.of());
        }
        try {
            JsonNode trays = objectMapper.readTree(screeningDetail).path("trays");
            if (!trays.isArray()) {
                return new ScreeningObjectIndex(Map.of(), Map.of(), Map.of());
            }
            Map<String, Integer> trayOwners = new HashMap<>();
            Map<String, Integer> labelOwners = new HashMap<>();
            Map<String, Set<String>> labelsByTray = new HashMap<>();
            int order = 0;
            for (JsonNode tray : trays) {
                int trayIndex = tray.path("index").isInt() ? tray.path("index").asInt() : order;
                String trayKey = "tray-" + trayIndex;
                if (trayOwners.putIfAbsent(trayKey, trayIndex) != null) {
                    throw objectReviewError("AI 初筛包含重复盒子编号，不能生成可靠人工真值");
                }
                Set<String> childKeys = new HashSet<>();
                JsonNode labels = tray.path("labels");
                if (labels.isArray()) {
                    int labelOrder = 0;
                    for (JsonNode ignored : labels) {
                        String labelKey = "label-" + trayIndex + "-" + labelOrder;
                        labelOwners.put(labelKey, trayIndex);
                        childKeys.add(labelKey);
                        labelOrder++;
                    }
                }
                labelsByTray.put(trayKey, childKeys);
                order++;
            }
            return new ScreeningObjectIndex(trayOwners, labelOwners, labelsByTray);
        } catch (JsonProcessingException ex) {
            throw objectReviewError("AI 初筛明细损坏，无法安全核对盒子归属");
        }
    }

    private Set<String> normalizedUniqueKeys(List<String> keys, String subject) {
        Set<String> normalized = new HashSet<>();
        for (String key : keys) {
            String value = normalizeKey(key);
            if (value == null || !normalized.add(value)) {
                throw objectReviewError(subject + "包含空值或重复项");
            }
        }
        return normalized;
    }

    private void validateDecision(
            LabelQcObjectDecision decision,
            String aiObjectKey,
            String subject) {
        if (decision == LabelQcObjectDecision.ADDED && aiObjectKey != null) {
            throw objectReviewError("人工新增" + subject + "不能引用 AI 原始框");
        }
        if (decision != LabelQcObjectDecision.ADDED && aiObjectKey == null) {
            throw objectReviewError(subject + "确认或修正必须保留 AI 原始框标识");
        }
    }

    private void validatePresence(LabelQcPresence presence, long objectCount, String labelName) {
        if (presence == LabelQcPresence.PRESENT && objectCount == 0) {
            throw objectReviewError(labelName + "选择“有”时必须保留或补画至少一个可见框");
        }
        if (presence == LabelQcPresence.MISSING && objectCount > 0) {
            throw objectReviewError(labelName + "选择“缺少”时不能同时保留可见框");
        }
    }

    private void validateBox(BoundingBox box, String subject) {
        if (box.xMin() >= box.xMax() || box.yMin() >= box.yMax()) {
            throw objectReviewError(subject + "框范围无效");
        }
    }

    private boolean boxContainsCenter(BoundingBox tray, BoundingBox label) {
        double centerX = (label.xMin() + label.xMax()) / 2.0;
        double centerY = (label.yMin() + label.yMax()) / 2.0;
        return centerX >= tray.xMin() && centerX <= tray.xMax()
                && centerY >= tray.yMin() && centerY <= tray.yMax();
    }

    private String normalizeKey(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private String writeObjectReview(ObjectReviewPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, "对象级人工真值保存失败")
                    .withCode("LABEL_QC_OBJECT_REVIEW_SERIALIZATION_FAILED")
                    .withHint("当前审核尚未提交，请保留页面并联系技术管理员");
        }
    }

    private ObjectReviewPayload readObjectReview(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, ObjectReviewPayload.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, "对象级人工真值读取失败")
                    .withCode("LABEL_QC_OBJECT_REVIEW_CORRUPT")
                    .withHint("请联系技术管理员核对该照片的审核快照");
        }
    }

    private BusinessException objectReviewError(String message) {
        return new BusinessException(message)
                .withCode("LABEL_QC_OBJECT_REVIEW_INVALID")
                .withHint("返回当前照片，逐盒确认所有 AI 框和标签存在性");
    }

    private record ScreeningObjectIndex(
            Map<String, Integer> trayOwners,
            Map<String, Integer> labelOwners,
            Map<String, Set<String>> labelsByTray) {}

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
                photo.getScreeningDetail(),
                readObjectReview(photo.getObjectReviewDetail()),
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
