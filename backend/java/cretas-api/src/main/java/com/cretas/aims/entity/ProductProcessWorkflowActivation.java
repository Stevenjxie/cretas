package com.cretas.aims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "product_process_workflow_activations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_product_process_workflow_activation",
                columnNames = {"factory_id", "product_type_id"}))
@Where(clause = "deleted_at IS NULL")
public class ProductProcessWorkflowActivation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 64)
    private String productTypeId;

    @Column(name = "active_workflow_id", nullable = false)
    private Long activeWorkflowId;

    @Column(name = "active_definition_version", nullable = false)
    private Integer activeDefinitionVersion;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "activated_by")
    private Long activatedBy;

    @Column(name = "activated_at", nullable = false)
    private LocalDateTime activatedAt = LocalDateTime.now();

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;
}
