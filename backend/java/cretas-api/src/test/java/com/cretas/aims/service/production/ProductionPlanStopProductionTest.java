package com.cretas.aims.service.production;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
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
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * G3b 停产单元测试 — 验证存货生产 (SAFETY_STOCK) 计划停产是纯状态关闭:
 * 无 BatchCompletedEvent 发布、无批次查询、无物料扣减。
 */
@DisplayName("ProductionPlan 停产 (G3b 存货生产纯状态关闭)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionPlanStopProductionTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID   = "PP-STOP-1";

    @Mock private ProductionPlanRepository        productionPlanRepository;
    @Mock private ProductionBatchRepository        productionBatchRepository;
    @Mock private ProcessTaskRepository            processTaskRepository;
    @Mock private MaterialBatchRepository          materialBatchRepository;
    @Mock private MaterialConsumptionRepository    materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository            productTypeRepository;
    @Mock private ProductionPlanMapper             productionPlanMapper;
    @Mock private ConversionRepository             conversionRepository;
    @Mock private SchedulingService                schedulingService;
    @Mock private ProductionLineRepository         productionLineRepository;
    @Mock private UserRepository                   userRepository;
    @Mock private ExcelUtil                        excelUtil;
    @Mock private SalesOrderRepository             salesOrderRepository;
    @Mock private SalesOrderItemRepository         salesOrderItemRepository;
    @Mock private BomService                       bomService;
    @Mock private ApplicationEventPublisher        applicationEventPublisher;
    @Mock private ProcessSheetRowRepository        processSheetRowRepository;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository, processTaskRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", applicationEventPublisher);
        ReflectionTestUtils.setField(service, "processSheetRowRepository", processSheetRowRepository);
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ProductionPlan byStockPlan() {
        ProductionPlan p = new ProductionPlan();
        p.setId(PLAN_ID);
        p.setFactoryId(FACTORY_ID);
        p.setPlanNumber("PN-STOP-1");
        p.setProductTypeId("PT-1");
        p.setPlannedQuantity(new BigDecimal("1000"));
        p.setStatus(ProductionPlanStatus.IN_PROGRESS);
        p.setSourceType(PlanSourceType.SAFETY_STOCK);
        p.setCreatedBy(1L);
        return p;
    }

    // ─── tests ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("存货生产 (SAFETY_STOCK) 计划停产 → COMPLETED, endTime 已设, repo.save 被调用")
    void stopProduction_byStock_setsCompletedAndSaves() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.stopProduction(FACTORY_ID, PLAN_ID);

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus(), "状态应为 COMPLETED");
        assertNotNull(plan.getEndTime(), "endTime 必须被设置");
        verify(productionPlanRepository).save(plan);
    }

    @Test
    @DisplayName("停产不发布任何事件 — 确认无 BatchCompletedEvent 双重扣减")
    void stopProduction_publishesNoEvent() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(1));

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.stopProduction(FACTORY_ID, PLAN_ID);

        // 关键: 没有发布任何事件 (BatchCompletedEvent / ProductionCompletedEvent)
        verify(applicationEventPublisher, never()).publishEvent(any());
        // 关键: 没有查询批次 (completeProduction 才会查 findByFactoryIdAndProductionPlanId)
        verify(productionBatchRepository, never()).findByFactoryIdAndProductionPlanId(any(), any());
    }

    @Test
    @DisplayName("startTime 为 null 时停产自动补填 startTime")
    void stopProduction_noStartTime_backfillsStartTime() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(null);

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.stopProduction(FACTORY_ID, PLAN_ID);

        assertNotNull(plan.getStartTime(), "startTime 未设时应补填");
        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
    }

    @Test
    @DisplayName("非存货生产 (非 SAFETY_STOCK) 计划停产 → BusinessException(400)")
    void stopProduction_byOrder_throws400() {
        ProductionPlan plan = byStockPlan();
        plan.setSourceType(PlanSourceType.CUSTOMER_ORDER);

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals(400, ex.getCode(), "非库存业态应抛 400");
        assertTrue(ex.getMessage().contains("仅存货生产计划可停产"));
        verify(productionPlanRepository, never()).save(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("🔒🔒 Gap2b: 有未结报工消耗时停产 → 409 拒绝 (停产零扣减, 若放行则残料永不扣减=幻库存)")
    void stopProduction_withUnsettledConsumption_blocked() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));

        ProcessSheetRow row = new ProcessSheetRow();
        row.setBatchId(555L);   // 已物化的 per-道 ProductionBatch id → 纳入 unsettled 检测

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(row));
        // 存在未结算 (interimSettledAt IS NULL) 报工消耗 → 停产必须拦截
        when(materialConsumptionRepository
                .findByFactoryIdAndProductionBatchIdInAndInterimSettledAtIsNull(eq(FACTORY_ID), anyList()))
                .thenReturn(List.of(new MaterialConsumption()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals(409, ex.getCode(), "有未结报工消耗应 409 拒绝停产");
        assertEquals("STOP_BLOCKED_UNSETTLED_CONSUMPTION", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("请先小结再停产"));
        // 关键: 状态未翻转, 未保存 (幻库存被前置守卫堵死)
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus(), "状态不应变 COMPLETED");
        verify(productionPlanRepository, never()).save(any());
    }

    @Test
    @DisplayName("🔴🔒🔒 BUG3: 中段起步纯 SFI 投料 (SAVED_SFI, batchId=null, 零 MaterialConsumption) 停产 → 409 拒绝 (消耗守卫盲区, 已投 SFI 会成幻库存)")
    void stopProduction_midStartPureSfi_noConsumption_blocked() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));

        // 纯 SFI 中间道: batchId=null (不物化 WIP), batchNumber=SFI 锚, 未结, 无 MaterialConsumption。
        // 现有消耗守卫按 batchId 定位 → 此行被 filter(id!=null) 排除 → 零消耗 → 旧逻辑放行 (BUG3)。
        ProcessSheetRow sfiRow = new ProcessSheetRow();
        sfiRow.setBatchId(null);
        sfiRow.setBatchNumber("SFI-MIDSTART-1");
        sfiRow.setRowStatus(ProcessSheetRow.STATUS_SAVED_SFI);
        sfiRow.setInterimSettledAt(null);

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(sfiRow));
        // batchId 全 null → findUnsettledPlanConsumptions 短路返回空 (repo 不被调用) → 消耗守卫放行

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.stopProduction(FACTORY_ID, PLAN_ID));

        assertEquals(409, ex.getCode(), "中段起步 SFI 投料未结应 409 拒绝停产");
        assertEquals("STOP_BLOCKED_UNSETTLED_CONSUMPTION", ex.getErrorCode());
        assertEquals(ProductionPlanStatus.IN_PROGRESS, plan.getStatus(), "状态不应变 COMPLETED");
        verify(productionPlanRepository, never()).save(any());
    }

    @Test
    @DisplayName("Gap2b 反向: 报工消耗已全部小结 (无未结) → 正常停产 → COMPLETED (不误伤干净停产)")
    void stopProduction_allConsumptionSettled_completes() {
        ProductionPlan plan = byStockPlan();
        plan.setStartTime(LocalDateTime.now().minusHours(2));

        ProcessSheetRow row = new ProcessSheetRow();
        row.setBatchId(777L);

        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));
        when(processSheetRowRepository.findByFactoryIdAndPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(row));
        // 无未结消耗 (全部已小结) → 停产放行
        when(materialConsumptionRepository
                .findByFactoryIdAndProductionBatchIdInAndInterimSettledAtIsNull(eq(FACTORY_ID), anyList()))
                .thenReturn(Collections.emptyList());
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.stopProduction(FACTORY_ID, PLAN_ID);

        assertEquals(ProductionPlanStatus.COMPLETED, plan.getStatus());
        verify(productionPlanRepository).save(plan);
    }
}
