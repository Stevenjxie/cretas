package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.inventory.TransferService;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划数量守卫与滚动调拨契约：
 * 显式 0/负数必须 fail-closed；未预设数量时按单个成品 BOM 基准创建唯一可调整草稿。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductionWorkflowOrchestrator.generateTransferFromPlan 0数量守卫")
class ProductionWorkflowOrchestratorGuardTest {

    @Mock private BomExpansionService bomExpansionService;
    @Mock private TransferService transferService;
    @Mock private ProductionPlanService productionPlanService;
    @Mock private InternalTransferRepository transferRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;

    private ProductionWorkflowOrchestrator newOrchestrator() {
        return new ProductionWorkflowOrchestrator(
                bomExpansionService, transferService, productionPlanService,
                rawMaterialTypeRepository, transferRepository,
                com.cretas.aims.service.unit.TestUnitContractFactory.legacyFacade());
    }

    private ProductionPlanDTO plan(BigDecimal plannedQty) {
        return ProductionPlanDTO.builder()
                .id("plan-1")
                .planNumber("PP-TEST-001")
                .productTypeId("prod-1")
                .plannedQuantity(plannedQty)
                .build();
    }

    @Test
    @DisplayName("plannedQuantity=0 (SAFETY_STOCK) → 抛 400, 绝不创建调拨单")
    void zeroPlannedQuantity_rejected_noDeadRecord() {
        when(productionPlanService.getProductionPlanById(anyString(), anyString()))
                .thenReturn(plan(BigDecimal.ZERO));
        ProductionWorkflowOrchestrator orch = newOrchestrator();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orch.generateTransferFromPlan("F006", "plan-1", null, 1L));
        assertEquals(400, ex.getCode(), "应为 400 级别的业务拒绝");
        // 核心: 从未创建任何调拨单 (无幽灵记录)
        verify(transferService, never()).createTransfer(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("plannedQuantity=null → 按单个成品 BOM 基准创建可调整的滚动调拨草稿")
    void nullPlannedQuantity_createsRollingDraftFromOneBomBasis() {
        when(productionPlanService.getProductionPlanById(anyString(), anyString()))
                .thenReturn(plan(null));
        when(transferRepository.findBySourceFactoryIdAndProductionPlanIdAndStatusInOrderByCreatedAtDesc(
                "F006", "plan-1",
                List.of(TransferStatus.DRAFT, TransferStatus.REQUESTED, TransferStatus.APPROVED)))
                .thenReturn(List.of());

        MaterialRequirement requirement = new MaterialRequirement(
                "mat-1", "原料A", new BigDecimal("2"), BigDecimal.ZERO, "kg");
        when(bomExpansionService.expandBOM("F006", "prod-1", BigDecimal.ONE))
                .thenReturn(List.of(requirement));
        when(bomExpansionService.checkMaterialAvailability(eq("F006"), any()))
                .thenReturn(new MaterialCheckResult(true, List.of(), List.of()));

        RawMaterialType materialType = new RawMaterialType();
        materialType.setUnit("kg");
        when(rawMaterialTypeRepository.findById("mat-1")).thenReturn(Optional.of(materialType));

        InternalTransfer created = new InternalTransfer();
        created.setId("transfer-new");
        created.setStatus(TransferStatus.DRAFT);
        doAnswer(invocation -> {
            CreateTransferRequest request = invocation.getArgument(1);
            assertEquals(new BigDecimal("2"), request.getItems().get(0).getQuantity());
            assertEquals("kg", request.getItems().get(0).getUnit());
            assertTrue(request.getRemark().contains("滚动备料基准"));
            return created;
        }).when(transferService).createTransfer(eq("F006"), any(), eq(1L));

        InternalTransfer result = newOrchestrator()
                .generateTransferFromPlan("F006", "plan-1", null, 1L);

        assertEquals("transfer-new", result.getId());
        assertEquals("plan-1", result.getProductionPlanId());
        verify(bomExpansionService).expandBOM("F006", "prod-1", BigDecimal.ONE);
        verify(transferRepository).save(created);
    }

    @Test
    @DisplayName("plannedQuantity=null → 已存在活动滚动调拨时幂等返回原单")
    void nullPlannedQuantity_existingOpenTransferIsReused() {
        when(productionPlanService.getProductionPlanById(anyString(), anyString()))
                .thenReturn(plan(null));
        InternalTransfer existing = new InternalTransfer();
        existing.setId("transfer-existing-null-plan");
        existing.setStatus(TransferStatus.DRAFT);
        when(transferRepository.findBySourceFactoryIdAndProductionPlanIdAndStatusInOrderByCreatedAtDesc(
                "F006", "plan-1",
                List.of(TransferStatus.DRAFT, TransferStatus.REQUESTED, TransferStatus.APPROVED)))
                .thenReturn(List.of(existing));

        InternalTransfer result = newOrchestrator()
                .generateTransferFromPlan("F006", "plan-1", null, 1L);

        assertEquals("transfer-existing-null-plan", result.getId());
        verify(transferService, never()).createTransfer(anyString(), any(), anyLong());
        verify(bomExpansionService, never()).expandBOM(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("计划量>0 但 BOM 展开全 0 需求 → 深度防御拒绝, 绝不创建调拨单")
    void allZeroRequirements_rejected_noDeadRecord() {
        when(productionPlanService.getProductionPlanById(anyString(), anyString()))
                .thenReturn(plan(new BigDecimal("100")));
        MaterialRequirement zeroReq = new MaterialRequirement();
        zeroReq.setMaterialTypeId("mat-1");
        zeroReq.setRequiredQuantity(BigDecimal.ZERO);
        lenient().when(bomExpansionService.expandBOM(anyString(), anyString(), any()))
                .thenReturn(List.of(zeroReq));
        ProductionWorkflowOrchestrator orch = newOrchestrator();

        assertThrows(BusinessException.class,
                () -> orch.generateTransferFromPlan("F006", "plan-1", null, 1L));
        verify(transferService, never()).createTransfer(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("已有当前计划的活跃调拨任务时幂等返回原单，不重复创建")
    void openTransferForPlan_isReturnedWithoutCreatingAnother() {
        when(productionPlanService.getProductionPlanById(anyString(), anyString()))
                .thenReturn(plan(new BigDecimal("5")));
        InternalTransfer existing = new InternalTransfer();
        existing.setId("transfer-existing");
        existing.setStatus(TransferStatus.DRAFT);
        when(transferRepository.findBySourceFactoryIdAndProductionPlanIdAndStatusInOrderByCreatedAtDesc(
                "F006", "plan-1", List.of(TransferStatus.DRAFT, TransferStatus.REQUESTED, TransferStatus.APPROVED)))
                .thenReturn(List.of(existing));

        InternalTransfer result = newOrchestrator().generateTransferFromPlan("F006", "plan-1", null, 1L);

        assertEquals("transfer-existing", result.getId());
        verify(transferService, never()).createTransfer(anyString(), any(), anyLong());
        verify(bomExpansionService, never()).expandBOM(anyString(), anyString(), any());
    }
}
