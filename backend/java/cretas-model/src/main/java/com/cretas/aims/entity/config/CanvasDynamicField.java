package com.cretas.aims.entity.config;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "canvas_dynamic_field",
    indexes = {
        @Index(name = "idx_cdf_factory_module", columnList = "factory_id, module_code"),
        @Index(name = "idx_cdf_status", columnList = "status")
    })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CanvasDynamicField {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private String id;

    @Column(name = "factory_id", length = 50)
    private String factoryId;

    @Column(name = "module_code", length = 50, nullable = false)
    private String moduleCode;

    @Column(name = "field_code", length = 100, nullable = false)
    private String fieldCode;

    @Column(name = "field_type", length = 20, nullable = false)
    private String fieldType;

    @Column(name = "label", length = 200, nullable = false)
    private String label;

    @Type(JsonBinaryType.class)
    @Column(name = "config", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> config = Map.of();

    @Column(name = "visible_when", length = 500)
    private String visibleWhen;

    @Column(name = "computed_when", length = 500)
    private String computedWhen;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "PENDING_DDL";

    @Column(name = "column_name", length = 100)
    private String columnName;

    /** Round 4 Fix P0-5: Version when this field first became active. Used by rollback
     *  to disable fields that were added after the rollback target version.
     *  NULL = field existed before version tracking was introduced (treat as version 0). */
    @Column(name = "active_from_version")
    private Integer activeFromVersion;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (columnName == null && fieldCode != null) {
            columnName = "cf_" + fieldCode;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
