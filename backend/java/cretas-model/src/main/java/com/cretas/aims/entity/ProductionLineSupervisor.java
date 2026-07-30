package com.cretas.aims.entity;

import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 产线-车间主任关联实体
 * 用于确定每条产线由哪个车间主任负责
 */
@Data
@Entity
@Table(name = "production_line_supervisors")
public class ProductionLineSupervisor {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "production_line_id", length = 36, nullable = false)
    private String productionLineId;

    @Column(name = "supervisor_user_id", nullable = false)
    private Long supervisorUserId;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "is_primary")
    private Boolean isPrimary = true;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = java.util.UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.assignedAt == null) {
            this.assignedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
