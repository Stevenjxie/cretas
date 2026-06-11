package com.cretas.aims.controller;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1 #37 — 多 SO 合并公单 payload builder 单元测试.
 *
 * <p>测试 {@code buildMultiSoWorkOrderPayload}, {@code buildSingleOrderEntry},
 * {@code buildMergedProcessList} (via reflection).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P1 #37 — 多 SO 合并公单 payload builder")
class PrintControllerMultiSoTest {

    private PrintController controller;
    private ProductionPlanService productionPlanService;
    private WorkProcessTaskService workProcessTaskService;
    private ProductionBatchRepository productionBatchRepository;

    @BeforeEach
    void setUp() {
        productionPlanService = mock(ProductionPlanService.class);
        workProcessTaskService = mock(WorkProcessTaskService.class);
        productionBatchRepository = mock(ProductionBatchRepository.class);

        controller = new PrintController(
                mock(RestTemplate.class),
                "http://localhost:8083",
                mock(PriceMaskResolver.class));
        ReflectionTestUtils.setField(controller, "productionPlanService", productionPlanService);
        ReflectionTestUtils.setField(controller, "workProcessTaskService", workProcessTaskService);
        ReflectionTestUtils.setField(controller, "productionBatchRepository", productionBatchRepository);
    }

    // ==================== buildMultiSoWorkOrderPayload ====================

    @Test
    @DisplayName("MSO-1: 两个计划 → payload 含 orders[2] + totalOrders=2 + processes + totalQty")
    void buildMultiSo_twoPlans_returnsCorrectPayload() throws Exception {
        // plan A
        ProductionPlanDTO planA = makePlan("PLAN-A", "叮咚猪舌", "kg", new BigDecimal("100"),
                "SO-001", "F006客户A");
        // plan B — 同品
        ProductionPlanDTO planB = makePlan("PLAN-B", "叮咚猪舌", "kg", new BigDecimal("200"),
                "SO-002", "F006客户B");

        when(productionPlanService.getProductionPlanById("F006", "plan-a")).thenReturn(planA);
        when(productionPlanService.getProductionPlanById("F006", "plan-b")).thenReturn(planB);

        // no batches for processes (empty OK —诚实)
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(eq("F006"), any()))
                .thenReturn(List.of());

        Map<String, Object> payload = invokeMultiSo("F006", List.of("plan-a", "plan-b"), null);

        assertThat(payload.get("totalOrders")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) payload.get("orders");
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).get("planNumber")).isEqualTo("PLAN-A");
        assertThat(orders.get(0).get("sourceOrderId")).isEqualTo("SO-001");
        assertThat(orders.get(0).get("customerName")).isEqualTo("F006客户A");
        assertThat(orders.get(1).get("planNumber")).isEqualTo("PLAN-B");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> qtyByProduct =
                (List<Map<String, Object>>) payload.get("totalQuantityByProduct");
        assertThat(qtyByProduct).hasSize(1);  // 同品合并为一行
        assertThat(qtyByProduct.get(0).get("productName")).isEqualTo("叮咚猪舌");
        assertThat(qtyByProduct.get(0).get("totalQty").toString()).isEqualTo("300");

        assertThat(payload).containsKey("processes");
        assertThat(payload).containsKey("printDate");
    }

    @Test
    @DisplayName("MSO-2: 不同品名 → totalQuantityByProduct 两行")
    void buildMultiSo_differentProducts_separateTotalQtyRows() throws Exception {
        ProductionPlanDTO planA = makePlan("PLAN-A", "猪舌", "kg", new BigDecimal("100"),
                "SO-001", "客户A");
        ProductionPlanDTO planB = makePlan("PLAN-B", "牛腱", "kg", new BigDecimal("50"),
                "SO-002", "客户B");

        when(productionPlanService.getProductionPlanById("F006", "pa")).thenReturn(planA);
        when(productionPlanService.getProductionPlanById("F006", "pb")).thenReturn(planB);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(eq("F006"), any()))
                .thenReturn(List.of());

        Map<String, Object> payload = invokeMultiSo("F006", List.of("pa", "pb"), null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> qtyByProduct =
                (List<Map<String, Object>>) payload.get("totalQuantityByProduct");
        assertThat(qtyByProduct).hasSize(2);
    }

    @Test
    @DisplayName("MSO-3: 一个 planId 不存在 → orders[] 只含存在的计划, totalOrders=1")
    void buildMultiSo_onePlanMissing_skipsGracefully() throws Exception {
        ProductionPlanDTO planA = makePlan("PLAN-A", "猪舌", "kg", new BigDecimal("100"),
                "SO-001", "客户A");
        when(productionPlanService.getProductionPlanById("F006", "plan-a")).thenReturn(planA);
        when(productionPlanService.getProductionPlanById("F006", "bad-plan"))
                .thenThrow(new RuntimeException("not found"));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(eq("F006"), any()))
                .thenReturn(List.of());

        Map<String, Object> payload = invokeMultiSo("F006", List.of("plan-a", "bad-plan"), null);

        assertThat(payload.get("totalOrders")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) payload.get("orders");
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).get("planNumber")).isEqualTo("PLAN-A");
    }

    @Test
    @DisplayName("MSO-4: service 未注入 → stub entry 返回, payload 不崩溃")
    void buildMultiSo_noService_returnsStubEntries() throws Exception {
        ReflectionTestUtils.setField(controller, "productionPlanService", null);
        ReflectionTestUtils.setField(controller, "workProcessTaskService", null);
        ReflectionTestUtils.setField(controller, "productionBatchRepository", null);

        Map<String, Object> payload = invokeMultiSo("F006", List.of("p1", "p2"), null);

        assertThat(payload.get("totalOrders")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) payload.get("orders");
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).get("planNumber")).isEqualTo("p1");  // stub: planId as planNumber
        assertThat(orders.get(1).get("planNumber")).isEqualTo("p2");
    }

    @Test
    @DisplayName("MSO-5: 工序去重 — 两计划相同工序名只保留首次出现")
    void buildMultiSo_duplicateProcessNames_deduplicated() throws Exception {
        ProductionPlanDTO planA = makePlan("PLAN-A", "猪舌", "kg", BigDecimal.ONE, "SO-A", "客户");
        ProductionPlanDTO planB = makePlan("PLAN-B", "猪舌", "kg", BigDecimal.ONE, "SO-B", "客户");
        when(productionPlanService.getProductionPlanById("F006", "pa")).thenReturn(planA);
        when(productionPlanService.getProductionPlanById("F006", "pb")).thenReturn(planB);

        // 两个计划的批次各有相同工序名 "领料" + "腌制"
        ProductionBatch batchA = mock(ProductionBatch.class);
        when(batchA.getId()).thenReturn(101L);
        ProductionBatch batchB = mock(ProductionBatch.class);
        when(batchB.getId()).thenReturn(102L);

        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "pa"))
                .thenReturn(List.of(batchA));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "pb"))
                .thenReturn(List.of(batchB));

        WorkProcessTaskDTO t1 = makeTask(1, "领料", 30, null);
        WorkProcessTaskDTO t2 = makeTask(2, "腌制", 120, "张三");
        // same tasks on both batches
        when(workProcessTaskService.listByBatch("F006", 101L)).thenReturn(List.of(t1, t2));
        when(workProcessTaskService.listByBatch("F006", 102L)).thenReturn(List.of(t1, t2));

        Map<String, Object> payload = invokeMultiSo("F006", List.of("pa", "pb"), null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> processes = (List<Map<String, Object>>) payload.get("processes");
        // 去重: 两批次各 2 工序, 但名字相同 → 只保留 2 而非 4
        assertThat(processes).hasSize(2);
        assertThat(processes.get(0).get("name")).isEqualTo("领料");
        assertThat(processes.get(1).get("name")).isEqualTo("腌制");
    }

    @Test
    @DisplayName("MSO-6: remark override 正确传递")
    void buildMultiSo_remarkOverride_passedThrough() throws Exception {
        ReflectionTestUtils.setField(controller, "productionPlanService", null);

        Map<String, Object> payload = invokeMultiSo("F006", List.of("p1"),
                Map.of("remark", "急单优先"));

        assertThat(payload.get("remark")).isEqualTo("急单优先");
    }

    // ==================== helpers ====================

    private ProductionPlanDTO makePlan(String planNumber, String productName, String unit,
            BigDecimal qty, String sourceOrderId, String customerName) {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber(planNumber);
        plan.setProductName(productName);
        plan.setProductUnit(unit);
        plan.setPlannedQuantity(qty);
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setPlannedDate(LocalDate.of(2026, 6, 10));
        plan.setExpectedCompletionDate(LocalDate.of(2026, 6, 15));
        plan.setSourceOrderId(sourceOrderId);
        plan.setSourceCustomerName(customerName);
        return plan;
    }

    private WorkProcessTaskDTO makeTask(int order, String name, int minutes, String assignedToName) {
        WorkProcessTaskDTO t = new WorkProcessTaskDTO();
        t.setProcessOrder(order);
        t.setProcessName(name);
        t.setEstimatedMinutes(minutes);
        t.setAssignedToName(assignedToName);
        return t;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeMultiSo(
            String factoryId, List<String> planIds, Map<String, String> overrides) throws Exception {
        Method m = PrintController.class.getDeclaredMethod(
                "buildMultiSoWorkOrderPayload", String.class, List.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(controller, factoryId, planIds, overrides);
    }
}
