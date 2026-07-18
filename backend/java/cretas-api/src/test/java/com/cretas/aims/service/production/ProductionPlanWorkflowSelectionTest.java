package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessTaskRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
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
    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        productTypeRepository = mock(ProductTypeRepository.class);
        workflowResolutionService = mock(ProductWorkflowResolutionService.class);
        service = new ProductionPlanServiceImpl(
                mock(ProductionPlanRepository.class), mock(ProductionBatchRepository.class),
                mock(ProcessTaskRepository.class), mock(MaterialBatchRepository.class),
                mock(MaterialConsumptionRepository.class), mock(ProductionPlanBatchUsageRepository.class),
                productTypeRepository, mock(ProductionPlanMapper.class), mock(ConversionRepository.class),
                mock(SchedulingService.class), mock(ProductionLineRepository.class), mock(UserRepository.class),
                mock(ExcelUtil.class), mock(SalesOrderRepository.class),
                mock(SalesOrderItemRepository.class));
        ReflectionTestUtils.setField(service, "workflowResolutionService", workflowResolutionService);

        ProductType owner = new ProductType();
        owner.setId("OWNER");
        owner.setFactoryId("F1");
        owner.setUnit("kg");
        when(productTypeRepository.findById("OWNER")).thenReturn(Optional.of(owner));
        when(productTypeRepository.findByIdAndFactoryId("OWNER", "F1")).thenReturn(Optional.of(owner));
    }

    @Test
    void exactUiSelectionUsesPinnedWorkflowContractInsteadOfResolvingTheCurrentAnchorAgain() {
        WorkflowPlanOutputContract pinned = new WorkflowPlanOutputContract(
                88L, 3, Map.of("FG-1", "box"), "kg");
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
}
