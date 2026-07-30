package com.cretas.aims.entity.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;
import java.util.Map;

/**
 * 审批工作流节点 (Jackson POJO, 存进 {@link ApprovalWorkflow#getNodesJson()} 数组).
 *
 * <p>Node type schema (7 种):
 * <ul>
 *   <li>{@code start} — 工作流入口, config 空</li>
 *   <li>{@code approval} — 单个审批步骤, config 含 approverRoles / requiredApprovers / timeoutMinutes 等</li>
 *   <li>{@code condition} — 条件分叉 (实际条件在 outgoing edges 的 condition 字段)</li>
 *   <li>{@code parallel} — 并行分叉 (fan-out, 所有 outgoing 同时启动)</li>
 *   <li>{@code join} — 汇合节点 (会签 / parallel join), config 含 mode (ALL/N_OF_M/ANY)</li>
 *   <li>{@code notify} — 通知节点 (推 InAppNotification, 不阻塞流程)</li>
 *   <li>{@code end} — 工作流结束, config 含 outcome (APPROVED/REJECTED/TIMEOUT/CANCELLED)</li>
 * </ul>
 *
 * <p>config 字段是 {@code Map<String, Object>} 因为 schema per type 不同;
 * 详细 schema 见 design doc {@code docs/superpowers/specs/2026-05-16-c-approval-editor-design.md}
 * §2.2 ApprovalWorkflowNode types.
 *
 * @since 2026-05-16
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApprovalWorkflowNode {

    /** 节点 ID — graph 内唯一, edges 引用. */
    private String id;

    /** 节点类型: start / approval / condition / parallel / join / notify / end */
    private String type;

    /** 显示名 (e.g. "工程师审批" / "条件: 金额阈值"). */
    private String label;

    /** 画布位置 (前端用, 后端透传). */
    private Position position;

    /** Type-specific 配置, 详细见 design doc §2.2. */
    @Builder.Default
    private Map<String, Object> config = Map.of();

    /** 允许 next node types — 校验用 (e.g. start 后必须接 approval/condition/parallel, end 后无 next). */
    private List<String> allowedNextTypes;

    /**
     * 节点画布坐标.
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Position {
        private Double x;
        private Double y;
    }
}
