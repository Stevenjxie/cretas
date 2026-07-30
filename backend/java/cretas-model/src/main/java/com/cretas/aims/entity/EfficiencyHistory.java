package com.cretas.aims.entity;

import lombok.Data;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 效率历史记录实体
 * 用于记录产线的效率历史数据
 */
@Data
@Entity
@Table(name = "aps_efficiency_history")
public class EfficiencyHistory {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "line_id", length = 36, nullable = false)
    private String lineId;

    @Column(name = "task_id", length = 36)
    private String taskId;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "actual_output", precision = 10, scale = 2)
    private BigDecimal actualOutput;

    @Column(name = "expected_output", precision = 10, scale = 2)
    private BigDecimal expectedOutput;

    @Column(name = "efficiency_ratio", precision = 5, scale = 4)
    private BigDecimal efficiencyRatio;

    @Column(name = "worker_count")
    private Integer workerCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = java.util.UUID.randomUUID().toString();
        }
        this.createdAt = LocalDateTime.now();
    }
}
