package com.cretas.aims.entity.config;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "factory_validation_rules",
    uniqueConstraints = @UniqueConstraint(name = "idx_fvr_factory_module_rule", columnNames = {"factory_id", "module_code", "rule_code"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FactoryValidationRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "factory_id", length = 50) private String factoryId;
    @Column(name = "module_code", length = 64, nullable = false) private String moduleCode;
    @Column(name = "rule_code", length = 64, nullable = false) private String ruleCode;
    @Column(name = "operation", length = 32) private String operation;
    @Column(name = "condition", columnDefinition = "TEXT", nullable = false) private String condition;
    @Column(name = "error_message", columnDefinition = "TEXT", nullable = false) private String errorMessage;
    @Column(name = "enabled", nullable = false) @Builder.Default private Boolean enabled = true;
    @Column(name = "severity", length = 16, nullable = false) @Builder.Default private String severity = "BLOCK";
    @Column(name = "sort_order") @Builder.Default private Integer sortOrder = 0;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
    @PrePersist protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
