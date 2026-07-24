package com.cretas.aims.service;

import com.cretas.aims.client.LabelQcAnalysisClient.AnalysisResult;
import com.cretas.aims.client.LabelQcAnalysisClient.BoundingBox;
import com.cretas.aims.client.LabelQcAnalysisClient.Candidate;
import com.cretas.aims.entity.LabelQcAnnotation;
import com.cretas.aims.entity.LabelQcPhoto;
import com.cretas.aims.entity.LabelQcTask;
import com.cretas.aims.entity.enums.LabelQcAnnotationSource;
import com.cretas.aims.entity.enums.LabelQcLabel;
import com.cretas.aims.entity.enums.LabelQcPhotoStatus;
import com.cretas.aims.entity.enums.LabelQcTaskStatus;
import com.cretas.aims.repository.LabelQcAnnotationRepository;
import com.cretas.aims.repository.LabelQcPhotoRepository;
import com.cretas.aims.repository.LabelQcTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabelQcAnalysisWorkerTest {

    private static final String FACTORY_ID = "F006";
    private static final String TASK_ID = "task-1";
    private static final String PHOTO_ID = "photo-1";

    @Mock private LabelQcTaskRepository taskRepository;
    @Mock private LabelQcPhotoRepository photoRepository;
    @Mock private LabelQcAnnotationRepository annotationRepository;

    private LabelQcAnalysisWorker worker;
    private LabelQcTask task;
    private LabelQcPhoto photo;

    @BeforeEach
    void setUp() {
        worker = new LabelQcAnalysisWorker(
                taskRepository,
                photoRepository,
                annotationRepository);
        task = LabelQcTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY_ID)
                .status(LabelQcTaskStatus.QUEUED)
                .aiCandidateCount(0)
                .build();
        photo = LabelQcPhoto.builder()
                .id(PHOTO_ID)
                .factoryId(FACTORY_ID)
                .taskId(TASK_ID)
                .attachmentId("attachment-1")
                .orderIndex(0)
                .status(LabelQcPhotoStatus.QUEUED)
                .build();
    }

    @Test
    void startMovesQueuedBatchIntoAnalyzing() {
        when(taskRepository.findFirstById(TASK_ID)).thenReturn(Optional.of(task));
        when(photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(
                FACTORY_ID, TASK_ID)).thenReturn(List.of(photo));

        LabelQcAnalysisWorker.AnalysisBatch batch = worker.start(TASK_ID);

        assertNotNull(batch);
        assertEquals(LabelQcTaskStatus.ANALYZING, task.getStatus());
        assertEquals(LabelQcPhotoStatus.ANALYZING, photo.getStatus());
        assertEquals(PHOTO_ID, batch.photos().get(0).photoId());
        verify(taskRepository).save(task);
        verify(photoRepository).saveAll(List.of(photo));
    }

    @Test
    void successfulCandidateMovesTaskToHumanReview() {
        when(photoRepository.findByFactoryIdAndId(FACTORY_ID, PHOTO_ID))
                .thenReturn(Optional.of(photo));
        AnalysisResult result = new AnalysisResult(
                "SUSPECTED",
                "vision-test",
                "label-qc-v2",
                List.of(new Candidate(
                        "candidate-1",
                        LabelQcLabel.MISSING_WHITE_LABEL,
                        0.78,
                        new BoundingBox(0.1, 0.5, 0.4, 0.9),
                        "tray edge lacks white label")));

        worker.saveSuccess(FACTORY_ID, TASK_ID, PHOTO_ID, result);

        assertEquals(LabelQcPhotoStatus.ANALYZED, photo.getStatus());
        assertEquals("vision-test", photo.getAiModel());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<LabelQcAnnotation>> annotations =
                ArgumentCaptor.forClass(Iterable.class);
        verify(annotationRepository).saveAll(annotations.capture());
        LabelQcAnnotation candidate =
                ((List<LabelQcAnnotation>) annotations.getValue()).get(0);
        assertEquals(LabelQcAnnotationSource.AI, candidate.getSource());
        assertEquals(LabelQcLabel.MISSING_WHITE_LABEL, candidate.getAiLabel());

        when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        when(annotationRepository.countByFactoryIdAndTaskIdAndSource(
                FACTORY_ID, TASK_ID, LabelQcAnnotationSource.AI)).thenReturn(1L);
        when(photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(
                FACTORY_ID, TASK_ID)).thenReturn(List.of(photo));

        worker.finish(FACTORY_ID, TASK_ID);

        assertEquals(LabelQcTaskStatus.NEEDS_REVIEW, task.getStatus());
        assertEquals(1, task.getAiCandidateCount());
    }

    @Test
    void totalAnalysisFailureStillCreatesHumanReviewFallback() {
        when(photoRepository.findByFactoryIdAndId(FACTORY_ID, PHOTO_ID))
                .thenReturn(Optional.of(photo));

        worker.saveFailure(
                FACTORY_ID,
                TASK_ID,
                PHOTO_ID,
                "vision service timeout");

        assertEquals(LabelQcPhotoStatus.ANALYSIS_FAILED, photo.getStatus());
        assertEquals("vision service timeout", photo.getAnalysisError());
        ArgumentCaptor<LabelQcAnnotation> fallback =
                ArgumentCaptor.forClass(LabelQcAnnotation.class);
        verify(annotationRepository).save(fallback.capture());
        assertEquals(LabelQcLabel.UNJUDGEABLE, fallback.getValue().getAiLabel());
        assertEquals(0.0, fallback.getValue().getXMin());
        assertEquals(1.0, fallback.getValue().getXMax());

        when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        when(annotationRepository.countByFactoryIdAndTaskIdAndSource(
                FACTORY_ID, TASK_ID, LabelQcAnnotationSource.AI)).thenReturn(1L);
        when(photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(
                FACTORY_ID, TASK_ID)).thenReturn(List.of(photo));

        worker.finish(FACTORY_ID, TASK_ID);

        assertEquals(LabelQcTaskStatus.ANALYSIS_FAILED, task.getStatus());
        assertEquals(1, task.getAiCandidateCount());
    }
}
