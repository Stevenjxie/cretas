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
    @Mock private com.cretas.aims.repository.ProcessTaskRepository processTaskRepo;
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

    /** Build a service instance wiring the optional wipInventoryService via reflection. */
    private ProductionPlanServiceImpl buildService(WipInventoryService wipSvc) throws Exception {
        Constructor<?> ctor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ProductionPlanRepository.class)          args[i] = productionPlanRepo;
            else if (types[i] == ProductionBatchRepository.class)    args[i] = productionBatchRepo;
            else if (types[i] == com.cretas.aims.repository.ProcessTaskRepository.class)
                                                                      args[i] = processTaskRepo;
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
}
