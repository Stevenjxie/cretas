package com.cretas.aims.service.impl;

import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SP2: ProductionPlanService — createSecondaryPlan 单元测试。
 *
 * <p>使用反射注入私有可选字段 wipInventoryService（与 ProductionPlanServiceImplUomTest 保持一致）。
 *
 * @since SP2 (2026-06-10, feat/liushanmen-sp2-reversal)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SP2: ProductionPlanService — createSecondaryPlan")
class ProductionPlanServiceSecondaryTest {

    private static final String FACTORY_ID = "F006";
    private static final Long WIP_ID = 77L;
    private static final String PRODUCT_TYPE_ID = "PT-001";

    // --- Mocks for constructor args ---
    @Mock private ProductionPlanRepository productionPlanRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialConsumptionRepository materialConsumptionRepo;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private ProductionPlanMapper productionPlanMapper;
    @Mock private ConversionRepository conversionRepo;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepo;
    @Mock private UserRepository userRepo;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepo;
    @Mock private SalesOrderItemRepository salesOrderItemRepo;
    @Mock private BomService bomService;

    // --- Optional SP2 dependency ---
    @Mock private WipInventoryService wipInventoryService;

    // --- Optional R2/修1 dependency: 检测 YIELD 报工 ---
    @Mock private ProductionReportRepository productionReportRepo;

    /** Build a service instance wiring the optional wipInventoryService via reflection. */
    private ProductionPlanServiceImpl buildService(WipInventoryService wipSvc) throws Exception {
        Constructor<?> ctor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ProductionPlanRepository.class)          args[i] = productionPlanRepo;
            else if (types[i] == ProductionBatchRepository.class)    args[i] = productionBatchRepo;
            else if (types[i] == MaterialBatchRepository.class)      args[i] = materialBatchRepo;
            else if (types[i] == MaterialConsumptionRepository.class) args[i] = materialConsumptionRepo;
            else if (types[i] == ProductionPlanBatchUsageRepository.class) args[i] = planBatchUsageRepo;
            else if (types[i] == ProductTypeRepository.class)        args[i] = productTypeRepo;
            else if (types[i] == ProductionPlanMapper.class)         args[i] = productionPlanMapper;
            else if (types[i] == ConversionRepository.class)        args[i] = conversionRepo;
            else if (types[i] == SchedulingService.class)           args[i] = schedulingService;
            else if (types[i] == ProductionLineRepository.class)    args[i] = productionLineRepo;
            else if (types[i] == UserRepository.class)              args[i] = userRepo;
            else if (types[i] == ExcelUtil.class)                   args[i] = excelUtil;
            else if (types[i] == SalesOrderRepository.class)        args[i] = salesOrderRepo;
            else if (types[i] == SalesOrderItemRepository.class)    args[i] = salesOrderItemRepo;
            else if (types[i] == BomService.class)                  args[i] = bomService;
            else                                                      args[i] = null;
        }
        ProductionPlanServiceImpl svc = (ProductionPlanServiceImpl) ctor.newInstance(args);
        if (wipSvc != null) {
            injectField(svc, "wipInventoryService", wipSvc);
        }
        return svc;
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field f = ProductionPlanServiceImpl.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private SemiFinishedInventory buildWip(BigDecimal available) {
        SemiFinishedInventory wip = new SemiFinishedInventory();
        wip.setId(WIP_ID);
        wip.setFactoryId(FACTORY_ID);
        wip.setAvailableQuantity(available);
        wip.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        return wip;
    }

    private ProductType buildProductType() {
        ProductType pt = new ProductType();
        pt.setId(PRODUCT_TYPE_ID);
        pt.setFactoryId(FACTORY_ID);
        pt.setName("猪蹄");
        return pt;
    }

    // ==================== createSecondaryPlan ====================

    @Nested
    @DisplayName("createSecondaryPlan")
    class CreateSecondaryPlan {

        @Test
        @DisplayName("正常流程 — PENDING 计划创建, planSourceType=SECONDARY, secondarySourceWipId=wipId")
        void happyPath_createsPendingPlanWithSecondaryFields() throws Exception {
            ProductionPlanServiceImpl svc = buildService(wipInventoryService);
            when(wipInventoryService.listAvailableWip(FACTORY_ID))
                    .thenReturn(List.of(buildWip(new BigDecimal("50"))));
            when(productTypeRepo.findById(PRODUCT_TYPE_ID))
                    .thenReturn(Optional.of(buildProductType()));
            when(productionPlanRepo.save(any())).thenAnswer(inv -> {
                ProductionPlan plan = inv.getArgument(0);
                plan.setId("PLAN-001");
                return plan;
            });
            when(productionPlanMapper.toDTO(any())).thenReturn(new ProductionPlanDTO());

            svc.createSecondaryPlan(FACTORY_ID, WIP_ID,
                    new BigDecimal("30"), PRODUCT_TYPE_ID, LocalDate.now(), 1L);

            ArgumentCaptor<ProductionPlan> captor = ArgumentCaptor.forClass(ProductionPlan.class);
            verify(productionPlanRepo).save(captor.capture());
            ProductionPlan saved = captor.getValue();
            assertThat(saved.getPlanSourceType()).isEqualTo("SECONDARY");
            assertThat(saved.getSecondarySourceWipId()).isEqualTo(WIP_ID);
            assertThat(saved.getPlannedQuantity()).isEqualByComparingTo("30");
            assertThat(saved.getProductTypeId()).isEqualTo(PRODUCT_TYPE_ID);
            assertThat(saved.getPlanNumber()).startsWith("SEC-" + FACTORY_ID + "-");
        }

        @Test
        @DisplayName("WIP 不存在 (不在 listAvailableWip 结果中) → ResourceNotFoundException")
        void wipNotAvailable_throwsNotFound() throws Exception {
            ProductionPlanServiceImpl svc = buildService(wipInventoryService);
            when(wipInventoryService.listAvailableWip(FACTORY_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> svc.createSecondaryPlan(FACTORY_ID, WIP_ID,
                    new BigDecimal("10"), PRODUCT_TYPE_ID, null, null))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(productionPlanRepo, never()).save(any());
        }

        @Test
        @DisplayName("请求数量超过 WIP 可用量 → 409")
        void exceedsAvailable_throws409() throws Exception {
            ProductionPlanServiceImpl svc = buildService(wipInventoryService);
            when(wipInventoryService.listAvailableWip(FACTORY_ID))
                    .thenReturn(List.of(buildWip(new BigDecimal("10"))));

            assertThatThrownBy(() -> svc.createSecondaryPlan(FACTORY_ID, WIP_ID,
                    new BigDecimal("50"), PRODUCT_TYPE_ID, null, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("半成品可用量不足");

            verify(productionPlanRepo, never()).save(any());
        }

        @Test
        @DisplayName("数量为 0 → 400")
        void zeroQuantity_throws400() throws Exception {
            ProductionPlanServiceImpl svc = buildService(wipInventoryService);
            when(wipInventoryService.listAvailableWip(FACTORY_ID))
                    .thenReturn(List.of(buildWip(new BigDecimal("50"))));

            assertThatThrownBy(() -> svc.createSecondaryPlan(FACTORY_ID, WIP_ID,
                    BigDecimal.ZERO, PRODUCT_TYPE_ID, null, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("计划加工数量必须大于 0");
        }

        @Test
        @DisplayName("产品类型不存在 → ResourceNotFoundException")
        void productTypeNotFound_throwsNotFound() throws Exception {
            ProductionPlanServiceImpl svc = buildService(wipInventoryService);
            when(wipInventoryService.listAvailableWip(FACTORY_ID))
                    .thenReturn(List.of(buildWip(new BigDecimal("50"))));
            when(productTypeRepo.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> svc.createSecondaryPlan(FACTORY_ID, WIP_ID,
                    new BigDecimal("10"), PRODUCT_TYPE_ID, null, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("wipInventoryService 为 null (未注入) → 500")
        void wipServiceNull_throws500() throws Exception {
            ProductionPlanServiceImpl svc = buildService(null); // no wipInventoryService

            assertThatThrownBy(() -> svc.createSecondaryPlan(FACTORY_ID, WIP_ID,
                    new BigDecimal("10"), PRODUCT_TYPE_ID, null, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("二次加工服务未初始化");
        }
    }

    // ==================== 修1 (🔴): 取消 SECONDARY 计划 — 还 WIP / 不卡 IN_PROGRESS ====================

    @Nested
    @DisplayName("修1: cancelProductionPlan — SECONDARY 计划取消")
    class CancelSecondaryPlan {

        private static final String PLAN_ID = "SEC-PLAN-001";

        private ProductionPlan buildSecondaryInProgressPlan() {
            ProductionPlan plan = new ProductionPlan();
            plan.setId(PLAN_ID);
            plan.setFactoryId(FACTORY_ID);
            plan.setStatus(com.cretas.aims.entity.enums.ProductionPlanStatus.IN_PROGRESS);
            plan.setPlanSourceType("SECONDARY");
            plan.setSecondarySourceWipId(WIP_ID);
            plan.setPlannedQuantity(new BigDecimal("30"));
            plan.setProductTypeId(PRODUCT_TYPE_ID);
            return plan;
        }

        @Test
        @DisplayName("SECONDARY 开工扣 WIP 但无报工 → 取消时反冲 WIP + 计划 CANCELLED (不卡, 不导向报工撤回)")
        void secondaryNoYield_reversesWipAndCancels() throws Exception {
            ProductionPlanServiceImpl svc = buildService(wipInventoryService);
            ProductionPlan plan = buildSecondaryInProgressPlan();
            when(productionPlanRepo.findById(PLAN_ID)).thenReturn(Optional.of(plan));
            // 本计划无活跃批次 (开工扣 WIP, 还没建报工批次), 也无 YIELD 报工
            when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                    .thenReturn(List.of());
            when(productionPlanRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            svc.cancelProductionPlan(FACTORY_ID, PLAN_ID, "不做了");

            // 反冲 WIP 被调用 (还回开工扣的 30)
            verify(wipInventoryService).reverseSecondaryDeduct(
                    eq(WIP_ID), eq(new BigDecimal("30")), eq(FACTORY_ID), any());
            // 计划置 CANCELLED, 不卡 IN_PROGRESS
            assertThat(plan.getStatus())
                    .isEqualTo(com.cretas.aims.entity.enums.ProductionPlanStatus.CANCELLED);
        }

        @Test
        @DisplayName("SECONDARY 已有 YIELD 报工 → 仍导向报工撤回 (拒绝直接取消, 不反冲)")
        void secondaryWithYield_routesToReversal() throws Exception {
            ProductionPlanServiceImpl svc = buildService(wipInventoryService);
            // 注入 productionReportRepository 让 hasYieldReports 能查到报工
            injectField(svc, "productionReportRepository", productionReportRepo);
            ProductionPlan plan = buildSecondaryInProgressPlan();
            when(productionPlanRepo.findById(PLAN_ID)).thenReturn(Optional.of(plan));

            com.cretas.aims.entity.ProductionBatch batch = new com.cretas.aims.entity.ProductionBatch();
            batch.setId(9001L);
            batch.setBatchNumber("B-9001");
            batch.setStatus(com.cretas.aims.entity.enums.ProductionBatchStatus.IN_PROGRESS);
            when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                    .thenReturn(List.of(batch));
            com.cretas.aims.entity.ProductionReport yield = new com.cretas.aims.entity.ProductionReport();
            yield.setId(1L);
            when(productionReportRepo.findYieldReportsByBatch(FACTORY_ID, 9001L))
                    .thenReturn(List.of(yield));

            assertThatThrownBy(() -> svc.cancelProductionPlan(FACTORY_ID, PLAN_ID, "撤回"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("报工");

            // 有报工 → 不走反冲 (走报工撤回), 计划不被置 CANCELLED
            verify(wipInventoryService, never()).reverseSecondaryDeduct(any(), any(), any(), any());
            assertThat(plan.getStatus())
                    .isEqualTo(com.cretas.aims.entity.enums.ProductionPlanStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("非 SECONDARY 计划无报工无批次 → 直接取消 (现有逻辑不动, 不触发反冲)")
        void normalPlanNoData_directCancel_noReverse() throws Exception {
            ProductionPlanServiceImpl svc = buildService(wipInventoryService);
            ProductionPlan plan = new ProductionPlan();
            plan.setId("NORMAL-PLAN-001");
            plan.setFactoryId(FACTORY_ID);
            plan.setStatus(com.cretas.aims.entity.enums.ProductionPlanStatus.IN_PROGRESS);
            plan.setPlanSourceType("NORMAL");
            when(productionPlanRepo.findById("NORMAL-PLAN-001")).thenReturn(Optional.of(plan));
            when(productionBatchRepo.findByFactoryIdAndProductionPlanId(FACTORY_ID, "NORMAL-PLAN-001"))
                    .thenReturn(List.of());
            when(productionPlanRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            svc.cancelProductionPlan(FACTORY_ID, "NORMAL-PLAN-001", "空计划取消");

            verify(wipInventoryService, never()).reverseSecondaryDeduct(any(), any(), any(), any());
            assertThat(plan.getStatus())
                    .isEqualTo(com.cretas.aims.entity.enums.ProductionPlanStatus.CANCELLED);
        }
    }
}
