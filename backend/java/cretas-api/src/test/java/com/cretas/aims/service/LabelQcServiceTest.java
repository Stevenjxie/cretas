package com.cretas.aims.service;

import com.cretas.aims.dto.labelqc.LabelQcDtos.*;
import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.LabelQcAnnotation;
import com.cretas.aims.entity.LabelQcPhoto;
import com.cretas.aims.entity.LabelQcTask;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.LabelQcAnnotationSource;
import com.cretas.aims.entity.enums.LabelQcLabel;
import com.cretas.aims.entity.enums.LabelQcPhotoStatus;
import com.cretas.aims.entity.enums.LabelQcTaskStatus;
import com.cretas.aims.entity.enums.LabelQcTrainingStatus;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.LabelQcAnnotationRepository;
import com.cretas.aims.repository.LabelQcPhotoRepository;
import com.cretas.aims.repository.LabelQcTaskRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.attachment.AttachmentService;
import com.cretas.aims.event.LabelQcAnalysisRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LabelQcServiceTest {

    private static final String FACTORY_ID = "F006";
    private static final String TASK_ID = "task-1";
    private static final String PHOTO_ID = "photo-1";
    private static final long REVIEWER_ID = 99L;

    @Mock private LabelQcTaskRepository taskRepository;
    @Mock private LabelQcPhotoRepository photoRepository;
    @Mock private LabelQcAnnotationRepository annotationRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private LabelQcService service;
    private LabelQcTask task;
    private LabelQcPhoto photo;
    private LabelQcAnnotation aiCandidate;

    @BeforeEach
    void setUp() {
        service = new LabelQcService(
                taskRepository,
                photoRepository,
                annotationRepository,
                productTypeRepository,
                attachmentService,
                eventPublisher);
        task = LabelQcTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY_ID)
                .productTypeId("sku-id")
                .skuCode("SKU-001")
                .skuName("测试牛肉")
                .batchNumber("BATCH-001")
                .productionDate(LocalDate.now())
                .createdBy(7L)
                .idempotencyKey("idem-1")
                .status(LabelQcTaskStatus.NEEDS_REVIEW)
                .photoCount(1)
                .aiCandidateCount(1)
                .finalDefectCount(0)
                .archived(false)
                .trainingStatus(LabelQcTrainingStatus.PENDING)
                .version(0L)
                .build();
        photo = LabelQcPhoto.builder()
                .id(PHOTO_ID)
                .factoryId(FACTORY_ID)
                .taskId(TASK_ID)
                .attachmentId("attachment-1")
                .orderIndex(0)
                .imageWidth(1000)
                .imageHeight(1600)
                .status(LabelQcPhotoStatus.ANALYZED)
                .build();
        aiCandidate = LabelQcAnnotation.builder()
                .id("ai-annotation-1")
                .factoryId(FACTORY_ID)
                .taskId(TASK_ID)
                .photoId(PHOTO_ID)
                .source(LabelQcAnnotationSource.AI)
                .aiCandidateId("ai-1")
                .aiLabel(LabelQcLabel.MISSING_WHITE_LABEL)
                .aiConfidence(0.81)
                .aiEvidence("左下角疑似缺少白标")
                .xMin(0.1)
                .yMin(0.6)
                .xMax(0.4)
                .yMax(0.9)
                .build();

        lenient().when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        lenient().when(taskRepository.findByFactoryIdAndIdForUpdate(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        lenient().when(photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(FACTORY_ID, TASK_ID))
                .thenReturn(List.of(photo));
        lenient().when(annotationRepository.findByFactoryIdAndTaskIdOrderByCreatedAtAsc(FACTORY_ID, TASK_ID))
                .thenReturn(List.of(aiCandidate));
        lenient().when(attachmentService.generateDownloadUrl(FACTORY_ID, "attachment-1"))
                .thenReturn("https://example.invalid/signed");
    }

    @Test
    void createTaskAcceptsOnlyActiveFinishedProduct() {
        ProductType product = product("finished-id", ProductCategory.FINISHED_PRODUCT);
        when(taskRepository.findByFactoryIdAndCreatedByAndIdempotencyKey(
                FACTORY_ID, 7L, "create-finished")).thenReturn(Optional.empty());
        when(productTypeRepository.findByIdAndFactoryId("finished-id", FACTORY_ID))
                .thenReturn(Optional.of(product));

        var result = service.createTask(
                FACTORY_ID,
                7L,
                new CreateTaskRequest(
                        "finished-id",
                        "BATCH-FINISHED",
                        LocalDate.now(),
                        "create-finished"));

        assertEquals("finished-id", result.task().productTypeId());
        assertEquals("CP-FINISHED", result.task().skuCode());
        assertEquals("成品牛肉", result.task().skuName());
        ArgumentCaptor<LabelQcTask> saved = ArgumentCaptor.forClass(LabelQcTask.class);
        verify(taskRepository).save(saved.capture());
        assertEquals(LabelQcTaskStatus.DRAFT, saved.getValue().getStatus());
    }

    @Test
    void createTaskRejectsActiveSemiFinishedProductBeforeWriting() {
        ProductType product = product("semi-id", ProductCategory.SEMI_FINISHED);
        when(taskRepository.findByFactoryIdAndCreatedByAndIdempotencyKey(
                FACTORY_ID, 7L, "create-semi")).thenReturn(Optional.empty());
        when(productTypeRepository.findByIdAndFactoryId("semi-id", FACTORY_ID))
                .thenReturn(Optional.of(product));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.createTask(
                        FACTORY_ID,
                        7L,
                        new CreateTaskRequest(
                                "semi-id",
                                "BATCH-SEMI",
                                LocalDate.now(),
                                "create-semi")));

        assertEquals("LABEL_QC_FINISHED_SKU_REQUIRED", error.getErrorCode());
        assertEquals("请重新选择当前工厂已启用的成品 SKU", error.getActionHint());
        verify(taskRepository, never()).save(any(LabelQcTask.class));
    }

    private ProductType product(String id, String productCategory) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId(FACTORY_ID);
        product.setCode("finished-id".equals(id) ? "CP-FINISHED" : "PT-SEMI");
        product.setName("finished-id".equals(id) ? "成品牛肉" : "滚揉半成品");
        product.setUnit("盒");
        product.setIsActive(true);
        product.setProductCategory(productCategory);
        return product;
    }

    @Test
    void reviewPreservesAiOriginAndPersistsHumanTruth() {
        BoundingBox originalBox = new BoundingBox(0.1, 0.6, 0.4, 0.9);
        BoundingBox missedBox = new BoundingBox(0.55, 0.7, 0.9, 0.98);
        ReviewTaskRequest request = new ReviewTaskRequest(0L, "review-device-a", List.of(
                new PhotoReviewRequest(PHOTO_ID, List.of(
                        new AnnotationReviewRequest(
                                aiCandidate.getId(),
                                LabelQcLabel.MISSING_WHITE_LABEL,
                                originalBox,
                                "人工确认"),
                        new AnnotationReviewRequest(
                                null,
                                LabelQcLabel.MISSING_COLOR_LABEL,
                                missedBox,
                                "人工补框")))));

        service.review(FACTORY_ID, TASK_ID, REVIEWER_ID, request);

        assertEquals(LabelQcLabel.MISSING_WHITE_LABEL, aiCandidate.getAiLabel());
        assertEquals(LabelQcLabel.MISSING_WHITE_LABEL, aiCandidate.getHumanLabel());
        assertEquals(REVIEWER_ID, aiCandidate.getReviewedBy());
        assertEquals(LabelQcTaskStatus.REVIEWED, task.getStatus());
        assertEquals(2, task.getFinalDefectCount());
        assertEquals(LabelQcPhotoStatus.REVIEWED, photo.getStatus());
        assertEquals("review-device-a", task.getReviewRequestId());
        assertEquals(LabelQcTrainingStatus.PENDING, task.getTrainingStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<LabelQcAnnotation>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(annotationRepository, times(2)).saveAll(captor.capture());
        List<LabelQcAnnotation> additions = (List<LabelQcAnnotation>) captor.getAllValues().get(1);
        assertEquals(1, additions.size());
        assertEquals(LabelQcAnnotationSource.HUMAN, additions.get(0).getSource());
        assertEquals(LabelQcLabel.MISSING_COLOR_LABEL, additions.get(0).getHumanLabel());
    }

    @Test
    void reviewedTaskCanBeArchivedRestoredAndBackedUpWithoutDeletion() {
        task.setStatus(LabelQcTaskStatus.REVIEWED);

        service.archive(FACTORY_ID, TASK_ID, REVIEWER_ID);
        assertTrue(task.getArchived());
        assertEquals(REVIEWER_ID, task.getArchivedBy());
        assertNotNull(task.getArchivedAt());
        verify(taskRepository, never()).delete(any(LabelQcTask.class));

        service.restore(FACTORY_ID, TASK_ID, REVIEWER_ID);
        assertFalse(task.getArchived());
        assertNull(task.getArchivedBy());
        assertNull(task.getArchivedAt());

        var backup = service.exportBackup(FACTORY_ID, TASK_ID, REVIEWER_ID);
        assertEquals(REVIEWER_ID, backup.exportedBy());
        assertEquals(TASK_ID, backup.data().task().id());
        assertNotNull(task.getBackupExportedAt());
    }

    @Test
    void technicalAdminDecisionIsExplicitAndVersionGuarded() {
        task.setStatus(LabelQcTaskStatus.REVIEWED);

        var approved = service.decideTraining(
                FACTORY_ID,
                TASK_ID,
                REVIEWER_ID,
                new TrainingDecisionRequest(true, 0L, "标注已复核"));

        assertEquals(LabelQcTrainingStatus.APPROVED, task.getTrainingStatus());
        assertEquals(REVIEWER_ID, task.getTrainingDecidedBy());
        assertEquals("标注已复核", task.getTrainingDecisionNotes());
        assertEquals(LabelQcTrainingStatus.APPROVED, approved.task().trainingStatus());

        BusinessException stale = assertThrows(
                BusinessException.class,
                () -> service.decideTraining(
                        FACTORY_ID,
                        TASK_ID,
                        REVIEWER_ID,
                        new TrainingDecisionRequest(false, 9L, "旧页面")));
        assertEquals("LABEL_QC_TRAINING_DECISION_STALE", stale.getErrorCode());
    }

    @Test
    void reviewRejectsDuplicateAiCandidateReference() {
        BoundingBox box = new BoundingBox(0.1, 0.6, 0.4, 0.9);
        AnnotationReviewRequest first = new AnnotationReviewRequest(
                aiCandidate.getId(), LabelQcLabel.MISSING_WHITE_LABEL, box, null);
        AnnotationReviewRequest duplicate = new AnnotationReviewRequest(
                aiCandidate.getId(), LabelQcLabel.NO_DEFECT, box, null);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.review(
                        FACTORY_ID,
                        TASK_ID,
                        REVIEWER_ID,
                        new ReviewTaskRequest(0L, "review-device-a", List.of(
                                new PhotoReviewRequest(PHOTO_ID, List.of(first, duplicate))))));

        assertEquals("LABEL_QC_ANNOTATION_DUPLICATE", error.getErrorCode());
        verify(annotationRepository, never()).saveAll(any());
        verify(photoRepository, never()).saveAll(any());
    }

    @Test
    void legacyReviewPayloadGetsStableServerFingerprint() {
        ReviewTaskRequest request = new ReviewTaskRequest(null, null, List.of(
                new PhotoReviewRequest(PHOTO_ID, List.of(
                        new AnnotationReviewRequest(
                                aiCandidate.getId(),
                                LabelQcLabel.MISSING_WHITE_LABEL,
                                new BoundingBox(0.1, 0.6, 0.4, 0.9),
                                null)))));

        service.review(FACTORY_ID, TASK_ID, REVIEWER_ID, request);

        assertNotNull(task.getReviewRequestId());
        assertTrue(task.getReviewRequestId().startsWith("legacy-"));
        assertEquals(LabelQcTaskStatus.REVIEWED, task.getStatus());
    }

    @Test
    void repeatedReviewRequestIsIdempotent() {
        task.setStatus(LabelQcTaskStatus.REVIEWED);
        task.setReviewRequestId("review-device-a");

        service.review(
                FACTORY_ID,
                TASK_ID,
                REVIEWER_ID,
                new ReviewTaskRequest(0L, "review-device-a", List.of(
                        new PhotoReviewRequest(PHOTO_ID, List.of(
                                new AnnotationReviewRequest(
                                        aiCandidate.getId(),
                                        LabelQcLabel.MISSING_WHITE_LABEL,
                                        new BoundingBox(0.1, 0.6, 0.4, 0.9),
                                        null))))));

        verify(annotationRepository, never()).saveAll(any());
        verify(photoRepository, never()).saveAll(any());
        verify(taskRepository, never()).save(any(LabelQcTask.class));
    }

    @Test
    void reviewRejectsAnotherDeviceAfterTaskWasCompleted() {
        task.setStatus(LabelQcTaskStatus.REVIEWED);
        task.setReviewRequestId("review-device-a");

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.review(
                        FACTORY_ID,
                        TASK_ID,
                        REVIEWER_ID,
                        new ReviewTaskRequest(0L, "review-device-b", List.of(
                                new PhotoReviewRequest(PHOTO_ID, List.of(
                                        new AnnotationReviewRequest(
                                                aiCandidate.getId(),
                                                LabelQcLabel.MISSING_WHITE_LABEL,
                                                new BoundingBox(0.1, 0.6, 0.4, 0.9),
                                                null)))))));

        assertEquals("LABEL_QC_ALREADY_REVIEWED", error.getErrorCode());
        verify(annotationRepository, never()).saveAll(any());
        verify(photoRepository, never()).saveAll(any());
        verify(taskRepository, never()).save(any(LabelQcTask.class));
    }

    @Test
    void reviewRejectsStaleTaskVersionBeforeWritingAnnotations() {
        task.setVersion(2L);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.review(
                        FACTORY_ID,
                        TASK_ID,
                        REVIEWER_ID,
                        new ReviewTaskRequest(1L, "review-device-b", List.of(
                                new PhotoReviewRequest(PHOTO_ID, List.of(
                                        new AnnotationReviewRequest(
                                                aiCandidate.getId(),
                                                LabelQcLabel.MISSING_WHITE_LABEL,
                                                new BoundingBox(0.1, 0.6, 0.4, 0.9),
                                                null)))))));

        assertEquals("LABEL_QC_REVIEW_STALE", error.getErrorCode());
        verify(annotationRepository, never()).saveAll(any());
        verify(photoRepository, never()).saveAll(any());
        verify(taskRepository, never()).save(any(LabelQcTask.class));
    }

    @Test
    void submitQueuesTaskAndPhotosBeforePublishingAnalysisRequest() {
        task.setStatus(LabelQcTaskStatus.UPLOADING);
        photo.setStatus(LabelQcPhotoStatus.UPLOADED);

        service.submit(FACTORY_ID, TASK_ID);

        assertEquals(LabelQcTaskStatus.QUEUED, task.getStatus());
        assertEquals(LabelQcPhotoStatus.QUEUED, photo.getStatus());
        verify(photoRepository).saveAll(List.of(photo));
        verify(taskRepository).save(task);

        ArgumentCaptor<LabelQcAnalysisRequestedEvent> event =
                ArgumentCaptor.forClass(LabelQcAnalysisRequestedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(TASK_ID, event.getValue().taskId());
    }

    @Test
    void addPhotoRejectsAttachmentBoundToAnotherTask() {
        task.setStatus(LabelQcTaskStatus.DRAFT);
        task.setPhotoCount(0);
        Attachment attachment = Attachment.builder()
                .id("attachment-2")
                .factoryId(FACTORY_ID)
                .entityType(Attachment.EntityType.QUALITY_CHECK)
                .entityId("another-task")
                .fileName("qc.jpg")
                .fileUrl("oss://bucket/qc.jpg")
                .fileSize(1024L)
                .fileType("image/jpeg")
                .uploadedBy(7L)
                .build();
        when(photoRepository.findByFactoryIdAndTaskIdAndAttachmentId(
                FACTORY_ID, TASK_ID, attachment.getId())).thenReturn(Optional.empty());
        when(photoRepository.existsByFactoryIdAndTaskIdAndOrderIndex(
                FACTORY_ID, TASK_ID, 0)).thenReturn(false);
        when(attachmentService.getById(FACTORY_ID, attachment.getId()))
                .thenReturn(attachment);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.addPhoto(
                        FACTORY_ID,
                        TASK_ID,
                        new AddPhotoRequest(attachment.getId(), 0, 1200, 1800)));

        assertEquals("LABEL_QC_ATTACHMENT_MISMATCH", error.getErrorCode());
        verify(photoRepository, never()).save(any(LabelQcPhoto.class));
        verify(taskRepository, never()).save(any(LabelQcTask.class));
    }
}
