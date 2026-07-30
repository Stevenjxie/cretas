package com.cretas.aims.entity.rules;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 规则事件绑定实体
 *
 * 将规则组绑定到实体类型和事件类型
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Entity
@Table(name = "rule_event_bindings",
       indexes = {
           @Index(name = "idx_rule_bindings_factory", columnList = "factory_id"),
           @Index(name = "idx_rule_bindings_entity", columnList = "factory_id, entity_type"),
           @Index(name = "idx_rule_bindings_event", columnList = "factory_id, entity_type, event_type")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_rule_bindings",
                            columnNames = {"factory_id", "entity_type", "event_type", "rule_group"})
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleEventBinding {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /**
     * 实体类型
     * 如: QUALITY_CHECK, MATERIAL_BATCH, PROCESSING_BATCH
     */
    @Column(name = "entity_type", length = 50, nullable = false)
    private String entityType;

    /**
     * 事件类型
     * 如: beforeSubmit, afterSubmit, onTransition, onValidation
     */
    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    /**
     * 关联的规则组
     */
    @Column(name = "rule_group", length = 50, nullable = false)
    private String ruleGroup;

    /**
     * 执行优先级
     */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 触发条件表达式 (可选)
     * 如: "quantity > 100 && status == 'pending'"
     */
    @Column(name = "condition_expression", length = 500)
    private String conditionExpression;

    /**
     * 创建时间
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
