package com.cretas.aims.entity.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * 审批工作流边 (Jackson POJO, 存进 {@link ApprovalWorkflow#getEdgesJson()} 数组).
 *
 * <p>每 edge 表示一个状态转换:
 * <ul>
 *   <li>从 {@code source} 节点流向 {@code target} 节点</li>
 *   <li>{@code condition} 可选 — SpEL 表达式 (e.g. {@code #amount > 10000});
 *       空 = 总是走</li>
 *   <li>{@code label} — 显示标签 (e.g. ">10000" / "主管同意" / "REJECTED")</li>
 * </ul>
 *
 * <p>Conditional 节点的多 outgoing edges: executor 按 priority 顺序评估 condition,
 * 选第一个 true 的走 (其余跳过). 全 false → 走 label="DEFAULT" 的 edge, 否则报错.
 *
 * <p>Parallel 节点的多 outgoing edges: 全部启动 (condition 不评估).
 *
 * @since 2026-05-16
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApprovalWorkflowEdge {

    /** Edge ID — graph 内唯一. */
    private String id;

    /** 起点 nodeId. */
    private String source;

    /** 终点 nodeId. */
    private String target;

    /**
     * 条件表达式 (SpEL subset).
     * 例: {@code #amount > 10000}, {@code #department == 'finance'}.
     * 空 = 无条件总是走.
     */
    private String condition;

    /** 显示标签 (e.g. ">10000", "REJECTED", "主管同意"). */
    private String label;

    /** Conditional 节点多 outgoing 时的评估顺序, 数值越小越优先. */
    @Builder.Default
    private Integer priority = 0;
}
