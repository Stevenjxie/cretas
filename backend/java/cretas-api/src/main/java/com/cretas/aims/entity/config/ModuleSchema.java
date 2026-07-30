package com.cretas.aims.entity.config;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "module_schemas")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ModuleSchema {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "module_code", length = 64, nullable = false, unique = true)
    private String moduleCode;

    @Column(name = "module_name", length = 100, nullable = false)
    private String moduleName;

    @Column(name = "module_category", length = 32, nullable = false)
    private String moduleCategory;

    @Column(name = "module_version", nullable = false)
    @Builder.Default
    private Integer moduleVersion = 1;

    @Type(JsonBinaryType.class)
    @Column(name = "field_schema", columnDefinition = "jsonb", nullable = false)
    private Object fieldSchema;

    @Type(JsonBinaryType.class)
    @Column(name = "workflow_schema", columnDefinition = "jsonb")
    private Map<String, Object> workflowSchema;

    @Type(JsonBinaryType.class)
    @Column(name = "validation_schema", columnDefinition = "jsonb")
    private Map<String, Object> validationSchema;

    @Type(JsonBinaryType.class)
    @Column(name = "permission_schema", columnDefinition = "jsonb")
    private Map<String, Object> permissionSchema;

    @Type(JsonBinaryType.class)
    @Column(name = "default_config", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> defaultConfig;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
