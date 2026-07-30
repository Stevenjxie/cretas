package com.cretas.aims.entity;

import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 调度告警实体
 * 记录调度过程中的告警信息
 */
@Data
@Entity
@Table(name = "scheduling_alerts")
public class SchedulingAlert {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "schedule_id", length = 36)
    private String scheduleId;

    @Column(name = "plan_id", length = 36)
    private String planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", length = 30, nullable = false)
    private AlertType alertType;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Severity severity = Severity.warning;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "suggested_action", columnDefinition = "TEXT")
    private String suggestedAction;

    @Column(name = "is_resolved")
    private Boolean isResolved = false;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public enum AlertType {
        low_probability, resource_conflict, deadline_risk, efficiency_drop, worker_shortage, equipment_issue
    }

    public enum Severity {
        info, warning, critical
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = java.util.UUID.randomUUID().toString();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
