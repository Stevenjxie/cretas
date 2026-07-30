package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;

/**
 * 审批工作流配置 (graph-native).
 *
 * <p>跟 {@link ApprovalChainConfig} 互补:
 * <ul>
 *   <li>{@code ApprovalChainConfig} = flat list, 每行一个审批级别, 多级靠
 *       {@code escalationConfigId} 串. 只能表达线性 + 简单升级链.</li>
 *   <li>{@code ApprovalWorkflow} = graph, nodes/edges JSONB. 能表达
 *       sequential / parallel / conditional / 会签 N-of-M 四种执行模式.</li>
 * </ul>
 *
 * <p><b>Dual-source read</b>: {@code ApprovalChainService.requiresApproval()}
 * 优先查 ApprovalWorkflow (published + enabled), 找到则走 graph executor;
 * 否则 fallback 到 legacy flat ApprovalChainConfig 逻辑. Track-E/H 调用方
 * 无感, 完全加性扩展.
 *
 * <p>Sprint 3 Track-I (C-APPROVAL-EDITOR-1) introduced 2026-05-16.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-16
 */
@Entity
@Table(name = "approval_workflows",
       uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id", "decision_type", "name"}))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalWorkflow extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 决策类型 — 复用 {@link ApprovalChainConfig.DecisionType} 10 种 enum.
     * Sprint 3 内 Voucher / ECN 借用 {@code CUSTOM} + name 区分, Sprint 4
     * 可扩 enum 加 {@code VOUCHER_APPROVAL} / {@code ECN_APPROVAL}.
     */
    @Column(name = "decision_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private ApprovalChainConfig.DecisionType decisionType;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /**
     * Graph nodes — JSONB array of {@link ApprovalWorkflowNode}.
     * 服务层用 Jackson serde 在 String ↔ List 之间转换.
     */
    @Column(name = "nodes_json", nullable = false, columnDefinition = "jsonb")
    private String nodesJson;

    /**
     * Graph edges — JSONB array of {@link ApprovalWorkflowEdge}.
     */
    @Column(name = "edges_json", nullable = false, columnDefinition = "jsonb")
    private String edgesJson;

    /** 工作流入口节点 id (必须在 nodes 中存在且 type=start). */
    @Column(name = "start_node_id", length = 50)
    private String startNodeId;

    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    /** draft / published / archived */
    @Column(name = "publish_status", length = 20)
    @Builder.Default
    private String publishStatus = "draft";

    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    /** 数值越大优先级越高 — 同 decisionType 多 workflow 时由 executor 选优先级最高的 published+enabled. */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;
}
