package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.BatchPlanFromSalesOrderRequest;
import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductionPlanSalesBatchDateTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "457daec1-d602-43a1-81a1-708586bfb937";
    private static final String ORDER_ID = "ecd7f20b-21c2-4ea3-9103-2034d5d6547f";
    private static final String PRODUCT_ID = "42321d1c-fdc3-457b-b78d-a781df12050d";
    private static final LocalDate BATCH_DATE = LocalDate.of(2026, 7, 20);
    private static final LocalDate PLANNED_DATE = LocalDate.of(2026, 7, 21);

    private ProductionPlanRepository planRepository;
    private ProductionBatchRepository batchRepository;
    private ProductTypeRepository productTypeRepository;
    private SalesOrderRepository salesOrderRepository;
    private SalesOrderItemRepository salesOrderItemRepository;
    private ProductionPlanMapper mapper;
    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        planRepository = mock(ProductionPlanRepository.class);
        batchRepository = mock(ProductionBatchRepository.class);
        productTypeRepository = mock(ProductTypeRepository.class);
        salesOrderRepository = mock(SalesOrderRepository.class);
        salesOrderItemRepository = mock(SalesOrderItemRepository.class);
        mapper = new ProductionPlanMapper();

        Constructor<?> ctor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ProductionPlanRepository.class) args[i] = planRepository;
            else if (types[i] == ProductionBatchRepository.class) args[i] = batchRepository;
            else if (types[i] == ProductTypeRepository.class) args[i] = productTypeRepository;
            else if (types[i] == ProductionPlanMapper.class) args[i] = mapper;
            else if (types[i] == SalesOrderRepository.class) args[i] = salesOrderRepository;
            else if (types[i] == SalesOrderItemRepository.class) args[i] = salesOrderItemRepository;
            else args[i] = mock(types[i]);
        }
        service = (ProductionPlanServiceImpl) ctor.newInstance(args);
    }

    @Test
    void salesOrderCreateKeepsBatchAndPlannedDatesIndependentAndRoundTripsCanonicalUnit() throws Exception {
        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY_ID);
        order.setOrderNumber("SO-20260720-0001");

        SalesOrderItem item = new SalesOrderItem();
        item.setId(726L);
        item.setSalesOrderId(ORDER_ID);
        item.setProductTypeId(PRODUCT_ID);
        item.setProductName("黄油鸡-成品800g");
        item.setQuantity(new BigDecimal("5"));
        item.setDeliveredQuantity(BigDecimal.ZERO);
        item.setUnit("box");

        ProductType product = new ProductType();
        product.setId(PRODUCT_ID);
        product.setFactoryId(FACTORY_ID);
        product.setUnit("盒");

        SalesOrderPlanQuantityNormalizer normalizer = mock(SalesOrderPlanQuantityNormalizer.class);
        injectField(service, "salesOrderPlanQuantityNormalizer", normalizer);
        when(normalizer.normalize(new BigDecimal("5"), item, product))
                .thenReturn(new SalesOrderPlanQuantityNormalizer.PlanQuantity(
                        new BigDecimal("5"), "盒", new BigDecimal("5"), "box"));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(item));
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT_ID, FACTORY_ID)).thenReturn(Optional.of(product));

        AtomicReference<CreateProductionPlanRequest> captured = new AtomicReference<>();
        ProductionPlanServiceImpl serviceSpy = spy(service);
        doAnswer(invocation -> {
            CreateProductionPlanRequest request = invocation.getArgument(1);
            captured.set(request);
            ProductionPlan persisted = planFrom(request);
            return mapper.toDTO(persisted);
        }).when(serviceSpy).createProductionPlan(any(), any(), any());

        BatchPlanFromSalesOrderRequest request = new BatchPlanFromSalesOrderRequest();
        request.setSourceOrderId(ORDER_ID);
        request.setItemIds(List.of("726"));
        request.setBatchDate(BATCH_DATE);
        request.setPlannedDate(PLANNED_DATE);

        ProductionPlanDTO result = serviceSpy.createPlansFromSalesOrder(FACTORY_ID, request, 1L).get(0);

        assertThat(captured.get().getBatchDate()).isEqualTo(BATCH_DATE);
        assertThat(captured.get().getPlannedDate()).isEqualTo(PLANNED_DATE);
        assertThat(captured.get().getSourceDisplayUnit()).isEqualTo("box");
        assertThat(result.getBatchDate()).isEqualTo(BATCH_DATE);
        assertThat(result.getPlannedDate()).isEqualTo(PLANNED_DATE);
        assertThat(result.getSourceDisplayUnit()).isEqualTo("box");
        assertPins(result);
    }

    @Test
    void repairUsesCompareAndSetAndChangesOnlyBatchDate() {
        ProductionPlan plan = existingPlan();
        when(planRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
        when(batchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID)).thenReturn(List.of());
        when(planRepository.saveAndFlush(plan)).thenReturn(plan);

        ProductionPlanDTO result = service.repairSalesPlanBatchDate(
                FACTORY_ID, PLAN_ID, PLANNED_DATE, BATCH_DATE);

        assertThat(result.getBatchDate()).isEqualTo(BATCH_DATE);
        assertThat(result.getPlannedDate()).isEqualTo(PLANNED_DATE);
        assertThat(result.getStatus()).isEqualTo(ProductionPlanStatus.PENDING);
        assertThat(result.getPlannedQuantity()).isEqualByComparingTo("5");
        assertPins(result);
        verify(planRepository).saveAndFlush(plan);
    }

    @Test
    void repairReplayIsNoOpAndDifferentExpectedValueIsRejected() {
        ProductionPlan plan = existingPlan();
        plan.setBatchDate(BATCH_DATE);
        when(planRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
        when(batchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID)).thenReturn(List.of());

        ProductionPlanDTO replay = service.repairSalesPlanBatchDate(
                FACTORY_ID, PLAN_ID, PLANNED_DATE, BATCH_DATE);
        assertThat(replay.getBatchDate()).isEqualTo(BATCH_DATE);
        verify(planRepository, never()).saveAndFlush(any());

        assertThatThrownBy(() -> service.repairSalesPlanBatchDate(
                FACTORY_ID, PLAN_ID, BATCH_DATE, LocalDate.of(2026, 7, 19)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已完成历史校正");
    }

    private static void assertPins(ProductionPlanDTO result) {
        assertThat(result.getSelectedWorkflowId()).isEqualTo(105L);
        assertThat(result.getSelectedWorkflowVersion()).isEqualTo(1);
        assertThat(result.getSelectedBomRecipeId()).isEqualTo("9e2eafed-9205-4627-aa4e-8acf20c460fd");
        assertThat(result.getSelectedBomVersion()).isEqualTo(1);
    }

    private ProductionPlan existingPlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("PLAN-1784523993145-78E6EE57");
        plan.setProductTypeId(PRODUCT_ID);
        plan.setPlannedQuantity(new BigDecimal("5"));
        plan.setPlannedUnit("盒");
        plan.setSourceDisplayQuantity(new BigDecimal("5"));
        plan.setSourceDisplayUnit("box");
        plan.setWorkflowOutputUnit("box");
        plan.setPlannedDate(PLANNED_DATE);
        plan.setBatchDate(PLANNED_DATE);
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        plan.setSelectedWorkflowId(105L);
        plan.setSelectedWorkflowVersion(1);
        plan.setSelectedBomRecipeId("9e2eafed-9205-4627-aa4e-8acf20c460fd");
        plan.setSelectedBomVersion(1);
        return plan;
    }

    private ProductionPlan planFrom(CreateProductionPlanRequest request) {
        ProductionPlan plan = existingPlan();
        plan.setBatchDate(request.getBatchDate());
        plan.setPlannedDate(request.getPlannedDate());
        plan.setPlannedQuantity(request.getPlannedQuantity());
        plan.setPlannedUnit(request.getPlannedUnit());
        plan.setSourceDisplayQuantity(request.getSourceDisplayQuantity());
        plan.setSourceDisplayUnit(request.getSourceDisplayUnit());
        return plan;
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field field = ProductionPlanServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
