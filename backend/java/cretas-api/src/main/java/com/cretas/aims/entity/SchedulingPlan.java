package com.cretas.aims.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.BatchSize;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.hibernate.annotations.Where;

/**
 * 调度计划实体
 * 代表某一天的整体调度计划
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "scheduling_plans",
       uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id", "plan_date"}))
@Where(clause = "deleted_at IS NULL")
public class SchedulingPlan extends BaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @Column(name = "plan_name", length = 100)
    private String planName;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PlanStatus status = PlanStatus.draft;

    @Column(name = "total_batches")
    private Integer totalBatches = 0;

    @Column(name = "total_workers")
    private Integer totalWorkers = 0;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @BatchSize(size = 20)
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<LineSchedule> lineSchedules;

    public enum PlanStatus {
        draft, confirmed, in_progress, completed, cancelled
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = java.util.UUID.randomUUID().toString();
        }
    }
}
