package com.cretas.aims.entity.workflow;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "production_workflow_instances",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_production_workflow_instance_batch",
                columnNames = {"factory_id", "production_batch_id"}))
@Where(clause = "deleted_at IS NULL")
public class ProductionWorkflowInstance extends BaseEntity {

    public enum Status {
        ACTIVE,
        COMPLETED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "production_batch_id", nullable = false)
    private Long productionBatchId;

    @Column(name = "product_type_id", nullable = false, length = 64)
    private String productTypeId;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "definition_version", nullable = false)
    private Integer definitionVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nodes_json", nullable = false, columnDefinition = "jsonb")
    private String nodesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edges_json", nullable = false, columnDefinition = "jsonb")
    private String edgesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "compiled_at", nullable = false)
    private LocalDateTime compiledAt = LocalDateTime.now();
}
