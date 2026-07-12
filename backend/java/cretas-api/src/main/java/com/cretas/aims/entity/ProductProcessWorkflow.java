package com.cretas.aims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

/** 独立于线性 ProductWorkProcess 的产品工序图版本。 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "product_process_workflows")
@Where(clause = "deleted_at IS NULL")
public class ProductProcessWorkflow extends BaseEntity {

    public enum Status {
        DRAFT,
        PUBLISHED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 64)
    private String productTypeId;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Column(name = "definition_version", nullable = false)
    private Integer definitionVersion = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nodes_json", nullable = false, columnDefinition = "jsonb")
    private String nodesJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edges_json", nullable = false, columnDefinition = "jsonb")
    private String edgesJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "viewport_json", nullable = false, columnDefinition = "jsonb")
    private String viewportJson = "{\"x\":0,\"y\":0,\"zoom\":1}";

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;
}
