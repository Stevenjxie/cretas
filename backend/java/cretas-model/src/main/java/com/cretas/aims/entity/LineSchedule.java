package com.cretas.aims.entity;

import lombok.Data;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 产线排程实体
 * 具体的批次-产线-时间安排
 */
@Data
@Entity
@Table(name = "line_schedules")
public class LineSchedule {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private SchedulingPlan plan;

    @Column(name = "plan_id", insertable = false, updatable = false)
    private String planId;

    @Column(name = "production_line_id", length = 36, nullable = false)
    private String productionLineId;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "sequence_order")
    private Integer sequenceOrder = 0;

    @Column(name = "planned_start_time")
    private LocalDateTime plannedStartTime;

    @Column(name = "planned_end_time")
    private LocalDateTime plannedEndTime;

    @Column(name = "actual_start_time")
    private LocalDateTime actualStartTime;

    @Column(name = "actual_end_time")
    private LocalDateTime actualEndTime;

    @Column(name = "assigned_workers")
    private Integer assignedWorkers = 0;

    @Column(name = "planned_quantity")
    private Integer plannedQuantity;

    @Column(name = "completed_quantity")
    private Integer completedQuantity = 0;

    @Column(name = "predicted_efficiency", precision = 5, scale = 2)
    private BigDecimal predictedEfficiency;

    @Column(name = "actual_efficiency", precision = 5, scale = 2)
    private BigDecimal actualEfficiency;

    @Column(name = "predicted_completion_prob", precision = 5, scale = 4)
    private BigDecimal predictedCompletionProb;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ScheduleStatus status = ScheduleStatus.pending;

    @Column(name = "delay_reason", columnDefinition = "TEXT")
    private String delayReason;

    @Column(name = "plan_efficiency", precision = 10, scale = 2)
    private BigDecimal planEfficiency;

    @Column(name = "efficiency_variance", precision = 5, scale = 2)
    private BigDecimal efficiencyVariance;

    @Column(name = "predicted_end")
    private LocalDateTime predictedEnd;

    @Column(name = "risk_level", length = 20)
    private String riskLevel = "low";

    @Column(name = "adjustment_count")
    private Integer adjustmentCount = 0;

    @Column(name = "last_adjustment_time")
    private LocalDateTime lastAdjustmentTime;

    @Column(name = "adjustment_reason", length = 200)
    private String adjustmentReason;

    @Column(name = "supervisor_id")
    private Long supervisorId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<WorkerAssignment> workerAssignments;

    public enum ScheduleStatus {
        pending, in_progress, completed, delayed, cancelled
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = java.util.UUID.randomUUID().toString();
        }
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
