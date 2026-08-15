package com.cretas.aims.dto.labelqc;

import com.cretas.aims.entity.enums.LabelQcAnnotationSource;
import com.cretas.aims.entity.enums.LabelQcLabel;
import com.cretas.aims.entity.enums.LabelQcObjectDecision;
import com.cretas.aims.entity.enums.LabelQcObjectType;
import com.cretas.aims.entity.enums.LabelQcPhotoStatus;
import com.cretas.aims.entity.enums.LabelQcPresence;
import com.cretas.aims.entity.enums.LabelQcTaskStatus;
import com.cretas.aims.entity.enums.LabelQcTrainingStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class LabelQcDtos {
    private LabelQcDtos() {}

    public record CreateTaskRequest(
            @NotBlank @Size(max = 100) String productTypeId,
            @NotBlank @Size(max = 100) String batchNumber,
            @NotNull LocalDate productionDate,
            @NotBlank @Size(max = 100) String idempotencyKey
    ) {}

    public record AddPhotoRequest(
            @NotBlank @Size(max = 191) String attachmentId,
            @NotNull @Min(0) @Max(5) Integer orderIndex,
            @NotNull @Min(1) @Max(20000) Integer imageWidth,
            @NotNull @Min(1) @Max(20000) Integer imageHeight
    ) {}

    public record BoundingBox(
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double xMin,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double yMin,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double xMax,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double yMax
    ) {}

    public record AnnotationReviewRequest(
            @Size(max = 36) String annotationId,
            @NotNull LabelQcLabel label,
            @Valid BoundingBox bbox,
            @Size(max = 500) String notes
    ) {}

    public record LabelObjectReview(
            @Size(max = 100) String aiObjectKey,
            @NotNull LabelQcObjectType type,
            @NotNull @Valid BoundingBox bbox,
            @NotNull LabelQcObjectDecision decision,
            @NotNull Boolean truncated
    ) {}

    public record TrayObjectReview(
            @NotNull @Min(0) @Max(999) Integer trayIndex,
            @Size(max = 100) String aiTrayKey,
            @NotNull @Valid BoundingBox bbox,
            @NotNull LabelQcObjectDecision decision,
            @NotNull LabelQcPresence whitePresence,
            @NotNull LabelQcPresence colorPresence,
            @NotNull @Size(max = 40) List<@Valid LabelObjectReview> labels,
            @NotNull @Size(max = 40)
            List<@NotBlank @Size(max = 100) String> rejectedAiObjectKeys
    ) {}

    /**
     * Version 1 is the first object-truth contract. Keeping an explicit version lets
     * training exporters reject a future incompatible shape instead of guessing.
     */
    public record ObjectReviewPayload(
            @NotNull @Min(1) @Max(1) Integer version,
            @NotNull Boolean complete,
            @NotNull @Size(max = 100) List<@Valid TrayObjectReview> trays,
            @NotNull @Size(max = 100)
            List<@NotBlank @Size(max = 100) String> rejectedAiTrayKeys
    ) {}

    public record PhotoReviewRequest(
            @NotBlank @Size(max = 36) String photoId,
            @NotEmpty @Size(max = 100) List<@Valid AnnotationReviewRequest> annotations,
            @Valid ObjectReviewPayload objectReview
    ) {
        public PhotoReviewRequest(String photoId, List<AnnotationReviewRequest> annotations) {
            this(photoId, annotations, null);
        }
    }

    public record ReviewTaskRequest(
            @Min(0) Long expectedVersion,
            @Size(max = 100) String reviewRequestId,
            @NotEmpty @Size(max = 6) List<@Valid PhotoReviewRequest> photos
    ) {}

    public record TrainingDecisionRequest(
            @NotNull Boolean approved,
            @Min(0) Long expectedVersion,
            @Size(max = 500) String notes
    ) {}

    public record AnnotationResponse(
            String id,
            LabelQcAnnotationSource source,
            String aiCandidateId,
            LabelQcLabel aiLabel,
            Double aiConfidence,
            String aiEvidence,
            LabelQcLabel humanLabel,
            BoundingBox bbox,
            String reviewerNotes
    ) {}

    public record PhotoResponse(
            String id,
            String attachmentId,
            Integer orderIndex,
            Integer imageWidth,
            Integer imageHeight,
            LabelQcPhotoStatus status,
            String imageUrl,
            String aiModel,
            String promptVersion,
            String analysisError,
            /** AI 初筛明细原文 JSON；复核台据此渲染盒子/白标/彩标三层框。可为 null。 */
            String screeningDetail,
            /** 人工最终的每盒对象真值；旧任务或旧客户端审核可为 null。 */
            ObjectReviewPayload objectReview,
            List<AnnotationResponse> annotations
    ) {}

    public record TaskSummaryResponse(
            String id,
            String productTypeId,
            String skuCode,
            String skuName,
            String batchNumber,
            LocalDate productionDate,
            Long createdBy,
            LabelQcTaskStatus status,
            Long version,
            Integer photoCount,
            Integer aiCandidateCount,
            Integer finalDefectCount,
            Long reviewedBy,
            LocalDateTime reviewedAt,
            Boolean archived,
            Long archivedBy,
            LocalDateTime archivedAt,
            LabelQcTrainingStatus trainingStatus,
            Long trainingDecidedBy,
            LocalDateTime trainingDecidedAt,
            String trainingDecisionNotes,
            Long backupExportedBy,
            LocalDateTime backupExportedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    public record TaskDetailResponse(
            TaskSummaryResponse task,
            List<PhotoResponse> photos
    ) {}

    public record StatusCountsResponse(Map<LabelQcTaskStatus, Long> counts) {}

    public record TaskBackupResponse(
            TaskDetailResponse data,
            LocalDateTime exportedAt,
            Long exportedBy
    ) {}

    public record TrainingPhoto(
            String taskId,
            String photoId,
            String imageUrl,
            Integer imageWidth,
            Integer imageHeight,
            String skuCode,
            String skuName,
            String batchNumber,
            LocalDate productionDate,
            LocalDateTime reviewedAt,
            ObjectReviewPayload objectReview,
            List<AnnotationResponse> finalAnnotations
    ) {}
}
