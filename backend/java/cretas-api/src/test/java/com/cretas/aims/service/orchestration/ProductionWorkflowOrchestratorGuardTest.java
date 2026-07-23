package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.exception.BusinessException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 🔒 防呆 Rule 1 — 计划量为 0/null 时 generateTransferFromPlan 必须 loud-fail,
 * 绝不落地 0 数量的死调拨单 (幽灵 artifact).
 *
 * 背景: SAFETY_STOCK(存货生产) 计划按设计 plannedQuantity=0 —— 产量在「逐道录入/小结」时才确定.
 * 修复前: BOM 展开 (qty × perUnit) 得到全 0 需求, 仍继续 createTransfer, 持久化一个
 * sourceWarehouse=null / 全 0 数量 / status=REQUESTED 的死记录, 前端却提示"生成成功".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductionWorkflowOrchestrator.generateTransferFromPlan 0数量守卫")
class ProductionWorkflowOrchestratorGuardTest {

    @Mock private BomExpansionService bomExpansionService;
    @Mock private TransferService transferService;
    @Mock private ProductionPlanService productionPlanService;
    @Mock private InternalTransferRepository transferRepository;

    private ProductionWorkflowOrchestrator newOrchestrator() {
        // 只用到前 3 个依赖; 其余传 null (守卫在触碰它们之前就抛异常).
        return new ProductionWorkflowOrchestrator(
                bomExpansionService, transferService, productionPlanService, null, transferRepository,
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
    @DisplayName("plannedQuantity=null → 抛异常, 绝不创建调拨单")
    void nullPlannedQuantity_rejected_noDeadRecord() {
        when(productionPlanService.getProductionPlanById(anyString(), anyString()))
                .thenReturn(plan(null));
        ProductionWorkflowOrchestrator orch = newOrchestrator();

        assertThrows(BusinessException.class,
                () -> orch.generateTransferFromPlan("F006", "plan-1", null, 1L));
        verify(transferService, never()).createTransfer(anyString(), any(), anyLong());
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
