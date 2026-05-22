package com.cretas.aims.service.lineage;

import com.cretas.aims.controller.dto.lineage.BatchLineageClosureDTO;
import com.cretas.aims.controller.dto.lineage.BatchLineageEdgeDTO;
import com.cretas.aims.controller.dto.lineage.LineageGraph;

import java.util.List;

/**
 * 批次 lineage 服务 — Phase 1 Sprint 1 Day 6 (2026-05-22).
 *
 * <p>食品行业批次级溯源 API. 委托 {@code BatchLineageEdgeRepository} 查直接边,
 * 委托 {@code BatchLineageClosureRepository} 查多跳传递闭包。
 *
 * <h3>核心场景</h3>
 * <ol>
 *   <li><b>findOutbound</b> — 某节点 (原料/批次) 的下游直接边. "这批原料去了哪 (1 跳)"</li>
 *   <li><b>findInbound</b> — 某节点的上游直接边. "这个成品/出货来自哪 (1 跳)"</li>
 *   <li><b>findAncestors</b> — 多跳上游 (闭包). "客户投诉成品 → 追溯到原料 → 供应商 → CCP"</li>
 *   <li><b>findDescendants</b> — 多跳下游 (闭包). "原料批次出问题 → 影响哪些成品 → 哪些客户"</li>
 *   <li><b>buildFullGraph</b> — 双向整图. 给前端 d3/vis-network 渲染溯源图。</li>
 * </ol>
 *
 * <p>所有方法都做多租户隔离, factoryId 强制传入。
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 6)
 */
public interface LineageService {

    /**
     * 查询某源节点的全部下游直接边 (1 跳).
     *
     * @param factoryId  工厂 ID
     * @param sourceId   源节点 ID
     * @param sourceType 源节点类型 (MATERIAL_BATCH / PRODUCTION_BATCH / FINISHED_BATCH / SHIPMENT_RECORD)
     * @return 直接下游 edges (按 createdAt 顺序)
     */
    List<BatchLineageEdgeDTO> findOutbound(String factoryId, String sourceId, String sourceType);

    /**
     * 查询某目标节点的全部上游直接边 (1 跳).
     *
     * @param factoryId  工厂 ID
     * @param targetId   目标节点 ID
     * @param targetType 目标节点类型
     * @return 直接上游 edges
     */
    List<BatchLineageEdgeDTO> findInbound(String factoryId, String targetId, String targetType);

    /**
     * 查询某节点的全部祖先 (多跳闭包, depth&gt;0).
     *
     * <p>若 {@code maxDepth} 为 null 或 &lt;=0, 不限深度 (取仓库全量).
     *
     * @param factoryId 工厂 ID
     * @param nodeId    descendant 节点 ID
     * @param nodeType  descendant 节点类型
     * @param maxDepth  最大深度限制 (含; null/0 表示不限制)
     * @return 祖先闭包记录 (depth ASC)
     */
    List<BatchLineageClosureDTO> findAncestors(String factoryId, String nodeId,
                                                String nodeType, Integer maxDepth);

    /**
     * 查询某节点的全部后代 (多跳闭包, depth&gt;0).
     *
     * <p>若 {@code maxDepth} 为 null 或 &lt;=0, 不限深度.
     *
     * @param factoryId 工厂 ID
     * @param nodeId    ancestor 节点 ID
     * @param nodeType  ancestor 节点类型
     * @param maxDepth  最大深度限制 (含; null/0 表示不限制)
     * @return 后代闭包记录 (depth ASC)
     */
    List<BatchLineageClosureDTO> findDescendants(String factoryId, String nodeId,
                                                  String nodeType, Integer maxDepth);

    /**
     * 构建某节点的整图 (双向遍历, 给前端可视化).
     *
     * <p>返回值的 {@code nodes} 是去重后的所有相关节点 (含查询节点本身),
     * {@code edges} 是闭包派生的全部祖先-后代边 (去掉 depth=0 自引用).
     *
     * @param factoryId 工厂 ID
     * @param nodeId    起点节点 ID
     * @param nodeType  起点节点类型
     * @param maxDepth  最大深度 (默认 5, 业务上 4-5 已足够)
     * @return 整图数据 (nodes + edges)
     */
    LineageGraph buildFullGraph(String factoryId, String nodeId, String nodeType, Integer maxDepth);
}
