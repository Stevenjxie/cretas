package com.cretas.aims.service;

import com.cretas.aims.dto.labelqc.LabelQcDtos.AnnotationReviewRequest;
import com.cretas.aims.dto.labelqc.LabelQcDtos.AddPhotoRequest;
import com.cretas.aims.dto.labelqc.LabelQcDtos.BoundingBox;
import com.cretas.aims.dto.labelqc.LabelQcDtos.PhotoReviewRequest;
import com.cretas.aims.dto.labelqc.LabelQcDtos.ReviewTaskRequest;
import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.LabelQcAnnotation;
import com.cretas.aims.entity.LabelQcPhoto;
import com.cretas.aims.entity.LabelQcTask;
import com.cretas.aims.entity.enums.LabelQcAnnotationSource;
import com.cretas.aims.entity.enums.LabelQcLabel;
import com.cretas.aims.entity.enums.LabelQcPhotoStatus;
import com.cretas.aims.entity.enums.LabelQcTaskStatus;
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

        when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        lenient().when(photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(FACTORY_ID, TASK_ID))
                .thenReturn(List.of(photo));
        lenient().when(annotationRepository.findByFactoryIdAndTaskIdOrderByCreatedAtAsc(FACTORY_ID, TASK_ID))
                .thenReturn(List.of(aiCandidate));
        lenient().when(attachmentService.generateDownloadUrl(FACTORY_ID, "attachment-1"))
                .thenReturn("https://example.invalid/signed");
    }

    @Test
    void reviewPreservesAiOriginAndPersistsHumanTruth() {
        BoundingBox originalBox = new BoundingBox(0.1, 0.6, 0.4, 0.9);
        BoundingBox missedBox = new BoundingBox(0.55, 0.7, 0.9, 0.98);
        ReviewTaskRequest request = new ReviewTaskRequest(List.of(
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

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<LabelQcAnnotation>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(annotationRepository, times(2)).saveAll(captor.capture());
        List<LabelQcAnnotation> additions = (List<LabelQcAnnotation>) captor.getAllValues().get(1);
        assertEquals(1, additions.size());
        assertEquals(LabelQcAnnotationSource.HUMAN, additions.get(0).getSource());
        assertEquals(LabelQcLabel.MISSING_COLOR_LABEL, additions.get(0).getHumanLabel());
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
                        new ReviewTaskRequest(List.of(
                                new PhotoReviewRequest(PHOTO_ID, List.of(first, duplicate))))));

        assertEquals("LABEL_QC_ANNOTATION_DUPLICATE", error.getErrorCode());
        verify(annotationRepository, never()).saveAll(any());
        verify(photoRepository, never()).saveAll(any());
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
