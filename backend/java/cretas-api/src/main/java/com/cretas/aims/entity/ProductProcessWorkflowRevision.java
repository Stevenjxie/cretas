package com.cretas.aims.entity;

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

/**
 * Immutable, content-addressed snapshot produced whenever a Workflow draft is saved.
 *
 * <p>The mutable editor row remains in {@code product_process_workflows}; BOM versions pin
 * this immutable row (and also copy its JSON) so later editor saves cannot drift an already
 * configured recipe.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "product_process_workflow_revisions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ppwr_workflow_hash", columnNames = {"workflow_id", "revision_hash"}),
        @UniqueConstraint(name = "uk_ppwr_workflow_revision", columnNames = {"workflow_id", "revision_number"})
})
@Where(clause = "deleted_at IS NULL")
public class ProductProcessWorkflowRevision extends BaseEntity {

    public enum Status { DRAFT, PUBLISHED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 64)
    private String productTypeId;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "definition_version", nullable = false)
    private Integer definitionVersion;

    @Column(name = "revision_number", nullable = false)
    private Integer revisionNumber;

    @Column(name = "revision_hash", nullable = false, length = 64)
    private String revisionHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nodes_json", nullable = false, columnDefinition = "jsonb")
    private String nodesJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edges_json", nullable = false, columnDefinition = "jsonb")
    private String edgesJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "viewport_json", nullable = false, columnDefinition = "jsonb")
    private String viewportJson = "{\"x\":0,\"y\":0,\"zoom\":1}";

    @Column(name = "process_count", nullable = false)
    private Integer processCount = 0;

    @Column(name = "structurally_complete", nullable = false)
    private Boolean structurallyComplete = false;

    @Column(name = "validation_message", length = 500)
    private String validationMessage;
}
