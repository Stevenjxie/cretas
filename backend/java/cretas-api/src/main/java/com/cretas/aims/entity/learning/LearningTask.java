package com.cretas.aims.entity.learning;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Learning Task Entity
 *
 * Manages scheduling and execution of various learning tasks:
 * - Sample clustering analysis
 * - Transition matrix updates
 * - Keyword effectiveness evaluation
 * - Knowledge promotion checks
 * - Model retraining
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Entity
@Table(name = "learning_tasks",
    indexes = {
        @Index(name = "idx_task_type", columnList = "task_type"),
        @Index(name = "idx_factory", columnList = "factory_id"),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_scheduled", columnList = "scheduled_at"),
        @Index(name = "idx_priority_status", columnList = "priority DESC, status, scheduled_at")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ==================== Task Identity ====================

    @Column(name = "task_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private TaskType taskType;

    @Column(name = "task_name", length = 200)
    private String taskName;

    @Column(name = "factory_id", length = 50)
    private String factoryId;

    // ==================== Task Status ====================

    @Column(name = "status", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 5;

    // ==================== Scheduling Info ====================

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "timeout_minutes")
    @Builder.Default
    private Integer timeoutMinutes = 60;

    // ==================== Task Parameters & Result ====================

    @Column(name = "parameters", columnDefinition = "JSON")
    private String parameters;

    @Column(name = "result", columnDefinition = "JSON")
    private String result;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries")
    @Builder.Default
    private Integer maxRetries = 3;

    // ==================== Progress Tracking ====================

    @Column(name = "progress_percent")
    @Builder.Default
    private Integer progressPercent = 0;

    @Column(name = "progress_message", length = 500)
    private String progressMessage;

    // ==================== Audit Fields ====================

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Start task execution
     */
    public void start() {
        this.status = TaskStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
        this.progressPercent = 0;
        this.progressMessage = "Task started";
    }

    /**
     * Complete task successfully
     */
    public void complete(String resultJson) {
        this.status = TaskStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.result = resultJson;
        this.progressPercent = 100;
        this.progressMessage = "Task completed successfully";
    }

    /**
     * Fail task with error
     */
    public void fail(String error) {
        this.status = TaskStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = error;
        this.progressMessage = "Task failed: " + error;
    }

    /**
     * Cancel task
     */
    public void cancel() {
        this.status = TaskStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
        this.progressMessage = "Task cancelled";
    }

    /**
     * Update progress
     */
    public void updateProgress(int percent, String message) {
        this.progressPercent = Math.min(100, Math.max(0, percent));
        this.progressMessage = message;
    }

    /**
     * Check if task is timed out
     */
    public boolean isTimedOut() {
        if (startedAt == null || status != TaskStatus.RUNNING) {
            return false;
        }
        long minutesElapsed = ChronoUnit.MINUTES.between(startedAt, LocalDateTime.now());
        return minutesElapsed >= timeoutMinutes;
    }

    /**
     * Check if task can be retried
     */
    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    /**
     * Retry the task
     */
    public void retry() {
        this.retryCount++;
        this.status = TaskStatus.PENDING;
        this.startedAt = null;
        this.completedAt = null;
        this.errorMessage = null;
        this.progressPercent = 0;
        this.progressMessage = "Retry attempt " + retryCount;
    }

    /**
     * Check if task is ready to execute
     */
    public boolean isReadyToExecute() {
        if (status != TaskStatus.PENDING) {
            return false;
        }
        if (scheduledAt == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(scheduledAt);
    }

    /**
     * Task type enum
     */
    public enum TaskType {
        SAMPLE_CLUSTERING,      // Cluster low-confidence samples
        TRANSITION_ANALYSIS,    // Update intent transition matrix
        KEYWORD_EVALUATION,     // Evaluate keyword effectiveness
        KNOWLEDGE_PROMOTION,    // Check cross-factory knowledge promotion
        MODEL_RETRAIN,          // Retrain AI model
        PERFORMANCE_ANALYSIS,   // Analyze model performance
        ANNOTATION_ASSIGNMENT,  // Assign samples for annotation
        CLEANUP                 // Clean up old data
    }

    /**
     * Task status enum
     */
    public enum TaskStatus {
        PENDING,    // Waiting to execute
        RUNNING,    // Currently executing
        COMPLETED,  // Successfully completed
        FAILED,     // Failed with error
        CANCELLED   // Manually cancelled
    }

    /**
     * Create a new scheduled task
     */
    public static LearningTask createScheduled(
            TaskType type,
            String factoryId,
            LocalDateTime scheduledAt,
            String parameters) {
        return LearningTask.builder()
            .taskType(type)
            .taskName(type.name() + " - " + (factoryId != null ? factoryId : "GLOBAL"))
            .factoryId(factoryId)
            .status(TaskStatus.PENDING)
            .scheduledAt(scheduledAt)
            .parameters(parameters)
            .build();
    }
}
