package com.cretas.aims.entity.config;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "factory_module_configs",
    uniqueConstraints = {
        @UniqueConstraint(name = "idx_fmc_factory_module_version",
            columnNames = {"factory_id", "module_code", "config_version"})
    },
    indexes = {
        @Index(name = "idx_fmc_factory", columnList = "factory_id")
    })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class FactoryModuleConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "module_code", length = 64, nullable = false)
    private String moduleCode;

    @Column(name = "config_version", nullable = false)
    @Builder.Default
    private Integer configVersion = 1;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Type(JsonBinaryType.class)
    @Column(name = "field_config", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> fieldConfig = Map.of();

    @Type(JsonBinaryType.class)
    @Column(name = "workflow_config", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> workflowConfig = Map.of();

    @Type(JsonBinaryType.class)
    @Column(name = "validation_config", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> validationConfig = Map.of();

    @Type(JsonBinaryType.class)
    @Column(name = "permission_config", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> permissionConfig = Map.of();

    /**
     * Layer 2: 工厂级 role × module 权限 override.
     * 格式: {"role_code": {"module_code": "rw|r|w|-"}}
     * 缺失的 (role, module) 组合 fallback 到 platform_role_permissions.
     * See: docs/superpowers/specs/2026-04-18-permission-matrix-ai-driven-design.md §4.1
     */
    @Type(JsonBinaryType.class)
    @Column(name = "role_module_override", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Map<String, String>> roleModuleOverride = Map.of();

    @Type(JsonBinaryType.class)
    @Column(name = "layout_config", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> layoutConfig = Map.of();

    @Type(JsonBinaryType.class)
    @Column(name = "custom_labels", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> customLabels = Map.of();

    @Type(JsonBinaryType.class)
    @Column(name = "computed_fields", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> computedFields = Map.of();

    @Column(name = "rendering_mode", length = 16, nullable = false)
    @Builder.Default
    private String renderingMode = "LEGACY";

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
