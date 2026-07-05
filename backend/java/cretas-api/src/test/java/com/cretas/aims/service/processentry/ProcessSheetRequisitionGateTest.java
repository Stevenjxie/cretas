package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ② Part B 生产领料单 Gate 单元测试 (2026-07-02).
 *
 * <p>验证 {@code ensureRawMaterialWarehouse} 的领料 gate 分支 (opt-in, 默认 OFF):
 * <ol>
 *   <li>Gate OFF (工厂未开启 / 无 settings) → 走 Part A 宽松仓校验, 不要求领料单 (向后兼容, 零回归)。</li>
 *   <li>Gate ON + 该计划无已确认领料单 → 409 PRODUCTION_REQUISITION_REQUIRED (强制先领料)。</li>
 *   <li>Gate ON + 领料单已确认但未覆盖被消耗物料 → 409 (缺料指引)。</li>
 *   <li>Gate ON + 领料单已确认且覆盖该物料 (issuedQty>0) → 放行。</li>
 * </ol>
 */
class ProcessSheetRequisitionGateTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PLAN-001";
    private static final String MAT_TYPE_ID = "MT-pig-trotter";
    private static final String RAW_WH_ID = "wh-raw-001";

    private ProcessSheetServiceImpl newImpl(WarehouseResolver resolver,
                                            FactorySettingsRepository settingsRepo,
                                            FactoryMaterialRequisitionRepository reqRepo) throws Exception {
        ProcessSheetServiceImpl impl = new ProcessSheetServiceImpl(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        setField(impl, "warehouseResolver", resolver);
        setField(impl, "factorySettingsRepository", settingsRepo);
        setField(impl, "requisitionRepository", reqRepo);
        return impl;
    }

    private void setField(ProcessSheetServiceImpl impl, String name, Object value) throws Exception {
        Field f = ProcessSheetServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(impl, value);
    }

    private void invokeGate(ProcessSheetServiceImpl impl, MaterialBatch rawMb) throws Throwable {
        Method m = ProcessSheetServiceImpl.class
                .getDeclaredMethod("ensureRawMaterialWarehouse", String.class, String.class, MaterialBatch.class);
        m.setAccessible(true);
        try {
            m.invoke(impl, FACTORY_ID, PLAN_ID, rawMb);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private MaterialBatch rawBatch() {
        MaterialBatch mb = new MaterialBatch();
        mb.setWarehouseId(RAW_WH_ID);
        mb.setMaterialTypeId(MAT_TYPE_ID);
        return mb;
    }

    private FactoryMaterialRequisition requisition(Status status, String matTypeId, BigDecimal issued) {
        FactoryMaterialRequisition r = new FactoryMaterialRequisition();
        r.setStatus(status);
        FactoryMaterialRequisitionItem item = new FactoryMaterialRequisitionItem();
        item.setMaterialTypeId(matTypeId);
        item.setIssuedQty(issued);
        r.setItems(new java.util.ArrayList<>(List.of(item)));
        return r;
    }

    @Test
    @DisplayName("Gate OFF (settings 返回 false) → 走 Part A 宽松校验, 不要求领料单")
    void gateOff_fallsBackToPartA() throws Throwable {
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        when(resolver.resolveProductionRawWh(FACTORY_ID)).thenReturn(RAW_WH_ID);
        FactorySettingsRepository settingsRepo = mock(FactorySettingsRepository.class);
        when(settingsRepo.findRequireRequisitionBeforeReportByFactoryId(FACTORY_ID)).thenReturn(false);
        FactoryMaterialRequisitionRepository reqRepo = mock(FactoryMaterialRequisitionRepository.class);

        ProcessSheetServiceImpl impl = newImpl(resolver, settingsRepo, reqRepo);
        assertDoesNotThrow(() -> invokeGate(impl, rawBatch()));
        // Gate OFF → 不查领料单
        verify(reqRepo, never()).findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(anyString(), anyString());
    }

    @Test
    @DisplayName("Gate OFF + 批次在生产仓 (领料迁移后) → 放行 (报工从生产仓消耗合法)")
    void gateOff_batchInWorkshopWarehouse_passes() throws Throwable {
        String workshopWhId = "wh-wks-001";
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        when(resolver.resolveProductionRawWh(FACTORY_ID)).thenReturn(RAW_WH_ID); // 默认生产领料仓 = 原料仓
        when(resolver.isRawOrLogisticsWarehouse(FACTORY_ID, workshopWhId)).thenReturn(false);
        when(resolver.isWorkshopWarehouse(FACTORY_ID, workshopWhId)).thenReturn(true);
        FactorySettingsRepository settingsRepo = mock(FactorySettingsRepository.class);
        when(settingsRepo.findRequireRequisitionBeforeReportByFactoryId(FACTORY_ID)).thenReturn(false);

        ProcessSheetServiceImpl impl = newImpl(resolver, settingsRepo, mock(FactoryMaterialRequisitionRepository.class));
        MaterialBatch mb = new MaterialBatch();
        mb.setWarehouseId(workshopWhId);
        mb.setMaterialTypeId(MAT_TYPE_ID);
        assertDoesNotThrow(() -> invokeGate(impl, mb));
    }

    @Test
    @DisplayName("Gate OFF + 批次在非 原料/物流/生产 仓 → 409 (诚实拒绝)")
    void gateOff_batchInForeignWarehouse_throws() throws Throwable {
        String foreignWhId = "wh-finished-001";
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        when(resolver.resolveProductionRawWh(FACTORY_ID)).thenReturn(RAW_WH_ID);
        when(resolver.isRawOrLogisticsWarehouse(FACTORY_ID, foreignWhId)).thenReturn(false);
        when(resolver.isWorkshopWarehouse(FACTORY_ID, foreignWhId)).thenReturn(false);
        FactorySettingsRepository settingsRepo = mock(FactorySettingsRepository.class);
        when(settingsRepo.findRequireRequisitionBeforeReportByFactoryId(FACTORY_ID)).thenReturn(false);

        ProcessSheetServiceImpl impl = newImpl(resolver, settingsRepo, mock(FactoryMaterialRequisitionRepository.class));
        MaterialBatch mb = new MaterialBatch();
        mb.setWarehouseId(foreignWhId);
        mb.setMaterialTypeId(MAT_TYPE_ID);
        BusinessException ex = assertThrows(BusinessException.class, () -> invokeGate(impl, mb));
        assertEquals("PRODUCTION_RAW_WAREHOUSE_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("无 settings repo → 兜底 OFF (向后兼容)")
    void noSettingsRepo_defaultsOff() throws Throwable {
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        when(resolver.resolveProductionRawWh(FACTORY_ID)).thenReturn(RAW_WH_ID);
        ProcessSheetServiceImpl impl = newImpl(resolver, null, null);
        assertDoesNotThrow(() -> invokeGate(impl, rawBatch()));
    }

    @Test
    @DisplayName("Gate ON + 无已确认领料单 → 409 PRODUCTION_REQUISITION_REQUIRED")
    void gateOn_noRequisition_throws() throws Throwable {
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        FactorySettingsRepository settingsRepo = mock(FactorySettingsRepository.class);
        when(settingsRepo.findRequireRequisitionBeforeReportByFactoryId(FACTORY_ID)).thenReturn(true);
        FactoryMaterialRequisitionRepository reqRepo = mock(FactoryMaterialRequisitionRepository.class);
        when(reqRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of());

        ProcessSheetServiceImpl impl = newImpl(resolver, settingsRepo, reqRepo);
        BusinessException ex = assertThrows(BusinessException.class, () -> invokeGate(impl, rawBatch()));
        assertEquals(409, ex.getCode());
        assertEquals("PRODUCTION_REQUISITION_REQUIRED", ex.getErrorCode());
        // Gate ON → 不再走仓校验 (resolveProductionRawWh 不应被调用)
        verify(resolver, never()).resolveProductionRawWh(anyString());
    }

    @Test
    @DisplayName("Gate ON + 领料单已确认但未覆盖该物料 → 409")
    void gateOn_requisitionNotCoveringMaterial_throws() throws Throwable {
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        FactorySettingsRepository settingsRepo = mock(FactorySettingsRepository.class);
        when(settingsRepo.findRequireRequisitionBeforeReportByFactoryId(FACTORY_ID)).thenReturn(true);
        FactoryMaterialRequisitionRepository reqRepo = mock(FactoryMaterialRequisitionRepository.class);
        // 领料单已 TRANSFERRED 但明细是别的物料
        when(reqRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(requisition(Status.TRANSFERRED, "MT-other", new BigDecimal("10"))));

        ProcessSheetServiceImpl impl = newImpl(resolver, settingsRepo, reqRepo);
        BusinessException ex = assertThrows(BusinessException.class, () -> invokeGate(impl, rawBatch()));
        assertEquals(409, ex.getCode());
        assertEquals("PRODUCTION_REQUISITION_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Gate ON + 领料单仍 PENDING (未确认) → 409")
    void gateOn_requisitionPending_throws() throws Throwable {
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        FactorySettingsRepository settingsRepo = mock(FactorySettingsRepository.class);
        when(settingsRepo.findRequireRequisitionBeforeReportByFactoryId(FACTORY_ID)).thenReturn(true);
        FactoryMaterialRequisitionRepository reqRepo = mock(FactoryMaterialRequisitionRepository.class);
        when(reqRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(requisition(Status.PENDING, MAT_TYPE_ID, new BigDecimal("10"))));

        ProcessSheetServiceImpl impl = newImpl(resolver, settingsRepo, reqRepo);
        BusinessException ex = assertThrows(BusinessException.class, () -> invokeGate(impl, rawBatch()));
        assertEquals(409, ex.getCode());
        assertEquals("PRODUCTION_REQUISITION_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Gate ON + 领料单已确认且覆盖该物料 (issuedQty>0) → 放行")
    void gateOn_requisitionConfirmedAndCovers_passes() throws Throwable {
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        FactorySettingsRepository settingsRepo = mock(FactorySettingsRepository.class);
        when(settingsRepo.findRequireRequisitionBeforeReportByFactoryId(FACTORY_ID)).thenReturn(true);
        FactoryMaterialRequisitionRepository reqRepo = mock(FactoryMaterialRequisitionRepository.class);
        when(reqRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(requisition(Status.TRANSFERRED, MAT_TYPE_ID, new BigDecimal("10"))));

        ProcessSheetServiceImpl impl = newImpl(resolver, settingsRepo, reqRepo);
        assertDoesNotThrow(() -> invokeGate(impl, rawBatch()));
    }

    @Test
    @DisplayName("Gate ON + IN_USE 领料单覆盖该物料 → 放行")
    void gateOn_inUseRequisitionCovers_passes() throws Throwable {
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        FactorySettingsRepository settingsRepo = mock(FactorySettingsRepository.class);
        when(settingsRepo.findRequireRequisitionBeforeReportByFactoryId(FACTORY_ID)).thenReturn(true);
        FactoryMaterialRequisitionRepository reqRepo = mock(FactoryMaterialRequisitionRepository.class);
        when(reqRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(requisition(Status.IN_USE, MAT_TYPE_ID, new BigDecimal("5"))));

        ProcessSheetServiceImpl impl = newImpl(resolver, settingsRepo, reqRepo);
        assertDoesNotThrow(() -> invokeGate(impl, rawBatch()));
    }
}
