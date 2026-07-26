package com.cretas.aims.repository;

import com.cretas.aims.entity.LabelQcTask;
import com.cretas.aims.entity.enums.LabelQcTaskStatus;
import com.cretas.aims.entity.enums.LabelQcTrainingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LabelQcTaskRepository extends JpaRepository<LabelQcTask, String> {
    Optional<LabelQcTask> findByFactoryIdAndId(String factoryId, String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT task
            FROM LabelQcTask task
            WHERE task.factoryId = :factoryId
              AND task.id = :taskId
            """)
    Optional<LabelQcTask> findByFactoryIdAndIdForUpdate(
            @Param("factoryId") String factoryId,
            @Param("taskId") String taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LabelQcTask> findFirstById(String id);

    Optional<LabelQcTask> findByFactoryIdAndCreatedByAndIdempotencyKey(
            String factoryId, Long createdBy, String idempotencyKey);

    Page<LabelQcTask> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    Page<LabelQcTask> findByFactoryIdAndStatusInOrderByCreatedAtDesc(
            String factoryId, Collection<LabelQcTaskStatus> statuses, Pageable pageable);

    Page<LabelQcTask> findByFactoryIdAndArchivedOrderByCreatedAtDesc(
            String factoryId, Boolean archived, Pageable pageable);

    Page<LabelQcTask> findByFactoryIdAndArchivedAndStatusInOrderByCreatedAtDesc(
            String factoryId,
            Boolean archived,
            Collection<LabelQcTaskStatus> statuses,
            Pageable pageable);

    long countByFactoryIdAndStatus(String factoryId, LabelQcTaskStatus status);

    long countByFactoryIdAndArchivedAndStatus(
            String factoryId, Boolean archived, LabelQcTaskStatus status);

    Page<LabelQcTask> findByFactoryIdAndStatusAndReviewedAtBetweenOrderByReviewedAtAsc(
            String factoryId,
            LabelQcTaskStatus status,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable);

    Page<LabelQcTask> findByFactoryIdAndStatusAndTrainingStatusAndReviewedAtBetweenOrderByReviewedAtAsc(
            String factoryId,
            LabelQcTaskStatus status,
            LabelQcTrainingStatus trainingStatus,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable);

    List<LabelQcTask> findTop20ByStatusOrderByCreatedAtAsc(LabelQcTaskStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<LabelQcTask> findTop20ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            LabelQcTaskStatus status,
            LocalDateTime cutoff);
}
