package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrintControllerProductionDocumentPackageTest {

    private PrintController controller;
    private ProductionPlanService productionPlanService;
    private FactoryMaterialRequisitionService requisitionService;
    private WorkProcessTaskService taskService;
    private ProductionBatchRepository batchRepository;
    private ProductTypeRepository productTypeRepository;

    @BeforeEach
    void setUp() {
        productionPlanService = mock(ProductionPlanService.class);
        requisitionService = mock(FactoryMaterialRequisitionService.class);
        taskService = mock(WorkProcessTaskService.class);
        batchRepository = mock(ProductionBatchRepository.class);
        productTypeRepository = mock(ProductTypeRepository.class);

        controller = new PrintController(
                mock(RestTemplate.class),
                "http://localhost:8083",
                mock(PriceMaskResolver.class));
        ReflectionTestUtils.setField(controller, "productionPlanService", productionPlanService);
        ReflectionTestUtils.setField(controller, "factoryMaterialRequisitionService", requisitionService);
        ReflectionTestUtils.setField(controller, "workProcessTaskService", taskService);
        ReflectionTestUtils.setField(controller, "productionBatchRepository", batchRepository);
        ReflectionTestUtils.setField(controller, "productTypeRepository", productTypeRepository);
    }

    @Test
    void packageUsesOnePinnedSnapshotForAllThreeIndependentSections() throws Exception {
        stubCompletePlan();

        Map<String, Object> payload = buildPackage(List.of(
                "work-order", "material-requisition", "batching-sheet"));

        assertThat(payload)
                .containsEntry("planNumber", "PLAN-001")
                .containsEntry("sku", "CPF0060015")
                .containsEntry("bomRecipeId", "BOM-RECIPE-1")
                .containsEntry("bomVersion", 1)
                .containsEntry("workflowId", 105L)
                .containsEntry("workflowVersion", 1)
                .containsKeys("workOrder", "materialRequisition", "batchingSheet");

        for (String key : List.of("workOrder", "materialRequisition", "batchingSheet")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> section = (Map<String, Object>) payload.get(key);
            assertThat(section)
                    .containsEntry("planNumber", "PLAN-001")
                    .containsEntry("productTypeId", "PRODUCT-1")
                    .containsEntry("bomRecipeId", "BOM-RECIPE-1")
                    .containsEntry("bomVersion", 1)
                    .containsEntry("workflowId", 105L)
                    .containsEntry("workflowVersion", 1)
                    .containsEntry("batchDate", "2026-07-20");
        }
    }

    @Test
    void selectedWorkOrderDoesNotRequireUnselectedWarehouseSections() throws Exception {
        stubPlanAndWorkOrderOnly();

        Map<String, Object> payload = buildPackage(List.of("work-order"));

        assertThat(payload.get("sections")).isEqualTo(List.of("work-order"));
        assertThat(payload).containsKey("workOrder");
        assertThat(payload).doesNotContainKeys("materialRequisition", "batchingSheet");
    }

    @Test
    void requestedMissingSectionFailsClosedInsteadOfPrintingBlankPage() {
        stubPlanAndWorkOrderOnly();
        when(requisitionService.listByPlan("F006", "PLAN-ID")).thenReturn(List.of());

        assertThatThrownBy(() -> buildPackage(List.of("material-requisition")))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .hasRootCauseMessage("领料单缺少物料需求数据");
    }

    @Test
    void planWithoutPinnedBomOrWorkflowFailsClosed() {
        ProductionPlanDTO plan = basePlan();
        plan.setSelectedBomRecipeId(null);
        when(productionPlanService.getProductionPlanById("F006", "PLAN-ID")).thenReturn(plan);

        assertThatThrownBy(() -> buildPackage(List.of("work-order")))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .hasRootCauseMessage("生产计划缺少锁定的 BOM 或 Workflow 版本，不能生成单据包");
    }

    @Test
    void endpointContractMatchesWebAndRequiresBothDocumentPermissions() throws Exception {
        Method method = PrintController.class.getDeclaredMethod(
                "printProductionDocumentPackage",
                String.class, String.class, List.class, Map.class, String.class);

        GetMapping mapping = method.getAnnotation(GetMapping.class);
        RequirePermission permission = method.getAnnotation(RequirePermission.class);
        assertThat(mapping.value()).containsExactly("/production-document-pack/{planId}");
        assertThat(permission.requireAll()).isTrue();
        assertThat(permission.value()).containsExactly("production:read", "warehouse:read");
    }

    @Test
    void chapterParserDefaultsAllDeduplicatesAndRejectsUnknownValues() throws Exception {
        assertThat(normalize(null)).containsExactly(
                "work-order", "material-requisition", "batching-sheet");
        assertThat(normalize(List.of("work-order,material-requisition", "work-order")))
                .containsExactly("work-order", "material-requisition");
        assertThatThrownBy(() -> normalize(List.of("not-a-document")))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(BusinessException.class);
    }

    private void stubCompletePlan() {
        stubPlanAndWorkOrderOnly();

        FactoryMaterialRequisitionItem item = new FactoryMaterialRequisitionItem();
        item.setMaterialTypeId("MAT-1");
        item.setMaterialName("原料A");
        item.setUnit("kg");
        item.setRequiredQty(new BigDecimal("5"));
        item.setMaterialCategory(FactoryMaterialRequisitionItem.MaterialCategory.RAW);
        FactoryMaterialRequisition requisition = mock(FactoryMaterialRequisition.class);
        when(requisition.getItems()).thenReturn(List.of(item));
        when(requisitionService.listByPlan("F006", "PLAN-ID")).thenReturn(List.of(requisition));

        ProductType product = mock(ProductType.class);
        when(product.getCode()).thenReturn("CPF0060015");
        when(product.getSinglePotCapacity()).thenReturn(new BigDecimal("5"));
        when(product.getUnit()).thenReturn("box");
        when(productTypeRepository.findByIdAndFactoryId("PRODUCT-1", "F006"))
                .thenReturn(Optional.of(product));
    }

    private void stubPlanAndWorkOrderOnly() {
        ProductionPlanDTO plan = basePlan();
        when(productionPlanService.getProductionPlanById("F006", "PLAN-ID")).thenReturn(plan);

        ProductionBatch batch = mock(ProductionBatch.class);
        when(batch.getId()).thenReturn(1L);
        when(batchRepository.findByFactoryIdAndProductionPlanId("F006", "PLAN-ID"))
                .thenReturn(List.of(batch));
        WorkProcessTaskDTO task = new WorkProcessTaskDTO();
        task.setProcessOrder(1);
        task.setProcessName("修油");
        task.setEstimatedMinutes(60);
        when(taskService.listByBatch("F006", 1L)).thenReturn(List.of(task));

        ProductType product = mock(ProductType.class);
        when(product.getCode()).thenReturn("CPF0060015");
        when(productTypeRepository.findByIdAndFactoryId("PRODUCT-1", "F006"))
                .thenReturn(Optional.of(product));
    }

    private ProductionPlanDTO basePlan() {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-001");
        plan.setProductTypeId("PRODUCT-1");
        plan.setProductName("黄油鸡成品");
        plan.setProductUnit("盒");
        plan.setPlannedUnit("box");
        plan.setWorkflowOutputUnit("box");
        plan.setPlannedQuantity(new BigDecimal("5"));
        plan.setBatchDate(LocalDate.of(2026, 7, 20));
        plan.setPlannedDate(LocalDate.of(2026, 7, 21));
        plan.setStatus(ProductionPlanStatus.COMPLETED);
        plan.setSelectedBomRecipeId("BOM-RECIPE-1");
        plan.setSelectedBomVersion(1);
        plan.setSelectedWorkflowId(105L);
        plan.setSelectedWorkflowVersion(1);
        return plan;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildPackage(List<String> sections) throws Exception {
        Method method = PrintController.class.getDeclaredMethod(
                "buildProductionDocumentPackagePayload",
                String.class, String.class, List.class, Map.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(
                controller, "F006", "PLAN-ID", sections, Map.of());
    }

    @SuppressWarnings("unchecked")
    private List<String> normalize(List<String> sections) throws Exception {
        Method method = PrintController.class.getDeclaredMethod(
                "normalizeProductionDocumentSections", List.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(controller, sections);
    }
}
