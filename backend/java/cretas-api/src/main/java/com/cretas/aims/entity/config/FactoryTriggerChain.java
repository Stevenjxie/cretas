package com.cretas.aims.entity.config;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "factory_trigger_chains",
    uniqueConstraints = @UniqueConstraint(name = "idx_ftch_factory_chain", columnNames = {"factory_id", "chain_code"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FactoryTriggerChain {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 50)
    private String factoryId;

    @Column(name = "chain_code", length = 64, nullable = false)
    private String chainCode;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Type(JsonBinaryType.class)
    @Column(name = "steps", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<Map<String, Object>> steps = List.of();

    @Column(name = "error_strategy", length = 20, nullable = false)
    @Builder.Default
    private String errorStrategy = "CONTINUE";

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;

    // R7 G3: execution observability for E2E deep tests + operator diagnostics.
    // Written by TriggerChainExecutor.executeChain. NOT set by @PreUpdate —
    // updatedAt tracks config changes; these track execution events.
    @Column(name = "last_executed_at") private LocalDateTime lastExecutedAt;
    @Column(name = "last_execution_status", length = 20) private String lastExecutionStatus; // SUCCESS / PARTIAL / FAILED
    @Column(name = "last_execution_error", columnDefinition = "TEXT") private String lastExecutionError;
    @Column(name = "execution_count", nullable = false) @Builder.Default private Long executionCount = 0L;

    @PrePersist protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
