package com.cretas.aims.service;

import com.cretas.aims.entity.LabelQcPhoto;
import com.cretas.aims.entity.LabelQcTask;
import com.cretas.aims.entity.enums.LabelQcAnnotationSource;
import com.cretas.aims.entity.enums.LabelQcPhotoStatus;
import com.cretas.aims.entity.enums.LabelQcTaskStatus;
import com.cretas.aims.event.LabelQcAnalysisRequestedEvent;
import com.cretas.aims.repository.LabelQcAnnotationRepository;
import com.cretas.aims.repository.LabelQcPhotoRepository;
import com.cretas.aims.repository.LabelQcTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Re-dispatches persisted work after process restarts and resets genuinely
 * stale in-flight tasks. The worker obtains a pessimistic task lock, so
 * duplicate dispatches from multiple application instances are harmless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LabelQcAnalysisRecoveryJob {

    private static final int STALE_AFTER_MINUTES = 45;

    private final LabelQcTaskRepository taskRepository;
    private final LabelQcPhotoRepository photoRepository;
    private final LabelQcAnnotationRepository annotationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(
            initialDelayString = "${cretas.label-qc.recovery-initial-delay-ms:30000}",
            fixedDelayString = "${cretas.label-qc.recovery-delay-ms:60000}")
    @Transactional
    public void recover() {
        Set<String> taskIds = new LinkedHashSet<>();

        List<LabelQcTask> stale = taskRepository
                .findTop20ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        LabelQcTaskStatus.ANALYZING,
                        LocalDateTime.now().minusMinutes(STALE_AFTER_MINUTES));
        for (LabelQcTask task : stale) {
            resetStaleTask(task);
            taskIds.add(task.getId());
        }

        taskRepository.findTop20ByStatusOrderByCreatedAtAsc(LabelQcTaskStatus.QUEUED)
                .stream()
                .map(LabelQcTask::getId)
                .forEach(taskIds::add);

        taskIds.forEach(taskId ->
                eventPublisher.publishEvent(new LabelQcAnalysisRequestedEvent(taskId)));
        if (!taskIds.isEmpty()) {
            log.info("Label QC recovery dispatched {} persisted task(s)", taskIds.size());
        }
    }

    private void resetStaleTask(LabelQcTask task) {
        String factoryId = task.getFactoryId();
        annotationRepository.deleteByFactoryIdAndTaskIdAndSource(
                factoryId,
                task.getId(),
                LabelQcAnnotationSource.AI);
        List<LabelQcPhoto> photos =
                photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(
                        factoryId,
                        task.getId());
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
        log.warn("Recovered stale Label QC analysis task {}", task.getId());
    }
}
