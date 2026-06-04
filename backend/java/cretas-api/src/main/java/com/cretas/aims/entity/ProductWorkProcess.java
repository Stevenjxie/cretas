package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "product_work_processes",
    uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id", "product_type_id", "work_process_id"}),
    indexes = {
        @Index(name = "idx_pwp_product", columnList = "factory_id, product_type_id")
    }
)
public class ProductWorkProcess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 50)
    private String productTypeId;

    @Column(name = "work_process_id", nullable = false, length = 50)
    private String workProcessId;

    @Column(name = "process_order")
    @Builder.Default
    private Integer processOrder = 0;

    @Column(name = "unit_override", length = 20)
    private String unitOverride;

    @Column(name = "estimated_minutes_override")
    private Integer estimatedMinutesOverride;

    @Column(name = "responsible_worker_id")
    private Long responsibleWorkerId;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
