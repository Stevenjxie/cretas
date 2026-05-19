package com.cretas.aims.entity.rules;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Audit log of every rule evaluation (matched rules only).
 *
 * Written by RuleEngine after each rule applies (LOG / REJECT / MODIFY / TRIGGER_WORKFLOW).
 * Used for:
 *  - replay / debug ("why was this order modified?")
 *  - rule effectiveness analytics
 *  - compliance audit trail
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Entity
@Table(name = "rule_execution_logs",
    indexes = {
        @Index(name = "idx_rule_logs_factory_rule",
               columnList = "factory_id, rule_id, executed_at DESC")
    })
@SQLDelete(sql = "UPDATE rule_execution_logs SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RuleExecutionLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /** Human-readable trigger context, e.g. "PurchaseServiceImpl.submitOrder" */
    @Column(name = "trigger_event", length = 100)
    private String triggerEvent;

    /** Snapshot of inputObject (sanitized) at evaluation time */
    @Type(JsonBinaryType.class)
    @Column(name = "input_json", columnDefinition = "jsonb")
    @lombok.Builder.Default
    private Map<String, Object> inputJson = new HashMap<>();

    /** What the engine actually did (action applied, modifications, reject reason, etc.) */
    @Type(JsonBinaryType.class)
    @Column(name = "result_json", columnDefinition = "jsonb")
    @lombok.Builder.Default
    private Map<String, Object> resultJson = new HashMap<>();

    @Column(name = "executed_at", nullable = false)
    @lombok.Builder.Default
    private LocalDateTime executedAt = LocalDateTime.now();
}
