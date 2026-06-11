package com.cretas.aims.service.production;

import com.cretas.aims.controller.ProductionPlanController;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.orchestration.ProductionWorkflowOrchestrator;
import com.cretas.aims.security.PriceMaskResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SP5 双向检索 — 销售订单↔生产计划.
 *
 * <p>测试 {@link ProductionPlanController} 双向检索端点:
 * <ul>
 *   <li>GET /by-sales-order/{soId} — SO → 计划列表 (exact + JSONB 两路合并去重)</li>
 *   <li>GET /{planId}/source-order-ids — 计划 → SO ID 列表 (含 legacy fallback)</li>
 * </ul>
 *
 * @since SP5 (2026-06-11)
 */
@DisplayName("SP5 双向检索 — 销售订单↔生产计划")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanBidirectionalSearchTest {

    private static final String FACTORY_ID = "F006";
    private static final String SO_A = "SO-SEARCH-001";
    private static final String SO_B = "SO-SEARCH-002";
    private static final String PLAN_ID = "PP-SEARCH-001";

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionPlanService productionPlanService;
    @Mock private MobileService mobileService;
    @Mock private ProductionWorkflowOrchestrator workflowOrchestrator;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private PriceMaskResolver priceMaskResolver;

    @InjectMocks
    private ProductionPlanController controller;

    // ────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────
    private ProductionPlan planWith(String planId, String primarySoId, String... allSoIds) {
        ProductionPlan p = new ProductionPlan();
        p.setId(planId);
        p.setFactoryId(FACTORY_ID);
        p.setSourceOrderId(primarySoId);
        p.setSourceOrderIds(Arrays.asList(allSoIds));
        return p;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 1: SO → 计划 via exact-match
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SO→计划: exact-match path 命中单SO计划")
    void soToPlans_exactMatch_returnsPlan() {
        ProductionPlan plan = planWith(PLAN_ID, SO_A, SO_A);
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdExact(FACTORY_ID, SO_A))
                .thenReturn(Collections.singletonList(plan));
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(
                eq(FACTORY_ID), anyString()))
                .thenReturn(Collections.emptyList());

        ProductionPlanDTO dto = new ProductionPlanDTO();
        when(productionPlanService.toPlanDTO(plan)).thenReturn(dto);

        var response = controller.getPlansBySalesOrder(FACTORY_ID, SO_A);
        assertTrue(Boolean.TRUE.equals(response.getSuccess()), "应成功");
        assertEquals(1, response.getData().size(), "应返回1条计划");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2: SO → 计划 via JSONB containment (extra SO路径)
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SO→计划: JSONB containment 命中合并工单的追加SO")
    void soToPlans_jsonbContainment_returnsMultiSoPlan() {
        ProductionPlan plan = planWith(PLAN_ID, SO_A, SO_A, SO_B);

        // SO_B 不是 primary → exact 不命中
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdExact(FACTORY_ID, SO_B))
                .thenReturn(Collections.emptyList());
        // JSONB 命中
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(
                eq(FACTORY_ID), anyString()))
                .thenReturn(Collections.singletonList(plan));

        ProductionPlanDTO dto = new ProductionPlanDTO();
        when(productionPlanService.toPlanDTO(plan)).thenReturn(dto);

        var response = controller.getPlansBySalesOrder(FACTORY_ID, SO_B);
        assertTrue(Boolean.TRUE.equals(response.getSuccess()));
        assertEquals(1, response.getData().size(), "JSONB path 应找到合并工单");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3: 两路均命中同一计划 → 去重后只返回1条
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("两路均命中同一计划 → 去重后只返回1条")
    void soToPlans_bothPathsHitSamePlan_deduplicated() {
        ProductionPlan plan = planWith(PLAN_ID, SO_A, SO_A, SO_B);

        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdExact(FACTORY_ID, SO_A))
                .thenReturn(Collections.singletonList(plan));
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(
                eq(FACTORY_ID), anyString()))
                .thenReturn(Collections.singletonList(plan)); // same plan via JSONB

        ProductionPlanDTO dto = new ProductionPlanDTO();
        when(productionPlanService.toPlanDTO(plan)).thenReturn(dto);

        var response = controller.getPlansBySalesOrder(FACTORY_ID, SO_A);
        assertTrue(Boolean.TRUE.equals(response.getSuccess()));
        assertEquals(1, response.getData().size(), "相同计划命中两次应去重为1条");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4: 无匹配 → 返回空列表(不是404)
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("无匹配 → 返回空列表(不是404)")
    void soToPlans_noMatch_returnsEmptyList() {
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdExact(FACTORY_ID, "SO-NONE"))
                .thenReturn(Collections.emptyList());
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(
                eq(FACTORY_ID), anyString()))
                .thenReturn(Collections.emptyList());

        var response = controller.getPlansBySalesOrder(FACTORY_ID, "SO-NONE");
        assertTrue(Boolean.TRUE.equals(response.getSuccess()));
        assertTrue(response.getData().isEmpty(), "无匹配应返回空列表");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5: 计划→SO ID列表 (多SO合并工单)
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("计划→SOID列表: 多SO合并工单返回全部SOs")
    void planToSourceOrderIds_multiSo_returnsAll() {
        ProductionPlan plan = planWith(PLAN_ID, SO_A, SO_A, SO_B);

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));

        var response = controller.getSourceOrderIdsByPlan(FACTORY_ID, PLAN_ID);
        assertTrue(Boolean.TRUE.equals(response.getSuccess()), "应成功");
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) response.getData().get("sourceOrderIds");
        assertNotNull(ids);
        assertEquals(2, ids.size(), "应返回2个SO ID");
        assertTrue(ids.contains(SO_A));
        assertTrue(ids.contains(SO_B));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6: 单SO旧计划(sourceOrderIds=null) → fallback到[sourceOrderId]
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("计划→SOID列表: legacy单SO计划(sourceOrderIds=null) → fallback[sourceOrderId]")
    void planToSourceOrderIds_legacySingleSo_fallback() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setSourceOrderId(SO_A);
        plan.setSourceOrderIds(null); // legacy

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));

        var response = controller.getSourceOrderIdsByPlan(FACTORY_ID, PLAN_ID);
        assertTrue(Boolean.TRUE.equals(response.getSuccess()));
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) response.getData().get("sourceOrderIds");
        assertNotNull(ids, "fallback 应返回非null列表");
        assertEquals(1, ids.size());
        assertEquals(SO_A, ids.get(0), "fallback 应返回 [sourceOrderId]");
    }
}
