package com.cretas.aims.service.lineage.impl;

import com.cretas.aims.controller.dto.lineage.BatchLineageClosureDTO;
import com.cretas.aims.controller.dto.lineage.BatchLineageEdgeDTO;
import com.cretas.aims.controller.dto.lineage.LineageGraph;
import com.cretas.aims.entity.lineage.BatchLineageClosure;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LineageServiceImpl 单元测试 — Phase 1 Sprint 1 Day 6 (2026-05-22).
 *
 * <p>覆盖:
 * <ol>
 *   <li>findOutbound — happy path (返回 edge DTO 列表)</li>
 *   <li>findInbound — happy path</li>
 *   <li>findAncestors — 3-level chain + maxDepth=2 截断深度 3 的记录</li>
 *   <li>findDescendants — 同上反向</li>
 *   <li>findAncestors — empty result 不抛错</li>
 *   <li>buildFullGraph — nodes 去重 + edges 不含 depth=0 self-edge</li>
 *   <li>buildFullGraph — isolated node (空 closure) 仍返查询节点本身</li>
 *   <li>validate — factoryId 空抛 BusinessException</li>
 *   <li>findAncestors with null/0 maxDepth — no limit</li>
 * </ol>
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 6)
 */
@DisplayName("LineageServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class LineageServiceImplTest {

    @Mock
    private BatchLineageEdgeRepository edgeRepository;

    @Mock
    private BatchLineageClosureRepository closureRepository;

    @InjectMocks
    private LineageServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String MATERIAL_TYPE = "MATERIAL_BATCH";
    private static final String PRODUCTION_TYPE = "PRODUCTION_BATCH";
    private static final String FINISHED_TYPE = "FINISHED_BATCH";

    // ====================================================================
    // helpers
    // ====================================================================

    private BatchLineageEdge newEdge(String id, String sourceId, String sourceType,
                                     String targetId, String targetType, String edgeType) {
        BatchLineageEdge e = new BatchLineageEdge();
        e.setId(id);
        e.setFactoryId(FACTORY_ID);
        e.setEdgeType(edgeType);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setQuantityUsed(new BigDecimal("12.5000"));
        e.setUnit("kg");
        e.setEventTime(LocalDateTime.now());
        e.setOperatorId(1001L);
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    private BatchLineageClosure newClosure(String id, String ancestorId, String ancestorType,
                                            String descendantId, String descendantType, int depth) {
        BatchLineageClosure c = new BatchLineageClosure();
        c.setId(id);
        c.setFactoryId(FACTORY_ID);
        c.setAncestorType(ancestorType);
        c.setAncestorId(ancestorId);
        c.setDescendantType(descendantType);
        c.setDescendantId(descendantId);
        c.setDepth(depth);
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    // ====================================================================
    // Test 1: findOutbound — happy path
    // ====================================================================

    @Test
    @DisplayName("findOutbound: 返回该 source 的下游 edges (DTO)")
    void findOutboundReturnsEdgeDTOs() {
        String prodId = "PB-001";
        BatchLineageEdge e1 = newEdge("e1", prodId, PRODUCTION_TYPE,
                "FB-001", FINISHED_TYPE, "PRODUCTION_TO_FINISHED");
        BatchLineageEdge e2 = newEdge("e2", prodId, PRODUCTION_TYPE,
                "FB-002", FINISHED_TYPE, "PRODUCTION_TO_FINISHED");
        when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(
                FACTORY_ID, prodId, PRODUCTION_TYPE))
                .thenReturn(List.of(e1, e2));

        List<BatchLineageEdgeDTO> result = service.findOutbound(FACTORY_ID, prodId, PRODUCTION_TYPE);

        assertEquals(2, result.size());
        assertEquals("e1", result.get(0).getId());
        assertEquals(prodId, result.get(0).getSourceId());
        assertEquals(PRODUCTION_TYPE, result.get(0).getSourceType());
        assertEquals("FB-001", result.get(0).getTargetId());
        assertEquals(FINISHED_TYPE, result.get(0).getTargetType());
        assertEquals("PRODUCTION_TO_FINISHED", result.get(0).getEdgeType());
        assertEquals(new BigDecimal("12.5000"), result.get(0).getQuantityUsed());
        assertEquals("kg", result.get(0).getUnit());
        verify(edgeRepository).findByFactoryIdAndSourceIdAndSourceType(
                FACTORY_ID, prodId, PRODUCTION_TYPE);
    }

    // ====================================================================
    // Test 2: findInbound — happy path
    // ====================================================================

    @Test
    @DisplayName("findInbound: 返回该 target 的上游 edges (DTO)")
    void findInboundReturnsEdgeDTOs() {
        String finishedId = "FB-100";
        BatchLineageEdge e1 = newEdge("e1", "PB-001", PRODUCTION_TYPE,
                finishedId, FINISHED_TYPE, "PRODUCTION_TO_FINISHED");
        when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(
                FACTORY_ID, finishedId, FINISHED_TYPE))
                .thenReturn(List.of(e1));

        List<BatchLineageEdgeDTO> result = service.findInbound(FACTORY_ID, finishedId, FINISHED_TYPE);

        assertEquals(1, result.size());
        assertEquals(finishedId, result.get(0).getTargetId());
        assertEquals("PB-001", result.get(0).getSourceId());
        verify(edgeRepository).findByFactoryIdAndTargetIdAndTargetType(
                FACTORY_ID, finishedId, FINISHED_TYPE);
    }

    // ====================================================================
    // Test 3: findAncestors — 3-level chain, maxDepth=2 截断深度 3
    // ====================================================================

    @Test
    @DisplayName("findAncestors: 3-level chain, maxDepth=2 → 只返 depth=1 和 2 (剔除 depth=3)")
    void findAncestorsRespectsMaxDepth() {
        String finishedId = "FB-200";
        // Repo 返 3 条 (depth 1, 2, 3 — 已按 depth ASC)
        BatchLineageClosure c1 = newClosure("c1", "PB-100", PRODUCTION_TYPE,
                finishedId, FINISHED_TYPE, 1);
        BatchLineageClosure c2 = newClosure("c2", "MB-50", MATERIAL_TYPE,
                finishedId, FINISHED_TYPE, 2);
        BatchLineageClosure c3 = newClosure("c3", "MB-25-RAW", MATERIAL_TYPE,
                finishedId, FINISHED_TYPE, 3);
        when(closureRepository.findAncestors(FACTORY_ID, finishedId, FINISHED_TYPE))
                .thenReturn(List.of(c1, c2, c3));

        List<BatchLineageClosureDTO> result = service.findAncestors(
                FACTORY_ID, finishedId, FINISHED_TYPE, 2);

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getDepth());
        assertEquals(2, result.get(1).getDepth());
        // depth=3 应被截断
        assertTrue(result.stream().noneMatch(d -> d.getDepth() == 3));
    }

    // ====================================================================
    // Test 4: findDescendants — 同上反向
    // ====================================================================

    @Test
    @DisplayName("findDescendants: 3-level chain, maxDepth=2 → 只返 depth=1 和 2")
    void findDescendantsRespectsMaxDepth() {
        String materialId = "MB-001";
        BatchLineageClosure c1 = newClosure("c1", materialId, MATERIAL_TYPE,
                "PB-100", PRODUCTION_TYPE, 1);
        BatchLineageClosure c2 = newClosure("c2", materialId, MATERIAL_TYPE,
                "FB-50", FINISHED_TYPE, 2);
        BatchLineageClosure c3 = newClosure("c3", materialId, MATERIAL_TYPE,
                "SR-25", "SHIPMENT_RECORD", 3);
        when(closureRepository.findDescendants(FACTORY_ID, materialId, MATERIAL_TYPE))
                .thenReturn(List.of(c1, c2, c3));

        List<BatchLineageClosureDTO> result = service.findDescendants(
                FACTORY_ID, materialId, MATERIAL_TYPE, 2);

        assertEquals(2, result.size());
        assertEquals("PB-100", result.get(0).getDescendantId());
        assertEquals("FB-50", result.get(1).getDescendantId());
        assertTrue(result.stream().noneMatch(d -> d.getDepth() == 3));
    }

    // ====================================================================
    // Test 5: findAncestors — empty result 不抛错
    // ====================================================================

    @Test
    @DisplayName("findAncestors: 空结果 (孤立节点) — 返空列表, 不抛错")
    void findAncestorsEmptyDoesNotThrow() {
        String isolated = "MB-ISOLATED";
        when(closureRepository.findAncestors(FACTORY_ID, isolated, MATERIAL_TYPE))
                .thenReturn(Collections.emptyList());

        List<BatchLineageClosureDTO> result = service.findAncestors(
                FACTORY_ID, isolated, MATERIAL_TYPE, 5);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ====================================================================
    // Test 6: buildFullGraph — nodes 去重 + edges 不含 depth=0
    // ====================================================================

    @Test
    @DisplayName("buildFullGraph: nodes 按 (type+id) 去重, edges 不含 depth=0 self-edge")
    void buildFullGraphProducesUniqueNodesAndEdges() {
        String nodeId = "PB-CENTER";
        // closure rows (depth=0 self-ref + depth=1 上游 + depth=1 下游 + depth=2 上游)
        BatchLineageClosure self = newClosure("self", nodeId, PRODUCTION_TYPE,
                nodeId, PRODUCTION_TYPE, 0);
        BatchLineageClosure upstream1 = newClosure("u1", "MB-10", MATERIAL_TYPE,
                nodeId, PRODUCTION_TYPE, 1);
        BatchLineageClosure upstream2 = newClosure("u2", "MB-5", MATERIAL_TYPE,
                nodeId, PRODUCTION_TYPE, 2);
        BatchLineageClosure downstream1 = newClosure("d1", nodeId, PRODUCTION_TYPE,
                "FB-A", FINISHED_TYPE, 1);
        BatchLineageClosure downstream2 = newClosure("d2", nodeId, PRODUCTION_TYPE,
                "FB-B", FINISHED_TYPE, 1);

        when(closureRepository.findFullGraph(FACTORY_ID, nodeId, PRODUCTION_TYPE, 5))
                .thenReturn(List.of(self, upstream1, upstream2, downstream1, downstream2));

        LineageGraph graph = service.buildFullGraph(FACTORY_ID, nodeId, PRODUCTION_TYPE, 5);

        // edges 不含 depth=0 self-edge → 4 条 (u1/u2/d1/d2)
        assertEquals(4, graph.edges().size());
        assertTrue(graph.edges().stream().noneMatch(e -> e.depth() != null && e.depth() == 0));

        // nodes 去重: PB-CENTER (含 self + 上游 desc + 下游 anc) + MB-10 + MB-5 + FB-A + FB-B = 5
        assertEquals(5, graph.nodes().size());
        assertTrue(graph.nodes().stream()
                .anyMatch(n -> "PB-CENTER".equals(n.nodeId()) && PRODUCTION_TYPE.equals(n.nodeType())));
        assertTrue(graph.nodes().stream()
                .anyMatch(n -> "MB-10".equals(n.nodeId())));
        assertTrue(graph.nodes().stream()
                .anyMatch(n -> "FB-B".equals(n.nodeId())));

        // 边正确性 spot-check
        assertTrue(graph.edges().stream()
                .anyMatch(e -> "MB-10".equals(e.ancestorId())
                        && "PB-CENTER".equals(e.descendantId())
                        && e.depth() == 1));
    }

    // ====================================================================
    // Test 7: buildFullGraph — isolated node (空 closure) 仍返查询节点本身
    // ====================================================================

    @Test
    @DisplayName("buildFullGraph: isolated node (closure 空) — nodes 仍含查询节点本身")
    void buildFullGraphIsolatedNodeStillIncludesSelf() {
        String orphanId = "PB-ORPHAN";
        when(closureRepository.findFullGraph(FACTORY_ID, orphanId, PRODUCTION_TYPE, 5))
                .thenReturn(Collections.emptyList());

        LineageGraph graph = service.buildFullGraph(FACTORY_ID, orphanId, PRODUCTION_TYPE, null);

        assertEquals(1, graph.nodes().size());
        assertEquals(orphanId, graph.nodes().get(0).nodeId());
        assertEquals(PRODUCTION_TYPE, graph.nodes().get(0).nodeType());
        assertTrue(graph.edges().isEmpty());
    }

    // ====================================================================
    // Test 8: validate — factoryId/nodeId/nodeType 任一空 → BusinessException
    // ====================================================================

    @Test
    @DisplayName("validate: factoryId/nodeId/nodeType 任一为 null 或空 → BusinessException")
    void validateBlankInputsThrows() {
        assertThrows(BusinessException.class,
                () -> service.findOutbound(null, "n1", "MATERIAL_BATCH"));
        assertThrows(BusinessException.class,
                () -> service.findOutbound(" ", "n1", "MATERIAL_BATCH"));
        assertThrows(BusinessException.class,
                () -> service.findOutbound(FACTORY_ID, null, "MATERIAL_BATCH"));
        assertThrows(BusinessException.class,
                () -> service.findOutbound(FACTORY_ID, "n1", null));
        assertThrows(BusinessException.class,
                () -> service.findAncestors(FACTORY_ID, "", "T", 5));
        assertThrows(BusinessException.class,
                () -> service.buildFullGraph(FACTORY_ID, "n1", "", 5));
    }

    // ====================================================================
    // Test 9: findAncestors with null/0 maxDepth = no limit (extra coverage)
    // ====================================================================

    @Test
    @DisplayName("findAncestors: maxDepth=null/0 视为不限制深度, 全返")
    void findAncestorsNullMaxDepthMeansNoLimit() {
        String descId = "FB-DEEP";
        BatchLineageClosure c1 = newClosure("c1", "P", PRODUCTION_TYPE, descId, FINISHED_TYPE, 1);
        BatchLineageClosure c5 = newClosure("c5", "X", MATERIAL_TYPE, descId, FINISHED_TYPE, 5);
        when(closureRepository.findAncestors(FACTORY_ID, descId, FINISHED_TYPE))
                .thenReturn(List.of(c1, c5));

        List<BatchLineageClosureDTO> noLimit = service.findAncestors(
                FACTORY_ID, descId, FINISHED_TYPE, null);
        assertEquals(2, noLimit.size());

        List<BatchLineageClosureDTO> zeroLimit = service.findAncestors(
                FACTORY_ID, descId, FINISHED_TYPE, 0);
        assertEquals(2, zeroLimit.size());
    }
}
