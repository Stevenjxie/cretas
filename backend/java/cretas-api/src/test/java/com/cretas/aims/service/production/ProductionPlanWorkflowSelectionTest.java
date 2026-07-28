package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.WorkflowPlanOutputContract;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductionPlanWorkflowSelectionTest {

    private ProductTypeRepository productTypeRepository;
    private ProductWorkflowResolutionService workflowResolutionService;
    private BomRecipeRepository bomRecipeRepository;
    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        productTypeRepository = mock(ProductTypeRepository.class);
        workflowResolutionService = mock(ProductWorkflowResolutionService.class);
        bomRecipeRepository = mock(BomRecipeRepository.class);
        service = new ProductionPlanServiceImpl(
                mock(ProductionPlanRepository.class), mock(ProductionBatchRepository.class),
                mock(MaterialBatchRepository.class),
                mock(MaterialConsumptionRepository.class), mock(ProductionPlanBatchUsageRepository.class),
                productTypeRepository, mock(ProductionPlanMapper.class), mock(ConversionRepository.class),
                mock(SchedulingService.class), mock(ProductionLineRepository.class), mock(UserRepository.class),
                mock(ExcelUtil.class), mock(SalesOrderRepository.class),
                mock(SalesOrderItemRepository.class));
        ReflectionTestUtils.setField(service, "workflowResolutionService", workflowResolutionService);
        ReflectionTestUtils.setField(service, "bomRecipeRepository", bomRecipeRepository);

        ProductType owner = new ProductType();
        owner.setId("OWNER");
        owner.setFactoryId("F1");
        owner.setUnit("kg");
        when(productTypeRepository.findByIdAndFactoryId("OWNER", "F1")).thenReturn(Optional.of(owner));
    }

    @Test
    void exactUiSelectionUsesPinnedWorkflowContractInsteadOfResolvingTheCurrentAnchorAgain() {
        WorkflowPlanOutputContract pinned = new WorkflowPlanOutputContract(
                88L, 3, 903L, "revision-hash-903", Map.of("FG-1", "box"), "kg");
        when(workflowResolutionService.resolvePinnedPlanOutputContract(
                "F1", "OWNER", 88L, 3, List.of("FG-1"))).thenReturn(pinned);

        Object authority = ReflectionTestUtils.invokeMethod(
                service, "resolvePlanUnitAuthority",
                "F1", "OWNER", List.of("FG-1"), 88L, 3);

        assertNotNull(authority);
        verify(workflowResolutionService).resolvePinnedPlanOutputContract(
                "F1", "OWNER", 88L, 3, List.of("FG-1"));
        verify(workflowResolutionService, never()).resolveActivePlanOutputContract(
                "F1", "OWNER", List.of("FG-1"));
    }

    @Test
    void workflowIdAndVersionMustBeSubmittedTogether() {
        BusinessException error = assertThrows(BusinessException.class, () ->
                ReflectionTestUtils.invokeMethod(
                        service, "resolvePlanUnitAuthority",
                        "F1", "OWNER", List.of("FG-1"), 88L, null));

        assertEquals("WORKFLOW_SELECTION_INCOMPLETE", error.getErrorCode());
    }

    @Test
    void workflowPlanPinsExactRevisionFamilyAndOutputRecipeInsteadOfOwnerSkuBom() {
        WorkflowPlanOutputContract active = new WorkflowPlanOutputContract(
                105L, 1, 501L, "revision-hash-501", Map.of("OWNER", "kg"), "kg");
        when(workflowResolutionService.resolveActivePlanOutputContract(
                "F1", "OWNER", null)).thenReturn(Optional.of(active));
        BomRecipe recipe = BomRecipe.builder()
                .id("BOM-ACTIVE-V1")
                .factoryId("F1")
                .productTypeId("OWNER")
                .version(1)
                .workflowId(105L)
                .workflowDefinitionVersion(1)
                .workflowRevisionId(501L)
                .workflowRevisionHash("revision-hash-501")
                .bomFamilyId("FAMILY-501")
                .sharedRecipeId("BOM-ACTIVE-V1")
                .targetTerminalNodeId("terminal-owner")
                .status(BomRecipe.Status.ACTIVE)
                .isCurrent(true)
                .build();
        when(bomRecipeRepository
                .findByFactoryIdAndWorkflowRevisionIdAndStatusOrderByProductTypeIdAsc(
                        "F1", 501L, BomRecipe.Status.ACTIVE))
                .thenReturn(List.of(recipe));

        Object authority = ReflectionTestUtils.invokeMethod(
                service, "resolvePlanUnitAuthority", "F1", "OWNER", null);
        ProductionPlan plan = new ProductionPlan();
        plan.setFactoryId("F1");
        plan.setProductTypeId("OWNER");

        ReflectionTestUtils.invokeMethod(service, "applyPlanUnitAuthority", plan, authority);

        assertEquals(105L, plan.getSelectedWorkflowId());
        assertEquals(1, plan.getSelectedWorkflowVersion());
        assertEquals(501L, plan.getSelectedWorkflowRevisionId());
        assertEquals("revision-hash-501", plan.getSelectedWorkflowRevisionHash());
        assertEquals("FAMILY-501", plan.getSelectedBomFamilyId());
        assertEquals("BOM-ACTIVE-V1", plan.getSelectedBomRecipeId());
        assertEquals(1, plan.getSelectedBomVersion());
        assertEquals(Map.of("OWNER", "BOM-ACTIVE-V1"), plan.getSelectedBomRecipeIdsByProduct());
    }
}
