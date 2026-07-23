package com.cretas.aims.controller;

import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private WorkProcessTaskService workProcessTaskService;
    private ProductionBatchRepository productionBatchRepository;
    private BomRecipeRepository bomRecipeRepository;
    private BomRecipeItemRepository bomRecipeItemRepository;
    private BomSeasoningItemRepository bomSeasoningItemRepository;
    private BomWorkflowRevisionService bomWorkflowRevisionService;
    private WorkProcessRepository workProcessRepository;

    @BeforeEach
    void setUp() {
        productionPlanService = mock(ProductionPlanService.class);
        factoryMaterialRequisitionService = mock(FactoryMaterialRequisitionService.class);
        workProcessTaskService = mock(WorkProcessTaskService.class);
        productionBatchRepository = mock(ProductionBatchRepository.class);
        bomRecipeRepository = mock(BomRecipeRepository.class);
        bomRecipeItemRepository = mock(BomRecipeItemRepository.class);
        bomSeasoningItemRepository = mock(BomSeasoningItemRepository.class);
        bomWorkflowRevisionService = mock(BomWorkflowRevisionService.class);
        workProcessRepository = mock(WorkProcessRepository.class);

        // PrintController requires 3 constructor args — inject mocks.
        controller = new PrintController(
                mock(RestTemplate.class),
                "http://localhost:8083",
                mock(PriceMaskResolver.class));
        ReflectionTestUtils.setField(controller, "productionPlanService", productionPlanService);
        ReflectionTestUtils.setField(controller, "factoryMaterialRequisitionService",
                factoryMaterialRequisitionService);
        ReflectionTestUtils.setField(controller, "workProcessTaskService", workProcessTaskService);
        ReflectionTestUtils.setField(controller, "productionBatchRepository", productionBatchRepository);
        ReflectionTestUtils.setField(controller, "bomRecipeRepository", bomRecipeRepository);
        ReflectionTestUtils.setField(controller, "bomRecipeItemRepository", bomRecipeItemRepository);
        ReflectionTestUtils.setField(controller, "bomSeasoningItemRepository", bomSeasoningItemRepository);
        ReflectionTestUtils.setField(controller, "bomWorkflowRevisionService", bomWorkflowRevisionService);
        ReflectionTestUtils.setField(controller, "workProcessRepository", workProcessRepository);
    }

    // ==================== buildProductionWorkOrderPayload ====================

    @Test
    @DisplayName("T8-PWO-1: service 可用时 payload 包含计划真实数据 (含 N5 抬头字段)")
    void buildProductionWorkOrderPayload_withService_returnsRealData() throws Exception {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-2026-001");
        plan.setProductName("白卤猪舌");
        plan.setProductUnit("kg");
        plan.setPlannedQuantity(new BigDecimal("500.00"));
        plan.setCustomerOrderNumber("SO-20260612-001");
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setPlannedDate(LocalDate.of(2026, 6, 10));
        plan.setExpectedCompletionDate(LocalDate.of(2026, 6, 12));
        // N5: 制单人 + 客户名称
        plan.setCreatedBy(2001L);
        plan.setCreatedByName("计划文员");
        plan.setSourceCustomerName("六扇门餐饮");

        when(productionPlanService.getProductionPlanById("F006", "plan-abc-001")).thenReturn(plan);

        Map<String, Object> payload = invokeBuildProductionWorkOrderPayload(
                "F006", "plan-abc-001", null);

        assertThat(payload).containsKey("planNumber");
        assertThat(payload.get("planNumber")).isEqualTo("PLAN-2026-001");
        assertThat(payload.get("productName")).isEqualTo("白卤猪舌");
        assertThat(payload.get("productUnit")).isEqualTo("kg");
        assertThat(payload.get("salesOrderNumbers")).isEqualTo("SO-20260612-001");
        assertThat(payload.get("productionOrderNumber")).isEqualTo("PLAN-2026-001");
        assertThat(payload.get("productionDate").toString()).isEqualTo("2026-06-10");
        assertThat(payload.get("printDate")).isNotNull();
        assertThat(payload.get("printedBy")).isEqualTo("-");
        assertThat(payload.get("printedAccount")).isEqualTo("-");
        assertThat(payload.get("plannedDate").toString()).isEqualTo("2026-06-10");
        assertThat(payload.get("expectedCompletionDate").toString()).isEqualTo("2026-06-12");
        // N5 新字段断言
        assertThat(payload.get("deliveryDate").toString()).isEqualTo("2026-06-12");
        assertThat(payload.get("customerName")).isEqualTo("六扇门餐饮");
        assertThat(payload.get("createdBy")).isEqualTo("2001");
        assertThat(payload.get("createdByName")).isEqualTo("计划文员");
        assertThat(payload.get("preparedBy")).isEqualTo("计划文员");
        assertThat(payload).containsKey("processes");
        assertThat(payload).containsKey("materialItems");
        assertThat(payload.get("factoryName").toString()).contains("F006");
    }

    @Test
    @DisplayName("T8-PWO-2: service 抛异常时不伪造 stub, 直接返回业务错误")
    void buildProductionWorkOrderPayload_serviceThrows_throwsBusinessException() {
        when(productionPlanService.getProductionPlanById(eq("F006"), any()))
                .thenThrow(new RuntimeException("plan not found"));

        assertThatThrownBy(() -> invokeBuildProductionWorkOrderPayload("F006", "nonexistent-plan", null))
                .hasCauseInstanceOf(BusinessException.class)
                .hasRootCauseMessage("生产计划不存在或不可访问 — 无法生成生产工单: nonexistent-plan");
    }

    @Test
    @DisplayName("T8-PWO-3: service 为 null 时不伪造 stub, 直接返回服务不可用")
    void buildProductionWorkOrderPayload_noService_throwsBusinessException() {
        // detach service
        ReflectionTestUtils.setField(controller, "productionPlanService", null);

        assertThatThrownBy(() -> invokeBuildProductionWorkOrderPayload("F006", "plan-xyz", null))
                .hasCauseInstanceOf(BusinessException.class)
                .hasRootCauseMessage("生产计划服务不可用 — 无法生成真实生产工单");
    }

    @Test
    @DisplayName("T8-PWO-4: overrides 仅允许补备注, 不覆盖真实计划字段")
    void buildProductionWorkOrderPayload_overridesDoNotReplacePlanData() throws Exception {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-REAL");
        plan.setProductName("真实产品");
        plan.setProductUnit("kg");
        plan.setPlannedQuantity(BigDecimal.TEN);
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setPlannedDate(LocalDate.of(2026, 6, 12));
        when(productionPlanService.getProductionPlanById("F006", "plan-real")).thenReturn(plan);

        Map<String, Object> payload = invokeBuildProductionWorkOrderPayload(
                "F006", "plan-real",
                Map.of("productName", "定制猪蹄", "remark", "备注内容"));

        assertThat(payload.get("productName")).isEqualTo("真实产品");
        assertThat(payload.get("remark")).isEqualTo("备注内容");
    }

    @Test
    @DisplayName("T8-PWO-5: batch + task service 可用时 processes 包含真实工序行")
    void buildProductionWorkOrderPayload_withTasks_processesPopulated() throws Exception {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-2026-002");
        plan.setProductName("白卤猪蹄");
        plan.setProductUnit("kg");
        plan.setPlannedQuantity(new BigDecimal("200.00"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setPlannedDate(LocalDate.of(2026, 6, 11));
        plan.setExpectedCompletionDate(LocalDate.of(2026, 6, 13));
        when(productionPlanService.getProductionPlanById("F006", "plan-pork-001")).thenReturn(plan);

        // Mock a batch associated with the plan
        ProductionBatch batch = mock(ProductionBatch.class);
        when(batch.getId()).thenReturn(1001L);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "plan-pork-001"))
                .thenReturn(List.of(batch));

        // Mock two work process tasks
        WorkProcessTaskDTO t1 = new WorkProcessTaskDTO();
        t1.setProcessOrder(1);
        t1.setProcessName("清洗分切");
        t1.setEstimatedMinutes(60);
        t1.setAssignedToName("张伟");

        WorkProcessTaskDTO t2 = new WorkProcessTaskDTO();
        t2.setProcessOrder(2);
        t2.setProcessName("卤制");
        t2.setEstimatedMinutes(90);
        t2.setAssignedToName(null); // unassigned

        when(workProcessTaskService.listByBatch("F006", 1001L)).thenReturn(List.of(t1, t2));

        Map<String, Object> payload = invokeBuildProductionWorkOrderPayload(
                "F006", "plan-pork-001", null);

        assertThat(payload.get("productName")).isEqualTo("白卤猪蹄");
        assertThat(payload).containsKey("processes");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> processes = (List<Map<String, Object>>) payload.get("processes");
        assertThat(processes).hasSize(2);

        Map<String, Object> proc1 = processes.get(0);
        assertThat(proc1.get("seq")).isEqualTo(1);
        assertThat(proc1.get("name")).isEqualTo("清洗分切");
        assertThat(proc1.get("standardHours")).isEqualTo("1.0");
        assertThat(proc1.get("operator")).isEqualTo("张伟");

        Map<String, Object> proc2 = processes.get(1);
        assertThat(proc2.get("seq")).isEqualTo(2);
        assertThat(proc2.get("name")).isEqualTo("卤制");
        assertThat(proc2.get("standardHours")).isEqualTo("1.5");
        assertThat(proc2.get("operator")).isNull(); // unassigned → null
    }

    @Test
    @DisplayName("T8-PWO-6: batch 有但无工序任务时 processes 为空列表 (诚实空)")
    void buildProductionWorkOrderPayload_batchWithNoTasks_emptyProcesses() throws Exception {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-EMPTY");
        plan.setProductName("测试产品");
        plan.setProductUnit("kg");
        plan.setPlannedQuantity(BigDecimal.TEN);
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setPlannedDate(LocalDate.of(2026, 6, 11));
        plan.setExpectedCompletionDate(LocalDate.of(2026, 6, 12));
        when(productionPlanService.getProductionPlanById("F006", "plan-empty")).thenReturn(plan);

        ProductionBatch batch = mock(ProductionBatch.class);
        when(batch.getId()).thenReturn(2001L);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "plan-empty"))
                .thenReturn(List.of(batch));
        when(workProcessTaskService.listByBatch("F006", 2001L)).thenReturn(List.of());

        Map<String, Object> payload = invokeBuildProductionWorkOrderPayload(
                "F006", "plan-empty", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> processes = (List<Map<String, Object>>) payload.get("processes");
        assertThat(processes).isEmpty();
    }

    @Test
    @DisplayName("T8-PWO-7: workProcessTaskService null 时 processes 为空列表 (不崩溃)")
    void buildProductionWorkOrderPayload_noTaskService_emptyProcesses() throws Exception {
        ReflectionTestUtils.setField(controller, "workProcessTaskService", null);
        ReflectionTestUtils.setField(controller, "productionBatchRepository", null);
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-NO-TASK-SERVICE");
        plan.setProductName("测试产品");
        plan.setProductUnit("kg");
        plan.setPlannedQuantity(BigDecimal.TEN);
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setPlannedDate(LocalDate.of(2026, 6, 12));
        when(productionPlanService.getProductionPlanById("F006", "plan-xyz")).thenReturn(plan);

        Map<String, Object> payload = invokeBuildProductionWorkOrderPayload(
                "F006", "plan-xyz", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> processes = (List<Map<String, Object>>) payload.get("processes");
        assertThat(processes).isEmpty();
    }

    @Test
    @DisplayName("新建计划尚无任务/领料单时使用固定 Workflow 与 BOM 生成诚实参考内容")
    void buildProductionWorkOrderPayload_beforeMaterialization_usesPinnedSnapshots() throws Exception {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-F006-REF");
        plan.setProductTypeId("PRODUCT-F006");
        plan.setProductName("SOP-20260723-01-黄油鸡-成品800g");
        plan.setProductUnit("box");
        plan.setPlannedQuantity(new BigDecimal("10"));
        plan.setSelectedBomRecipeId("BOM-F006");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setPlannedDate(LocalDate.of(2026, 7, 24));
        when(productionPlanService.getProductionPlanById("F006", "plan-f006")).thenReturn(plan);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "plan-f006"))
                .thenReturn(List.of());
        when(factoryMaterialRequisitionService.listByPlan("F006", "plan-f006"))
                .thenReturn(List.of());

        BomRecipe recipe = new BomRecipe();
        recipe.setId("BOM-F006");
        recipe.setFactoryId("F006");
        recipe.setProductTypeId("PRODUCT-F006");
        when(bomRecipeRepository.findById("BOM-F006")).thenReturn(Optional.of(recipe));
        when(bomWorkflowRevisionService.resolvePinnedGraph("F006", recipe)).thenReturn(
                new PinnedWorkflowGraph(
                        25L, 112L, 1, "hash", "PRODUCT-F006", "finished",
                        List.of("RAW-A"),
                        List.of(
                                new PinnedWorkflowGraph.ProcessStep("process-1", "WP-RAW", 1),
                                new PinnedWorkflowGraph.ProcessStep("process-2", "WP-PACK", 2)),
                        List.of(), List.of()));

        WorkProcess rawProcess = new WorkProcess();
        rawProcess.setId("WP-RAW");
        rawProcess.setFactoryId("F006");
        rawProcess.setProcessName("SOP-20260723-01-黄油鸡-原料处理");
        rawProcess.setEstimatedMinutes(60);
        WorkProcess packProcess = new WorkProcess();
        packProcess.setId("WP-PACK");
        packProcess.setFactoryId("F006");
        packProcess.setProcessName("SOP-20260723-01-黄油鸡-定量包装");
        when(workProcessRepository.findByFactoryIdAndIdIn(
                "F006", List.of("WP-RAW", "WP-PACK")))
                .thenReturn(List.of(rawProcess, packProcess));

        BomRecipeItem raw = new BomRecipeItem();
        raw.setRecipeId("BOM-F006");
        raw.setFactoryId("F006");
        raw.setMaterialTypeId("RAW-A");
        raw.setMaterialName("黄油鸡原料A");
        raw.setMaterialCategory("RAW");
        raw.setUnit("kg");

        BomRecipeItem carton = new BomRecipeItem();
        carton.setRecipeId("BOM-F006");
        carton.setFactoryId("F006");
        carton.setMaterialTypeId("PK-CARTON");
        carton.setMaterialName("黄油鸡外箱");
        carton.setMaterialCategory("PACKAGING");
        carton.setUnit("case");
        carton.setStandardQuantity(new BigDecimal("0.125"));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc("BOM-F006"))
                .thenReturn(List.of(raw, carton));
        when(bomSeasoningItemRepository.findByRecipeIdOrderBySeqAsc("BOM-F006"))
                .thenReturn(List.of());

        Map<String, Object> payload =
                invokeBuildProductionWorkOrderPayload("F006", "plan-f006", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> processes =
                (List<Map<String, Object>>) payload.get("processes");
        assertThat(processes).extracting(row -> row.get("name")).containsExactly(
                "SOP-20260723-01-黄油鸡-原料处理",
                "SOP-20260723-01-黄油鸡-定量包装");
        assertThat(payload.get("processDataStatus").toString()).contains("固定 Workflow");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> materials =
                (List<Map<String, Object>>) payload.get("materialItems");
        assertThat(materials).hasSize(2);
        assertThat(materials.get(0))
                .containsEntry("category", "原料")
                .containsEntry("plannedRawQty", "计划投料待填写");
        assertThat(materials.get(1))
                .containsEntry("category", "包材")
                .containsEntry("totalQty", "1.25");
        assertThat(payload.get("materialDataStatus").toString()).contains("固定 BOM");
    }

    @Test
    @DisplayName("N5-PWO-8: 生产工单 payload 带原料/辅料/半成品报名值分列和实际领用列")
    void buildProductionWorkOrderPayload_materialItems_splitByCategory() throws Exception {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-MAT-001");
        plan.setCustomerOrderNumber("SO-MAT-001");
        plan.setProductName("白卤牛腱");
        plan.setProductUnit("kg");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setPlannedDate(LocalDate.of(2026, 6, 12));
        when(productionPlanService.getProductionPlanById("F006", "plan-mat")).thenReturn(plan);

        FactoryMaterialRequisitionItem raw = new FactoryMaterialRequisitionItem();
        raw.setMaterialTypeId("mat-beef");
        raw.setMaterialName("牛腱");
        raw.setUnit("kg");
        raw.setRequiredQty(new BigDecimal("80"));
        raw.setMaterialCategory(FactoryMaterialRequisitionItem.MaterialCategory.RAW);

        FactoryMaterialRequisitionItem aux = new FactoryMaterialRequisitionItem();
        aux.setMaterialTypeId("mat-spice");
        aux.setMaterialName("香辛料包");
        aux.setUnit("袋");
        aux.setRequiredQty(new BigDecimal("5"));
        aux.setConsumedQty(new BigDecimal("4"));
        aux.setMaterialCategory(FactoryMaterialRequisitionItem.MaterialCategory.AUXILIARY);

        // N5: 半成品行
        FactoryMaterialRequisitionItem semi = new FactoryMaterialRequisitionItem();
        semi.setMaterialTypeId("mat-wip-soup");
        semi.setMaterialName("老卤汤");
        semi.setUnit("kg");
        semi.setRequiredQty(new BigDecimal("12.5"));
        semi.setMaterialCategory(FactoryMaterialRequisitionItem.MaterialCategory.SEMI_FINISHED);

        FactoryMaterialRequisition req = mock(FactoryMaterialRequisition.class);
        when(req.getItems()).thenReturn(List.of(raw, aux, semi));
        when(factoryMaterialRequisitionService.listByPlan("F006", "plan-mat")).thenReturn(List.of(req));

        Map<String, Object> payload = invokeBuildProductionWorkOrderPayload("F006", "plan-mat", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> materialItems = (List<Map<String, Object>>) payload.get("materialItems");
        assertThat(materialItems).hasSize(3);

        Map<String, Object> rawRow = materialItems.stream()
                .filter(r -> "牛腱".equals(r.get("materialName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("牛腱 row not found"));
        assertThat(rawRow.get("category")).isEqualTo("原料");
        assertThat(rawRow.get("plannedRawQty")).isEqualTo("80");
        assertThat(rawRow.get("plannedAuxiliaryQty")).isEqualTo("");
        assertThat(rawRow.get("plannedSemiFinishedQty")).isEqualTo("");
        assertThat(rawRow.get("actualUsedQty")).isEqualTo("________");

        Map<String, Object> auxRow = materialItems.stream()
                .filter(r -> "香辛料包".equals(r.get("materialName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("香辛料包 row not found"));
        assertThat(auxRow.get("category")).isEqualTo("辅料");
        assertThat(auxRow.get("plannedRawQty")).isEqualTo("");
        assertThat(auxRow.get("plannedAuxiliaryQty")).isEqualTo("5");
        assertThat(auxRow.get("actualUsedQty")).isEqualTo("4");

        // N5: 半成品行断言
        Map<String, Object> semiRow = materialItems.stream()
                .filter(r -> "老卤汤".equals(r.get("materialName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("老卤汤 row not found"));
        assertThat(semiRow.get("category")).isEqualTo("半成品");
        assertThat(semiRow.get("plannedRawQty")).isEqualTo("");
        assertThat(semiRow.get("plannedAuxiliaryQty")).isEqualTo("");
        assertThat(semiRow.get("plannedSemiFinishedQty")).isEqualTo("12.5");
        assertThat(semiRow.get("actualUsedQty")).isEqualTo("________");
    }

    // ==================== buildConsolidatedMaterialRequisitionPayload ====================

    @Test
    @DisplayName("T8-CMR-1: 两个 service 都可用, items 包含汇总领料明细行")
    void buildConsolidatedMaterialRequisitionPayload_withServices_populatesItemsAndCount()
            throws Exception {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-2026-001");
        plan.setProductName("白卤猪舌");
        when(productionPlanService.getProductionPlanById("F006", "plan-abc-001")).thenReturn(plan);

        // Requisition 1 with 2 items
        FactoryMaterialRequisitionItem item1 = new FactoryMaterialRequisitionItem();
        item1.setMaterialTypeId("mat-salt");
        item1.setMaterialName("食盐");
        item1.setUnit("kg");
        item1.setRequiredQty(new BigDecimal("10.500"));
        item1.setConsumedQty(new BigDecimal("9.000"));
        item1.setMaterialCategory(FactoryMaterialRequisitionItem.MaterialCategory.AUXILIARY);

        FactoryMaterialRequisitionItem item2 = new FactoryMaterialRequisitionItem();
        item2.setMaterialTypeId("mat-pork-tongue");
        item2.setMaterialName("猪舌");
        item2.setUnit("kg");
        item2.setRequiredQty(new BigDecimal("200.000"));
        item2.setConsumedQty(new BigDecimal("198.000"));
        item2.setMaterialCategory(FactoryMaterialRequisitionItem.MaterialCategory.RAW);

        FactoryMaterialRequisition req1 = mock(FactoryMaterialRequisition.class);
        when(req1.getItems()).thenReturn(List.of(item1, item2));

        // Requisition 2 with same material types (cross-batch aggregation)
        FactoryMaterialRequisitionItem item3 = new FactoryMaterialRequisitionItem();
        item3.setMaterialTypeId("mat-salt");
        item3.setMaterialName("食盐");
        item3.setUnit("kg");
        item3.setRequiredQty(new BigDecimal("5.000"));
        item3.setConsumedQty(new BigDecimal("4.500"));
        item3.setMaterialCategory(FactoryMaterialRequisitionItem.MaterialCategory.AUXILIARY);

        FactoryMaterialRequisition req2 = mock(FactoryMaterialRequisition.class);
        when(req2.getItems()).thenReturn(List.of(item3));

        when(factoryMaterialRequisitionService.listByPlan("F006", "plan-abc-001"))
                .thenReturn(List.of(req1, req2));

        Map<String, Object> payload = invokeBuildConsolidatedMaterialRequisitionPayload(
                "F006", "plan-abc-001", null);

        assertThat(payload.get("planNumber")).isEqualTo("PLAN-2026-001");
        assertThat(payload.get("productName")).isEqualTo("白卤猪舌");
        assertThat(payload.get("requisitionCount")).isEqualTo(2);
        assertThat(payload).containsKey("items");
        assertThat(payload).containsKey("printDate");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        // 2 unique material types: mat-salt (10.5 + 5 = 15.5), mat-pork-tongue (200)
        assertThat(items).hasSize(2);

        // Find salt row (aggregated across 2 requisitions)
        Map<String, Object> saltRow = items.stream()
                .filter(r -> "食盐".equals(r.get("materialName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("食盐 row not found"));
        assertThat(saltRow.get("totalQty")).isEqualTo("15.5");
        assertThat(saltRow.get("category")).isEqualTo("辅料");
        assertThat(saltRow.get("plannedRawQty")).isEqualTo("");
        assertThat(saltRow.get("plannedAuxiliaryQty")).isEqualTo("15.5");
        assertThat(saltRow.get("plannedSemiFinishedQty")).isEqualTo("");
        assertThat(saltRow.get("actualUsedQty")).isEqualTo("13.5");
        assertThat(saltRow.get("unit")).isEqualTo("kg");

        // Find pork tongue row
        Map<String, Object> tongueRow = items.stream()
                .filter(r -> "猪舌".equals(r.get("materialName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("猪舌 row not found"));
        assertThat(tongueRow.get("totalQty")).isEqualTo("200");
        assertThat(tongueRow.get("category")).isEqualTo("原料");
        assertThat(tongueRow.get("plannedRawQty")).isEqualTo("200");
        assertThat(tongueRow.get("plannedAuxiliaryQty")).isEqualTo("");
        assertThat(tongueRow.get("plannedSemiFinishedQty")).isEqualTo("");
        assertThat(tongueRow.get("actualUsedQty")).isEqualTo("198");
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
