package com.cretas.aims.repository;

import com.cretas.aims.entity.LabelQcAnnotation;
import com.cretas.aims.entity.LabelQcPhoto;
import com.cretas.aims.entity.LabelQcTask;
import com.cretas.aims.entity.enums.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class LabelQcRepositoryQueryValidationTest {

    @Autowired TestEntityManager entityManager;
    @Autowired LabelQcTaskRepository taskRepository;
    @Autowired LabelQcPhotoRepository photoRepository;
    @Autowired LabelQcAnnotationRepository annotationRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void repositoriesBootAndQueriesKeepTenantIsolation() {
        LabelQcTask taskA = persistTask("F-LABEL-A", 11L, "idem-a");
        LabelQcTask taskB = persistTask("F-LABEL-B", 11L, "idem-b");
        taskA.setTrainingStatus(LabelQcTrainingStatus.APPROVED);
        LabelQcPhoto photoA = persistPhoto(taskA, "attachment-a");
        persistAnnotation(taskA, photoA);
        entityManager.flush();
        entityManager.clear();

        assertThat(taskRepository.findByFactoryIdAndId("F-LABEL-A", taskA.getId())).isPresent();
        assertThat(taskRepository.findByFactoryIdAndId("F-LABEL-B", taskA.getId())).isEmpty();
        assertThat(taskRepository.findByFactoryIdAndIdForUpdate("F-LABEL-A", taskA.getId()))
                .isPresent();
        assertThat(taskRepository.findByFactoryIdAndIdForUpdate("F-LABEL-B", taskA.getId()))
                .isEmpty();
        assertThat(taskRepository.findByFactoryIdAndCreatedByAndIdempotencyKey(
                "F-LABEL-A", 11L, "idem-a")).isPresent();
        assertThat(taskRepository.findFirstById(taskA.getId())).isPresent();
        Page<LabelQcTask> matchingTasks =
                taskRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                        "F-LABEL-A",
                        List.of(LabelQcTaskStatus.NEEDS_REVIEW),
                        PageRequest.of(0, 10));
        assertThat(matchingTasks.getTotalElements()).isEqualTo(1L);
        assertThat(taskRepository.findByFactoryIdAndArchivedAndStatusInOrderByCreatedAtDesc(
                "F-LABEL-A",
                false,
                List.of(LabelQcTaskStatus.NEEDS_REVIEW),
                PageRequest.of(0, 10))).hasSize(1);
        assertThat(taskRepository.findByFactoryIdAndArchivedOrderByCreatedAtDesc(
                "F-LABEL-A", false, PageRequest.of(0, 10))).hasSize(1);
        assertThat(taskRepository.countByFactoryIdAndArchivedAndStatus(
                "F-LABEL-A", false, LabelQcTaskStatus.NEEDS_REVIEW)).isEqualTo(1);
        assertThat(photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(
                "F-LABEL-A", taskA.getId())).hasSize(1);
        assertThat(photoRepository.findByFactoryIdAndTaskIdOrderByOrderIndexAsc(
                "F-LABEL-B", taskA.getId())).isEmpty();
        assertThat(annotationRepository.countByFactoryIdAndTaskIdAndSource(
                "F-LABEL-A", taskA.getId(), LabelQcAnnotationSource.AI)).isEqualTo(1);
        assertThat(annotationRepository.countByFactoryIdAndTaskIdAndSource(
                "F-LABEL-B", taskA.getId(), LabelQcAnnotationSource.AI)).isZero();
        assertThat(taskRepository.findByFactoryIdAndStatusAndReviewedAtBetweenOrderByReviewedAtAsc(
                "F-LABEL-A",
                LabelQcTaskStatus.NEEDS_REVIEW,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1),
                PageRequest.of(0, 10))).hasSize(1);
        assertThat(taskRepository
                .findByFactoryIdAndStatusAndTrainingStatusAndReviewedAtBetweenOrderByReviewedAtAsc(
                        "F-LABEL-A",
                        LabelQcTaskStatus.NEEDS_REVIEW,
                        LabelQcTrainingStatus.APPROVED,
                        LocalDateTime.now().minusDays(1),
                        LocalDateTime.now().plusDays(1),
                        PageRequest.of(0, 10))).hasSize(1);
        assertThat(taskRepository.findTop20ByStatusOrderByCreatedAtAsc(
                LabelQcTaskStatus.NEEDS_REVIEW)).hasSize(2);
        assertThat(taskRepository.findTop20ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                LabelQcTaskStatus.NEEDS_REVIEW,
                LocalDateTime.now().plusMinutes(1))).hasSize(2);
        assertThat(taskB.getFactoryId()).isEqualTo("F-LABEL-B");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reviewLockSerializesTwoDevicesAndExposesFirstCommitToSecond() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String taskId = transaction.execute(status -> {
            LabelQcTask task = LabelQcTask.builder()
                    .factoryId("F-LABEL-LOCK")
                    .productTypeId("sku")
                    .skuCode("SKU-LOCK")
                    .skuName("并发审核测试")
                    .batchNumber("BATCH-LOCK")
                    .productionDate(LocalDate.now())
                    .createdBy(21L)
                    .idempotencyKey("idem-lock")
                    .status(LabelQcTaskStatus.NEEDS_REVIEW)
                    .photoCount(1)
                    .aiCandidateCount(1)
                    .finalDefectCount(0)
                    .build();
            return taskRepository.saveAndFlush(task).getId();
        });
        assertThat(taskId).isNotBlank();

        CountDownLatch firstDeviceLocked = new CountDownLatch(1);
        CountDownLatch allowFirstDeviceCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstDevice = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        LabelQcTask task = taskRepository
                                .findByFactoryIdAndIdForUpdate("F-LABEL-LOCK", taskId)
                                .orElseThrow();
                        firstDeviceLocked.countDown();
                        try {
                            if (!allowFirstDeviceCommit.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("等待第一台设备提交超时");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        task.setStatus(LabelQcTaskStatus.REVIEWED);
                        task.setReviewRequestId("review-device-a");
                        taskRepository.saveAndFlush(task);
                    }));

            assertThat(firstDeviceLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> secondDevice = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        LabelQcTask task = taskRepository
                                .findByFactoryIdAndIdForUpdate("F-LABEL-LOCK", taskId)
                                .orElseThrow();
                        return task.getStatus() + ":" + task.getReviewRequestId();
                    }));

            assertThatThrownBy(() -> secondDevice.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowFirstDeviceCommit.countDown();
            firstDevice.get(5, TimeUnit.SECONDS);
            assertThat(secondDevice.get(5, TimeUnit.SECONDS))
                    .isEqualTo("REVIEWED:review-device-a");
        } finally {
            allowFirstDeviceCommit.countDown();
            executor.shutdownNow();
            transaction.executeWithoutResult(status -> taskRepository.deleteById(taskId));
        }
    }

    private LabelQcTask persistTask(String factoryId, Long userId, String key) {
        LabelQcTask task = LabelQcTask.builder()
                .factoryId(factoryId)
                .productTypeId("sku")
                .skuCode("SKU-1")
                .skuName("测试产品")
                .batchNumber("BATCH-1")
                .productionDate(LocalDate.now())
                .createdBy(userId)
                .idempotencyKey(key)
                .status(LabelQcTaskStatus.NEEDS_REVIEW)
                .photoCount(1)
                .aiCandidateCount(1)
                .finalDefectCount(0)
                .reviewedAt(LocalDateTime.now())
                .build();
        entityManager.persist(task);
        return task;
    }

    private LabelQcPhoto persistPhoto(LabelQcTask task, String attachmentId) {
        LabelQcPhoto photo = LabelQcPhoto.builder()
                .factoryId(task.getFactoryId())
                .taskId(task.getId())
                .attachmentId(attachmentId)
                .orderIndex(0)
                .imageWidth(1000)
                .imageHeight(2000)
                .status(LabelQcPhotoStatus.ANALYZED)
                .build();
        entityManager.persist(photo);
        return photo;
    }

    private void persistAnnotation(LabelQcTask task, LabelQcPhoto photo) {
        LabelQcAnnotation annotation = LabelQcAnnotation.builder()
                .factoryId(task.getFactoryId())
                .taskId(task.getId())
                .photoId(photo.getId())
                .source(LabelQcAnnotationSource.AI)
                .aiCandidateId("ai-1")
                .aiLabel(LabelQcLabel.MISSING_WHITE_LABEL)
                .aiConfidence(0.8)
                .xMin(0.1)
                .yMin(0.1)
                .xMax(0.5)
                .yMax(0.5)
                .build();
        entityManager.persist(annotation);
    }
}
