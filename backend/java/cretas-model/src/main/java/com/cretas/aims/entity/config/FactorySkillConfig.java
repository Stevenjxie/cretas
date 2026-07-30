package com.cretas.aims.entity.config;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "factory_skill_configs",
    uniqueConstraints = @UniqueConstraint(name = "idx_fsc_factory_skill", columnNames = {"factory_id", "skill_name"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FactorySkillConfig {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "skill_name", length = 100, nullable = false)
    private String skillName;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Type(JsonBinaryType.class)
    @Column(name = "custom_dag", columnDefinition = "jsonb")
    private Map<String, Object> customDag;

    @Type(JsonBinaryType.class)
    @Column(name = "custom_triggers", columnDefinition = "jsonb")
    private List<String> customTriggers;

    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 100;

    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;

    @PrePersist protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
