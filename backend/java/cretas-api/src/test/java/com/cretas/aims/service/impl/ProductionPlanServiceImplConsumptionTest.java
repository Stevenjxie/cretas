package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计 follow-up: ProductionPlanServiceImpl.recordMaterialConsumption 是 C-B1 同族站点 —
 * 创建 MaterialConsumption 时 unitPrice/totalCost/recordedBy 均 NOT NULL。此前直接用
 * batch.getUnitPrice() (未录价批次为 null → setUnitPrice(null)/multiply(null) → 500) +
 * recordedBy=plan.getCreatedBy() (legacy 计划可能 null → 500)。修复: unitPrice 兜底 ZERO,
 * recordedBy 取线程进来的 operatorId, 均无则抛 401。
 *
 * <p>用反射构造 (ProductionPlanServiceImpl 构造器很大), 仅注入本路径用到的 3 个 repo +
 * inventoryLowStockEventPublisher (字段注入)。</p>
 */
class ProductionPlanServiceImplConsumptionTest {

    private ProductionPlanRepository planRepo;
    private MaterialBatchRepository batchRepo;
    private MaterialConsumptionRepository consumptionRepo;
    private ProductionPlanServiceImpl svc;

    @BeforeEach
    void setUp() throws Exception {
        planRepo = mock(ProductionPlanRepository.class);
        batchRepo = mock(MaterialBatchRepository.class);
        consumptionRepo = mock(MaterialConsumptionRepository.class);

        Constructor<?> ctor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ProductionPlanRepository.class) args[i] = planRepo;
            else if (types[i] == MaterialBatchRepository.class) args[i] = batchRepo;
            else if (types[i] == MaterialConsumptionRepository.class) args[i] = consumptionRepo;
            else args[i] = null;
        }
        svc = (ProductionPlanServiceImpl) ctor.newInstance(args);
        inject(svc, "inventoryLowStockEventPublisher", mock(InventoryLowStockEventPublisher.class));
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = ProductionPlanServiceImpl.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private MaterialBatch batch(String id, String unitPrice) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId("F006");
        b.setReceiptQuantity(new BigDecimal("100"));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        if (unitPrice != null) b.setUnitPrice(new BigDecimal(unitPrice));
        return b;
    }

    private ProductionPlan plan(String id, Long createdBy) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId("F006");
        p.setCreatedBy(createdBy);
        return p;
    }

    @Test
    void nullUnitPriceBatch_doesNotFail_populatesZeroCostAndOperator() {
        when(planRepo.findById("PLAN-1")).thenReturn(Optional.of(plan("PLAN-1", 99L)));
        when(batchRepo.findById("MB-1")).thenReturn(Optional.of(batch("MB-1", null)));  // 未录价
        when(consumptionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.recordMaterialConsumption("F006", "PLAN-1", "MB-1", new BigDecimal("10"), 42L);

        ArgumentCaptor<MaterialConsumption> captor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepo).save(captor.capture());
        MaterialConsumption c = captor.getValue();
        assertThat(c.getUnitPrice()).isEqualByComparingTo("0");   // 兜底 ZERO, 不再 NPE/NOT NULL
        assertThat(c.getTotalCost()).isEqualByComparingTo("0");
        assertThat(c.getRecordedBy()).isEqualTo(42L);             // 线程进来的操作人
    }

    @Test
    void pricedBatch_computesTotalCost_operatorWins() {
        when(planRepo.findById("PLAN-2")).thenReturn(Optional.of(plan("PLAN-2", 99L)));
        when(batchRepo.findById("MB-2")).thenReturn(Optional.of(batch("MB-2", "8.50")));
        when(consumptionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.recordMaterialConsumption("F006", "PLAN-2", "MB-2", new BigDecimal("10"), 42L);

        ArgumentCaptor<MaterialConsumption> captor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepo).save(captor.capture());
        assertThat(captor.getValue().getTotalCost()).isEqualByComparingTo("85.00");
        assertThat(captor.getValue().getRecordedBy()).isEqualTo(42L);  // operatorId 优先于 plan.createdBy
    }

    @Test
    void nullOperator_fallsBackToPlanCreator() {
        when(planRepo.findById("PLAN-3")).thenReturn(Optional.of(plan("PLAN-3", 77L)));
        when(batchRepo.findById("MB-3")).thenReturn(Optional.of(batch("MB-3", "8.50")));
        when(consumptionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.recordMaterialConsumption("F006", "PLAN-3", "MB-3", new BigDecimal("10"), null);

        ArgumentCaptor<MaterialConsumption> captor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepo).save(captor.capture());
        assertThat(captor.getValue().getRecordedBy()).isEqualTo(77L);  // 兜底计划创建人
    }

    @Test
    void nullOperatorAndNullPlanCreator_throws401_noSave() {
        when(planRepo.findById("PLAN-4")).thenReturn(Optional.of(plan("PLAN-4", null)));
        when(batchRepo.findById("MB-4")).thenReturn(Optional.of(batch("MB-4", "8.50")));

        assertThatThrownBy(() ->
                svc.recordMaterialConsumption("F006", "PLAN-4", "MB-4", new BigDecimal("10"), null))
                .isInstanceOf(BusinessException.class);
        verify(consumptionRepo, never()).save(any());
    }
}
