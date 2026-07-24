package com.cretas.aims.dto.labelqc;

import com.cretas.aims.entity.enums.LabelQcAnnotationSource;
import com.cretas.aims.entity.enums.LabelQcLabel;
import com.cretas.aims.entity.enums.LabelQcPhotoStatus;
import com.cretas.aims.entity.enums.LabelQcTaskStatus;
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

    public record PhotoReviewRequest(
            @NotBlank @Size(max = 36) String photoId,
            @NotEmpty @Size(max = 100) List<@Valid AnnotationReviewRequest> annotations
    ) {}

    public record ReviewTaskRequest(
            @NotEmpty @Size(max = 6) List<@Valid PhotoReviewRequest> photos
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
            Integer photoCount,
            Integer aiCandidateCount,
            Integer finalDefectCount,
            Long reviewedBy,
            LocalDateTime reviewedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    public record TaskDetailResponse(
            TaskSummaryResponse task,
            List<PhotoResponse> photos
    ) {}

    public record StatusCountsResponse(Map<LabelQcTaskStatus, Long> counts) {}

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
            List<AnnotationResponse> finalAnnotations
    ) {}
}
