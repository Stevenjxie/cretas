package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

/**
 * 工作流流转规则 — 绑定到 {@link ApprovalWorkflow} condition 节点的用户友好条件配置.
 *
 * <p>跟 {@link ApprovalWorkflowEdge#getCondition()} (raw SpEL) 互补:
 * <ul>
 *   <li>{@code WorkflowRule} = 结构化用户友好层 (AMOUNT_THRESHOLD/DEPT_MATCH/ROLE_MATCH/SPEL_CUSTOM).
 *       Vue editor 配 + AIChat workflow_rule_test tool 测.</li>
 *   <li>{@code edge.condition} = raw SpEL escape hatch, 保留给 power user.</li>
 * </ul>
 *
 * <p><b>Executor 求值顺序</b> (in {@code ApprovalWorkflowExecutorImpl.advanceFromCondition}):
 * 先按 priority ASC 取所有 enabled rules, 逐一 evaluate;
 * 第一个 true 走 {@code trueTargetNodeId}, 第一个 false (若 {@code falseTargetNodeId} 非空) 走 false 分支,
 * 都不匹配再 fall through 到 edge.condition 评估 (现 Sprint 3 I 逻辑不变).
 *
 * <p>Sprint 4 Wave 1 (C-WF-RULE-1) introduced 2026-05-16.
 *
 * @author Cretas Team
 * @since 2026-05-16
 */
@Entity
@Table(name = "workflow_rules",
       indexes = {
         @Index(name = "idx_workflow_rules_workflow_node",
                columnList = "workflow_id,node_id,enabled,priority"),
         @Index(name = "idx_workflow_rules_factory",
                columnList = "factory_id")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRule extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 绑定到 ApprovalWorkflow.id */
    @Column(name = "workflow_id", nullable = false, length = 36)
    private String workflowId;

    /** 绑定到 condition 节点的 nodeId (graph 内字符串 id) */
    @Column(name = "node_id", nullable = false, length = 50)
    private String nodeId;

    /** 预留 — 未来 edge-level rule scope; Sprint 4 Wave 1 不消费 */
    @Column(name = "edge_id", length = 50)
    private String edgeId;

    @Column(name = "rule_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private RuleType ruleType;

    /**
     * 表达式 — JSON 字符串, schema per ruleType:
     * <ul>
     *   <li>AMOUNT_THRESHOLD: {@code {"field":"amount","op":">","value":10000}}</li>
     *   <li>DEPT_MATCH:       {@code {"field":"department","in":["finance","purchasing"]}}</li>
     *   <li>ROLE_MATCH:       {@code {"field":"role","in":["FACTORY_SUPER_ADMIN"]}}</li>
     *   <li>SPEL_CUSTOM:      {@code {"spel":"#amount > 10000 && #department == 'finance'"}}</li>
     * </ul>
     */
    @Column(name = "expression", nullable = false, columnDefinition = "jsonb")
    private String expression;

    /** rule eval=true 时路由到此 nodeId */
    @Column(name = "true_target_node_id", length = 50)
    private String trueTargetNodeId;

    /** rule eval=false 时路由到此 nodeId. NULL = 继续下一 rule (fall through) */
    @Column(name = "false_target_node_id", length = 50)
    private String falseTargetNodeId;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private Integer priority = 0;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "description", length = 500)
    private String description;

    public enum RuleType {
        AMOUNT_THRESHOLD,
        DEPT_MATCH,
        ROLE_MATCH,
        SPEL_CUSTOM
    }
}
