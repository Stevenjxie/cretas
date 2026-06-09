package com.cretas.aims.controller;

import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
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
 * SP12 T8 — PrintController 公单 + 汇总领料单 payload builder 单元测试.
 *
 * <p>直接测试 private helper 方法 (via reflection), 不启动 Spring 容器.
 * 生产测试策略 MVP: payload keys 存在 + 内容合理. Python 模板渲染不在本测试范围.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SP12 T8 — PrintController payload builders")
class PrintControllerSp12T8Test {

    /**
     * 最小化 PrintController stub — 只注入 SP12 T8 必需的两个 optional service.
     * 其他依赖通过 @Autowired(required=false) 不注入, null 安全.
     */
    private PrintController controller;

    private ProductionPlanService productionPlanService;
    private FactoryMaterialRequisitionService factoryMaterialRequisitionService;

    @BeforeEach
    void setUp() {
        productionPlanService = mock(ProductionPlanService.class);
        factoryMaterialRequisitionService = mock(FactoryMaterialRequisitionService.class);

        // PrintController requires 3 constructor args — inject mocks.
        controller = new PrintController(
                mock(RestTemplate.class),
                "http://localhost:8083",
                mock(PriceMaskResolver.class));
        ReflectionTestUtils.setField(controller, "productionPlanService", productionPlanService);
        ReflectionTestUtils.setField(controller, "factoryMaterialRequisitionService",
                factoryMaterialRequisitionService);
    }

    // ==================== buildProductionWorkOrderPayload ====================

    @Test
    @DisplayName("T8-PWO-1: service 可用时 payload 包含计划真实数据")
    void buildProductionWorkOrderPayload_withService_returnsRealData() throws Exception {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-2026-001");
        plan.setProductName("白卤猪舌");
        plan.setProductUnit("kg");
        plan.setPlannedQuantity(new BigDecimal("500.00"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setPlannedDate(LocalDate.of(2026, 6, 10));
        plan.setExpectedCompletionDate(LocalDate.of(2026, 6, 12));

        when(productionPlanService.getProductionPlanById("F006", "plan-abc-001")).thenReturn(plan);

        Map<String, Object> payload = invokeBuildProductionWorkOrderPayload(
                "F006", "plan-abc-001", null);

        assertThat(payload).containsKey("planNumber");
        assertThat(payload.get("planNumber")).isEqualTo("PLAN-2026-001");
        assertThat(payload.get("productName")).isEqualTo("白卤猪舌");
        assertThat(payload.get("productUnit")).isEqualTo("kg");
        assertThat(payload.get("plannedDate").toString()).isEqualTo("2026-06-10");
        assertThat(payload.get("expectedCompletionDate").toString()).isEqualTo("2026-06-12");
        assertThat(payload).containsKey("processes");
        assertThat(payload.get("factoryName").toString()).contains("F006");
    }

    @Test
    @DisplayName("T8-PWO-2: service 抛异常时 payload fallback 到 stub")
    void buildProductionWorkOrderPayload_serviceThrows_fallsBackToStub() throws Exception {
        when(productionPlanService.getProductionPlanById(eq("F006"), any()))
                .thenThrow(new RuntimeException("plan not found"));

        Map<String, Object> payload = invokeBuildProductionWorkOrderPayload(
                "F006", "nonexistent-plan", null);

        // stub 填了 planId 本身作为 planNumber
        assertThat(payload).containsKey("planNumber");
        assertThat(payload.get("planNumber").toString()).isEqualTo("nonexistent-plan");
        assertThat(payload).containsKey("productName");
        assertThat(payload).containsKey("processes");
    }

    @Test
    @DisplayName("T8-PWO-3: service 为 null 时 payload fallback 到 stub")
    void buildProductionWorkOrderPayload_noService_returnsStub() throws Exception {
        // detach service
        ReflectionTestUtils.setField(controller, "productionPlanService", null);

        Map<String, Object> payload = invokeBuildProductionWorkOrderPayload(
                "F006", "plan-xyz", null);

        assertThat(payload).containsKeys("planNumber", "productName", "productUnit",
                "plannedQuantity", "status", "plannedDate", "expectedCompletionDate",
                "processes", "factoryName");
    }

    @Test
    @DisplayName("T8-PWO-4: overrides 参数覆盖 stub 中的默认值")
    void buildProductionWorkOrderPayload_overridesApplied() throws Exception {
        // No service injected → pure stub path, override is visible
        ReflectionTestUtils.setField(controller, "productionPlanService", null);

        Map<String, Object> payload = invokeBuildProductionWorkOrderPayload(
                "F006", "plan-xyz",
                Map.of("productName", "定制猪蹄", "remark", "备注内容"));

        assertThat(payload.get("productName")).isEqualTo("定制猪蹄");
        assertThat(payload.get("remark")).isEqualTo("备注内容");
    }

    // ==================== buildConsolidatedMaterialRequisitionPayload ====================

    @Test
    @DisplayName("T8-CMR-1: 两个 service 都可用时 payload 包含计划名 + 领料数量")
    void buildConsolidatedMaterialRequisitionPayload_withServices_populatesFields()
            throws Exception {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-2026-001");
        plan.setProductName("白卤猪舌");
        when(productionPlanService.getProductionPlanById("F006", "plan-abc-001")).thenReturn(plan);

        FactoryMaterialRequisition req1 = mock(FactoryMaterialRequisition.class);
        FactoryMaterialRequisition req2 = mock(FactoryMaterialRequisition.class);
        when(factoryMaterialRequisitionService.listByPlan("F006", "plan-abc-001"))
                .thenReturn(List.of(req1, req2));

        Map<String, Object> payload = invokeBuildConsolidatedMaterialRequisitionPayload(
                "F006", "plan-abc-001", null);

        assertThat(payload.get("planNumber")).isEqualTo("PLAN-2026-001");
        assertThat(payload.get("productName")).isEqualTo("白卤猪舌");
        assertThat(payload.get("requisitionCount")).isEqualTo(2);
        assertThat(payload).containsKey("items");
        assertThat(payload).containsKey("printDate");
    }

    @Test
    @DisplayName("T8-CMR-2: requisitionService 为 null 时 requisitionCount=0 不崩溃")
    void buildConsolidatedMaterialRequisitionPayload_noRequisitionService_zeroCount()
            throws Exception {
        ReflectionTestUtils.setField(controller, "factoryMaterialRequisitionService", null);

        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-X");
        plan.setProductName("产品X");
        when(productionPlanService.getProductionPlanById("F006", "plan-x")).thenReturn(plan);

        Map<String, Object> payload = invokeBuildConsolidatedMaterialRequisitionPayload(
                "F006", "plan-x", null);

        assertThat(payload.get("requisitionCount")).isEqualTo(0);
        assertThat(payload.get("planNumber")).isEqualTo("PLAN-X");
    }

    @Test
    @DisplayName("T8-CMR-3: planService 抛异常时 planNumber fallback 到 planId")
    void buildConsolidatedMaterialRequisitionPayload_planServiceThrows_fallbackPlanId()
            throws Exception {
        when(productionPlanService.getProductionPlanById(eq("F006"), any()))
                .thenThrow(new RuntimeException("not found"));
        when(factoryMaterialRequisitionService.listByPlan("F006", "bad-plan"))
                .thenReturn(List.of());

        Map<String, Object> payload = invokeBuildConsolidatedMaterialRequisitionPayload(
                "F006", "bad-plan", null);

        assertThat(payload.get("planNumber").toString()).isEqualTo("bad-plan");
        assertThat(payload.get("requisitionCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("T8-CMR-4: 两个 service 全 null 时 stub 数据完整不崩溃")
    void buildConsolidatedMaterialRequisitionPayload_noServices_returnsStub() throws Exception {
        ReflectionTestUtils.setField(controller, "productionPlanService", null);
        ReflectionTestUtils.setField(controller, "factoryMaterialRequisitionService", null);

        Map<String, Object> payload = invokeBuildConsolidatedMaterialRequisitionPayload(
                "F006", "plan-stub", null);

        assertThat(payload).containsKeys("planNumber", "productName", "requisitionCount",
                "items", "factoryName", "printDate");
        assertThat(payload.get("requisitionCount")).isEqualTo(0);
    }

    // ==================== reflection helpers ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeBuildProductionWorkOrderPayload(
            String factoryId, String planId, Map<String, String> overrides) throws Exception {
        Method m = PrintController.class.getDeclaredMethod(
                "buildProductionWorkOrderPayload", String.class, String.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(controller, factoryId, planId, overrides);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeBuildConsolidatedMaterialRequisitionPayload(
            String factoryId, String planId, Map<String, String> overrides) throws Exception {
        Method m = PrintController.class.getDeclaredMethod(
                "buildConsolidatedMaterialRequisitionPayload",
                String.class, String.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(controller, factoryId, planId, overrides);
    }
}
