package com.cretas.aims.ai.tool.impl.lineage;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.lineage.BatchLineageClosure;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Unit tests for {@link LineageQueryTool} (Sprint 11 D4). */
@ExtendWith(MockitoExtension.class)
class LineageQueryToolTest {

    private static final String FACTORY_ID = "F999_MOCK";
    private static final String BATCH_TYPE = "FINISHED_BATCH";
    private static final String BATCH_ID = "RES_3101_009";

    @InjectMocks
    private LineageQueryTool tool;

    @Mock
    private BatchLineageClosureRepository closureRepository;

    @Mock
    private BatchLineageEdgeRepository edgeRepository;

    @Test
    @DisplayName("UT-LQT-01: metadata — READ + LOW + tool_name=lineage_query")
    void metadata() {
        assertEquals("lineage_query", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.READ, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.LOW, tool.getRiskLevel());
        Map<String, Object> schema = tool.getParametersSchema();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertEquals(List.of("batch_type", "batch_id"), required);
    }

    @Test
    @DisplayName("UT-LQT-02: happy path — 1 上游 + 2 下游 + 3 边")
    @SuppressWarnings("unchecked")
    void happyPath() throws Exception {
        // Closure: 1 ancestor (depth=1) + 2 descendants (depth=1 + depth=2)
        BatchLineageClosure anc = closure(
                "MATERIAL_BATCH", "MB-rice-001",
                BATCH_TYPE, BATCH_ID, 1);  // 我是 descendant
        BatchLineageClosure desc1 = closure(
                BATCH_TYPE, BATCH_ID,
                "SHIPMENT_RECORD", "SHP-001", 1);  // 我是 ancestor
        BatchLineageClosure desc2 = closure(
                BATCH_TYPE, BATCH_ID,
                "SHIPMENT_RECORD", "SHP-002", 2);
        // Self-edge depth=0 should be filtered out
        BatchLineageClosure self = closure(BATCH_TYPE, BATCH_ID, BATCH_TYPE, BATCH_ID, 0);

        when(closureRepository.findFullGraph(eq(FACTORY_ID), eq(BATCH_ID), eq(BATCH_TYPE), anyInt()))
                .thenReturn(List.of(self, anc, desc1, desc2));

        BatchLineageEdge upstream = edge("RAW_TO_PRODUCTION", "MATERIAL_BATCH", "MB-rice-001",
                BATCH_TYPE, BATCH_ID, new BigDecimal("50.0"), "kg");
        when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(FACTORY_ID, BATCH_ID, BATCH_TYPE))
                .thenReturn(List.of(upstream));

        BatchLineageEdge downstream = edge("FINISHED_TO_SHIPMENT", BATCH_TYPE, BATCH_ID,
                "SHIPMENT_RECORD", "SHP-001", new BigDecimal("30.0"), "kg");
        when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(FACTORY_ID, BATCH_ID, BATCH_TYPE))
                .thenReturn(List.of(downstream));

        Map<String, Object> result = invoke(Map.of(
                "batch_type", BATCH_TYPE,
                "batch_id", BATCH_ID));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("ancestorCount"));
        assertEquals(2, data.get("descendantCount"));
        assertEquals(2, data.get("directEdgeCount"));
        assertEquals(BATCH_ID, data.get("batchId"));
        assertEquals("BOTH", data.get("direction"));

        List<Map<String, Object>> ancestors = (List<Map<String, Object>>) data.get("ancestors");
        assertEquals("MATERIAL_BATCH", ancestors.get(0).get("nodeType"));
        assertEquals("MB-rice-001", ancestors.get(0).get("nodeId"));
        assertEquals(1, ancestors.get(0).get("depth"));

        List<Map<String, Object>> edges = (List<Map<String, Object>>) data.get("edges");
        assertEquals(2, edges.size());
        assertTrue(edges.stream().anyMatch(e -> "UPSTREAM".equals(e.get("relation"))));
        assertTrue(edges.stream().anyMatch(e -> "DOWNSTREAM".equals(e.get("relation"))));
    }

    @Test
    @DisplayName("UT-LQT-03: direction=ANCESTORS — 仅返上游, 跳过下游")
    @SuppressWarnings("unchecked")
    void ancestorsOnly() throws Exception {
        BatchLineageClosure anc = closure(
                "MATERIAL_BATCH", "MB-rice-001",
                BATCH_TYPE, BATCH_ID, 1);
        BatchLineageClosure desc = closure(
                BATCH_TYPE, BATCH_ID,
                "SHIPMENT_RECORD", "SHP-001", 1);
        when(closureRepository.findFullGraph(eq(FACTORY_ID), eq(BATCH_ID), eq(BATCH_TYPE), anyInt()))
                .thenReturn(List.of(anc, desc));
        lenient().when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(any(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of(
                "batch_type", BATCH_TYPE,
                "batch_id", BATCH_ID,
                "direction", "ANCESTORS"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("ancestorCount"));
        assertEquals(0, data.get("descendantCount"),
                "direction=ANCESTORS 应过滤掉 descendants");
    }

    @Test
    @DisplayName("UT-LQT-04: invalid batch_type → VALIDATION_ERROR")
    @SuppressWarnings("unchecked")
    void invalidBatchType() throws Exception {
        Map<String, Object> result = invoke(Map.of(
                "batch_type", "WHATEVER",
                "batch_id", "X"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("VALIDATION_ERROR", data.get("status"));
        assertEquals("batch_type", data.get("field"));
    }

    @Test
    @DisplayName("UT-LQT-05: invalid direction → VALIDATION_ERROR")
    @SuppressWarnings("unchecked")
    void invalidDirection() throws Exception {
        Map<String, Object> result = invoke(Map.of(
                "batch_type", BATCH_TYPE,
                "batch_id", BATCH_ID,
                "direction", "SIDEWAYS"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("VALIDATION_ERROR", data.get("status"));
        assertEquals("direction", data.get("field"));
    }

    @Test
    @DisplayName("UT-LQT-06: no lineage 数据 → status=NO_LINEAGE_FOUND")
    @SuppressWarnings("unchecked")
    void noLineageFound() throws Exception {
        when(closureRepository.findFullGraph(any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(any(), any(), any()))
                .thenReturn(List.of());
        when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(any(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of(
                "batch_type", BATCH_TYPE,
                "batch_id", "DOES-NOT-EXIST"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("NO_LINEAGE_FOUND", data.get("status"));
        assertTrue(((String) result.get("message")).contains("未找到"));
    }

    @Test
    @DisplayName("UT-LQT-07: max_depth=15 → 自动 cap 到 10")
    @SuppressWarnings("unchecked")
    void maxDepthCap() throws Exception {
        when(closureRepository.findFullGraph(any(), any(), any(), eq(10)))
                .thenReturn(List.of());
        when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(any(), any(), any()))
                .thenReturn(List.of());
        when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(any(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of(
                "batch_type", BATCH_TYPE,
                "batch_id", BATCH_ID,
                "max_depth", 15));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(10, data.get("maxDepth"), "max_depth=15 应被 cap 到 10");
    }

    // ============================================================
    // Helpers
    // ============================================================

    private BatchLineageClosure closure(String ancType, String ancId,
                                        String descType, String descId, int depth) {
        BatchLineageClosure c = new BatchLineageClosure();
        c.setFactoryId(FACTORY_ID);
        c.setAncestorType(ancType);
        c.setAncestorId(ancId);
        c.setDescendantType(descType);
        c.setDescendantId(descId);
        c.setDepth(depth);
        return c;
    }

    private BatchLineageEdge edge(String edgeType, String srcType, String srcId,
                                  String tgtType, String tgtId, BigDecimal qty, String unit) {
        BatchLineageEdge e = new BatchLineageEdge();
        e.setFactoryId(FACTORY_ID);
        e.setEdgeType(edgeType);
        e.setSourceType(srcType);
        e.setSourceId(srcId);
        e.setTargetType(tgtType);
        e.setTargetId(tgtId);
        e.setQuantityUsed(qty);
        e.setUnit(unit);
        e.setEventTime(LocalDateTime.now());
        return e;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Map<String, Object> params) throws Exception {
        Method m = LineageQueryTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        try {
            return (Map<String, Object>) m.invoke(tool, FACTORY_ID, params, ctx);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }
}
