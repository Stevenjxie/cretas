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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void repositoriesBootAndQueriesKeepTenantIsolation() {
        LabelQcTask taskA = persistTask("F-LABEL-A", 11L, "idem-a");
        LabelQcTask taskB = persistTask("F-LABEL-B", 11L, "idem-b");
        LabelQcPhoto photoA = persistPhoto(taskA, "attachment-a");
        persistAnnotation(taskA, photoA);
        entityManager.flush();
        entityManager.clear();

        assertThat(taskRepository.findByFactoryIdAndId("F-LABEL-A", taskA.getId())).isPresent();
        assertThat(taskRepository.findByFactoryIdAndId("F-LABEL-B", taskA.getId())).isEmpty();
        assertThat(taskRepository.findByFactoryIdAndCreatedByAndIdempotencyKey(
                "F-LABEL-A", 11L, "idem-a")).isPresent();
        assertThat(taskRepository.findFirstById(taskA.getId())).isPresent();
        Page<LabelQcTask> matchingTasks =
                taskRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                        "F-LABEL-A",
                        List.of(LabelQcTaskStatus.NEEDS_REVIEW),
                        PageRequest.of(0, 10));
        assertThat(matchingTasks.getTotalElements()).isEqualTo(1L);
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
        assertThat(taskRepository.findTop20ByStatusOrderByCreatedAtAsc(
                LabelQcTaskStatus.NEEDS_REVIEW)).hasSize(2);
        assertThat(taskRepository.findTop20ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                LabelQcTaskStatus.NEEDS_REVIEW,
                LocalDateTime.now().plusMinutes(1))).hasSize(2);
        assertThat(taskB.getFactoryId()).isEqualTo("F-LABEL-B");
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
