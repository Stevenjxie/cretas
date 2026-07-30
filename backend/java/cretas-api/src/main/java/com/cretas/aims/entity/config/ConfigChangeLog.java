package com.cretas.aims.entity.config;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "config_change_log",
    indexes = {
        @Index(name = "idx_ccl_factory", columnList = "factory_id"),
        @Index(name = "idx_ccl_factory_module", columnList = "factory_id, module_code"),
        @Index(name = "idx_ccl_created", columnList = "created_at")
    })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ConfigChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "module_code", length = 64)
    private String moduleCode;

    @Column(name = "operation", length = 32, nullable = false)
    private String operation;

    @Type(JsonBinaryType.class)
    @Column(name = "before_value", columnDefinition = "jsonb")
    private Map<String, Object> beforeValue;

    @Type(JsonBinaryType.class)
    @Column(name = "after_value", columnDefinition = "jsonb")
    private Map<String, Object> afterValue;

    @Column(name = "diff_summary", columnDefinition = "TEXT")
    private String diffSummary;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Column(name = "operator_type", length = 16, nullable = false)
    @Builder.Default
    private String operatorType = "USER";

    @Column(name = "ai_prompt", columnDefinition = "TEXT")
    private String aiPrompt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
