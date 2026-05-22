package com.cretas.aims.service.lineage.impl;

import com.cretas.aims.controller.dto.lineage.BatchLineageClosureDTO;
import com.cretas.aims.controller.dto.lineage.BatchLineageEdgeDTO;
import com.cretas.aims.controller.dto.lineage.LineageGraph;
import com.cretas.aims.entity.lineage.BatchLineageClosure;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.service.lineage.LineageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lineage 服务实现 — Phase 1 Sprint 1 Day 6 (2026-05-22).
 *
 * <p>无状态服务: 直接委托 {@code BatchLineageEdgeRepository} 查直接边,
 * {@code BatchLineageClosureRepository} 查闭包。所有方法 read-only.
 *
 * <h3>关于 maxDepth 过滤</h3>
 * <p>{@code findAncestors} / {@code findDescendants} 仓库方法不带 maxDepth 参数,
 * 由本 Service 层在内存中过滤 (按业务上闭包行数有限, 内存 filter 可接受)。
 * {@code findFullGraph} 仓库自身已带 maxDepth。
 *
 * <h3>关于 buildFullGraph 去重</h3>
 * <p>{@code BatchLineageClosureRepository.findFullGraph} 返回该节点作为 ancestor
 * 或 descendant 的所有 closure 行 (含 depth=0 self-edge); 同一节点对在 closure
 * 表中有 unique constraint (factory_id + ancestor + descendant), 行级无重复。
 * 但节点本身可能在多条 edges 中出现, 因此 nodes 列表用 LinkedHashSet 去重。
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 6)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LineageServiceImpl implements LineageService {

    private final BatchLineageEdgeRepository edgeRepository;
    private final BatchLineageClosureRepository closureRepository;

    /** 默认 buildFullGraph 最大深度. 业务上 4-5 跳已足够 (原料→生产→成品→出货). */
    private static final int DEFAULT_MAX_DEPTH = 5;

    /** {@inheritDoc} */
    @Override
    public List<BatchLineageEdgeDTO> findOutbound(String factoryId, String sourceId, String sourceType) {
        validate(factoryId, sourceId, sourceType, "sourceId/sourceType");
        log.debug("findOutbound: factoryId={}, sourceId={}, sourceType={}",
                factoryId, sourceId, sourceType);
        List<BatchLineageEdge> edges = edgeRepository
                .findByFactoryIdAndSourceIdAndSourceType(factoryId, sourceId, sourceType);
        return edges.stream()
                .map(BatchLineageEdgeDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /** {@inheritDoc} */
    @Override
    public List<BatchLineageEdgeDTO> findInbound(String factoryId, String targetId, String targetType) {
        validate(factoryId, targetId, targetType, "targetId/targetType");
        log.debug("findInbound: factoryId={}, targetId={}, targetType={}",
                factoryId, targetId, targetType);
        List<BatchLineageEdge> edges = edgeRepository
                .findByFactoryIdAndTargetIdAndTargetType(factoryId, targetId, targetType);
        return edges.stream()
                .map(BatchLineageEdgeDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /** {@inheritDoc} */
    @Override
    public List<BatchLineageClosureDTO> findAncestors(String factoryId, String nodeId,
                                                      String nodeType, Integer maxDepth) {
        validate(factoryId, nodeId, nodeType, "nodeId/nodeType");
        log.debug("findAncestors: factoryId={}, nodeId={}, nodeType={}, maxDepth={}",
                factoryId, nodeId, nodeType, maxDepth);
        List<BatchLineageClosure> ancestors = closureRepository
                .findAncestors(factoryId, nodeId, nodeType);
        return ancestors.stream()
                .filter(c -> isWithinDepth(c.getDepth(), maxDepth))
                .map(BatchLineageClosureDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /** {@inheritDoc} */
    @Override
    public List<BatchLineageClosureDTO> findDescendants(String factoryId, String nodeId,
                                                        String nodeType, Integer maxDepth) {
        validate(factoryId, nodeId, nodeType, "nodeId/nodeType");
        log.debug("findDescendants: factoryId={}, nodeId={}, nodeType={}, maxDepth={}",
                factoryId, nodeId, nodeType, maxDepth);
        List<BatchLineageClosure> descendants = closureRepository
                .findDescendants(factoryId, nodeId, nodeType);
        return descendants.stream()
                .filter(c -> isWithinDepth(c.getDepth(), maxDepth))
                .map(BatchLineageClosureDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /** {@inheritDoc} */
    @Override
    public LineageGraph buildFullGraph(String factoryId, String nodeId,
                                       String nodeType, Integer maxDepth) {
        validate(factoryId, nodeId, nodeType, "nodeId/nodeType");
        int depth = (maxDepth != null && maxDepth > 0) ? maxDepth : DEFAULT_MAX_DEPTH;
        log.debug("buildFullGraph: factoryId={}, nodeId={}, nodeType={}, maxDepth={}",
                factoryId, nodeId, nodeType, depth);

        List<BatchLineageClosure> closures = closureRepository
                .findFullGraph(factoryId, nodeId, nodeType, depth);

        // Collect unique nodes (insertion-ordered for stable response)
        Set<LineageGraph.Node> nodes = new LinkedHashSet<>();
        // Always include the query node itself (in case closure returns empty for an isolated node)
        nodes.add(new LineageGraph.Node(nodeType, nodeId));

        List<LineageGraph.Edge> edges = new ArrayList<>();
        for (BatchLineageClosure c : closures) {
            // Skip depth=0 self-refs in the edges array — they are not real lineage links
            // (self-ref's only purpose is internal trigger bookkeeping)
            if (c.getDepth() == null || c.getDepth() == 0) {
                // Still add to nodes set
                nodes.add(new LineageGraph.Node(c.getAncestorType(), c.getAncestorId()));
                continue;
            }
            nodes.add(new LineageGraph.Node(c.getAncestorType(), c.getAncestorId()));
            nodes.add(new LineageGraph.Node(c.getDescendantType(), c.getDescendantId()));
            edges.add(new LineageGraph.Edge(
                    c.getAncestorType(),
                    c.getAncestorId(),
                    c.getDescendantType(),
                    c.getDescendantId(),
                    c.getDepth()));
        }
        return new LineageGraph(new ArrayList<>(nodes), edges);
    }

    // ====================================================================
    // helpers
    // ====================================================================

    /**
     * 简单空检查. id/type 一起视为业务键.
     */
    private void validate(String factoryId, String id, String type, String label) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException("factoryId 不能为空");
        }
        if (id == null || id.isBlank() || type == null || type.isBlank()) {
            throw new BusinessException(label + " 不能为空");
        }
    }

    /**
     * 闭包深度过滤. {@code maxDepth} null/&lt;=0 视为不限制.
     */
    private boolean isWithinDepth(Integer depth, Integer maxDepth) {
        if (depth == null) return false;
        if (maxDepth == null || maxDepth <= 0) return true;
        return depth <= maxDepth;
    }
}
