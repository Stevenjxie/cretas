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
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    @Setter(AccessLevel.NONE)
    @Column(name = "factory_id", nullable = false, updatable = false, length = 64)
    private String factoryId;

    @Setter(AccessLevel.NONE)
    @Column(name = "production_batch_id", nullable = false, updatable = false)
    private Long productionBatchId;

    @Setter(AccessLevel.NONE)
    @Column(name = "product_type_id", nullable = false, updatable = false, length = 64)
    private String productTypeId;

    @Setter(AccessLevel.NONE)
    @Column(name = "workflow_id", nullable = false, updatable = false)
    private Long workflowId;

    @Setter(AccessLevel.NONE)
    @Column(name = "definition_version", nullable = false, updatable = false)
    private Integer definitionVersion;

    @Setter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nodes_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String nodesJson;

    @Setter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edges_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String edgesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Setter(AccessLevel.NONE)
    @Column(name = "compiled_at", nullable = false, updatable = false)
    private LocalDateTime compiledAt = LocalDateTime.now();

    public static ProductionWorkflowInstance create(
            String factoryId,
            Long productionBatchId,
            String productTypeId,
            Long workflowId,
            Integer definitionVersion,
            String nodesJson,
            String edgesJson,
            LocalDateTime compiledAt) {
        ProductionWorkflowInstance instance = new ProductionWorkflowInstance();
        instance.factoryId = factoryId;
        instance.productionBatchId = productionBatchId;
        instance.productTypeId = productTypeId;
        instance.workflowId = workflowId;
        instance.definitionVersion = definitionVersion;
        instance.nodesJson = nodesJson;
        instance.edgesJson = edgesJson;
        instance.compiledAt = compiledAt;
        instance.status = Status.ACTIVE;
        return instance;
    }
}
