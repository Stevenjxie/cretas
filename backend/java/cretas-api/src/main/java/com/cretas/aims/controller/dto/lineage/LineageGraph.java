package com.cretas.aims.controller.dto.lineage;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 整图数据结构 — Phase 1 Sprint 1 Day 6 (2026-05-22).
 *
 * <p>用于前端可视化的图数据格式。{@link #nodes} 是节点列表 (去重的 type+id 对),
 * {@link #edges} 是 ancestor → descendant 的有向边列表 (depth=1 直接边 +
 * depth>1 派生的传递边)。
 *
 * <p>食品行业典型用法 — 客户投诉成品出问题, 用 {@code buildFullGraph(成品Id)}
 * 拿到该节点的上游 (原料) + 下游 (出货客户), 前端用 d3/vis-network 渲染溯源图。
 *
 * @param nodes 节点列表 (按 type+id 唯一)
 * @param edges 有向边列表 (祖先→后代)
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 6)
 */
@Schema(description = "lineage 整图数据 (nodes + edges)")
public record LineageGraph(
        @Schema(description = "节点列表 (按 type+id 唯一)")
        List<Node> nodes,
        @Schema(description = "有向边列表 (祖先→后代)")
        List<Edge> edges
) {

    /**
     * 图节点 — 用 (type, id) 二元组唯一标识。
     *
     * @param nodeType 节点类型 (MATERIAL_BATCH / PRODUCTION_BATCH / FINISHED_BATCH / SHIPMENT_RECORD)
     * @param nodeId   节点 ID
     */
    @Schema(description = "图节点")
    public record Node(
            @Schema(description = "节点类型") String nodeType,
            @Schema(description = "节点 ID") String nodeId
    ) {}

    /**
     * 图有向边 — 从 ancestor → descendant, 携带 depth (1=直接边, >1=传递闭包派生).
     *
     * @param ancestorType   祖先节点类型
     * @param ancestorId     祖先节点 ID
     * @param descendantType 后代节点类型
     * @param descendantId   后代节点 ID
     * @param depth          传递深度 (1=直接边, >1=多跳)
     */
    @Schema(description = "图有向边")
    public record Edge(
            @Schema(description = "祖先节点类型") String ancestorType,
            @Schema(description = "祖先节点 ID") String ancestorId,
            @Schema(description = "后代节点类型") String descendantType,
            @Schema(description = "后代节点 ID") String descendantId,
            @Schema(description = "传递深度") Integer depth
    ) {}
}
