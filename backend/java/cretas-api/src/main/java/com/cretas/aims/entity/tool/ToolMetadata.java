package com.cretas.aims.entity.tool;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

@Entity
@Table(name = "tool_metadata", indexes = {
    @Index(name = "idx_tm_action_type", columnList = "action_type"),
    @Index(name = "idx_tm_risk_level", columnList = "risk_level"),
    @Index(name = "idx_tm_last_called", columnList = "last_called_at")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class ToolMetadata extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tool_name", nullable = false, unique = true, length = 100)
    private String toolName;

    @Column(name = "action_type", nullable = false, length = 20)
    @Builder.Default
    private String actionType = "READ";

    @Column(name = "risk_level", nullable = false, length = 20)
    @Builder.Default
    private String riskLevel = "LOW";

    @Column(name = "domain_tags", columnDefinition = "TEXT")
    @Builder.Default
    private String domainTags = "[]";

    @Column(name = "tool_version", length = 20)
    @Builder.Default
    private String toolVersion = "1.0.0";

    @Column(name = "deprecation_notice", columnDefinition = "TEXT")
    private String deprecationNotice;

    @Column(name = "last_called_at")
    private LocalDateTime lastCalledAt;

    @Column(name = "call_count", nullable = false)
    @Builder.Default
    private Long callCount = 0L;

    @Column(name = "success_count", nullable = false)
    @Builder.Default
    private Long successCount = 0L;

    @Column(name = "failure_count", nullable = false)
    @Builder.Default
    private Long failureCount = 0L;

    @Column(name = "avg_execution_ms")
    @Builder.Default
    private Double avgExecutionMs = 0.0;
}
