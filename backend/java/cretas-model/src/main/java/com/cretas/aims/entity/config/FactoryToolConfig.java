package com.cretas.aims.entity.config;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "factory_tool_configs",
    uniqueConstraints = @UniqueConstraint(name = "idx_ftc_factory_tool", columnNames = {"factory_id", "tool_name"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FactoryToolConfig {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "tool_name", length = 100, nullable = false)
    private String toolName;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Type(JsonBinaryType.class)
    @Column(name = "param_overrides", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> paramOverrides = Map.of();

    @Column(name = "risk_override", length = 20)
    private String riskOverride;

    @Column(name = "custom_description", columnDefinition = "TEXT")
    private String customDescription;

    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;

    @PrePersist protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
