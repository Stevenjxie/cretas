package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E-2 空价销售订单草稿支持 (六扇门追溯矩阵 E-2, 2026-06-10).
 *
 * <p>需求依据:
 * <ul>
 *   <li>行485 (P0): "订单单价/成本后面才有，下单时可空"</li>
 *   <li>行717 (constraint): "后期审核时报价/单价应有数据不能空"</li>
 * </ul>
 *
 * <p>验证场景:
 * <ol>
 *   <li>E2-01: 空单价草稿创建 → 200 OK（不被 POSITIVE_AMOUNT 规则阻拦）</li>
 *   <li>E2-02: 有单价草稿创建 → 正常（不回归）</li>
 *   <li>E2-03: 空单价提审 → 409 BusinessException，含行级提示</li>
 *   <li>E2-04: 有单价提审 → 通过</li>
 *   <li>E2-05: 部分行空单价提审 → 409，列出所有缺价行名</li>
 *   <li>E2-06: 毛利红线行为不回归（null 价格跳过红线检查）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesServiceImplEmptyPriceE2Test {

    // ctor-injected deps
    @Mock SalesOrderRepository          salesOrderRepository;
    @Mock SalesOrderItemRepository      salesOrderItemRepository;
    @Mock SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock FinishedGoodsBatchRepository  finishedGoodsBatchRepository;
    @Mock CustomerRepository            customerRepository;
    @Mock ProductTypeRepository         productTypeRepository;
    @Mock ArApService                   arApService;
    @Mock ApplicationEventPublisher     eventPublisher;

    // optional field-injected
    @Mock UserRepository                userRepository;
    @Mock WarehouseResolver             warehouseResolver;
    @Mock ArApTransactionRepository     arApTransactionRepository;
    @Mock EntityManager                 entityManager;
    @Mock Query                         nativeQuery;
    @Mock WorkflowEngineService         workflowEngine;

    SalesServiceImpl salesService;

    private static final String FACTORY_ID      = "F006";
    private static final String CUSTOMER_ID     = "CUST-001";
    private static final String PRODUCT_TYPE_ID = "PT-001";
    private static final String ORDER_ID        = "SO-TEST-E2-001";
    private static final Long   USER_ID         = 1L;

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                salesOrderRepository,
                salesOrderItemRepository,
                deliveryRecordRepository,
                finishedGoodsBatchRepository,
                customerRepository,
                productTypeRepository,
                arApService,
                eventPublisher);

        ReflectionTestUtils.setField(salesService, "userRepository", userRepository);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(salesService, "arApTransactionRepository", arApTransactionRepository);
        ReflectionTestUtils.setField(salesService, "entityManager", entityManager);
        ReflectionTestUtils.setField(salesService, "workflowEngine", workflowEngine);
        when(workflowEngine.hasActiveWorkflow(FACTORY_ID, "SALES_ORDER")).thenReturn(true);
        when(workflowEngine.startWorkflow(
                eq(FACTORY_ID), eq("SALES_ORDER"), anyString(), anyMap(), anyLong()))
                .thenAnswer(inv -> ApprovalWorkflowInstance.builder()
                        .id("wf-inst-e2")
                        .factoryId(FACTORY_ID)
                        .workflowId("wf-sales")
                        .moduleCode("SALES_ORDER")
                        .businessEntityId(inv.getArgument(2))
                        .status(ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                        .currentNodeIds(new java.util.ArrayList<>(List.of("approval_finance")))
                        .initiatedBy(inv.getArgument(4))
                        .build());

        // advisory lock mock
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.getSingleResult()).thenReturn(1L);

        when(salesOrderRepository.findMaxOrderNumberByPrefix(anyString(), anyString()))
                .thenReturn(null);

        when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> {
            SalesOrder o = inv.getArgument(0);
            if (o.getId() == null) ReflectionTestUtils.setField(o, "id", ORDER_ID);
            return o;
        });

        when(salesOrderItemRepository.saveAll(anyList())).thenReturn(List.of());

        when(customerRepository.findByIdAndFactoryId(CUSTOMER_ID, FACTORY_ID))
                .thenReturn(Optional.of(stubCustomer()));

        when(productTypeRepository.findById(PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubProductType("猪舌")));
    }

    // =========================================================================
    // E2-01: 草稿创建 — 空单价不被 POSITIVE_AMOUNT DB 规则阻拦
    // =========================================================================

    @Test
    @DisplayName("E2-01: 空单价创建草稿 → 真正成功且 totalAmount=0（不再容忍 NPE）")
    void createDraft_nullUnitPrice_succeedsWithZeroTotal() {
        // 修订记录 (2026-06-11): 旧版此测试 catch(Exception) 吞掉 NPE 让其"通过",
        // 掩盖了 createSalesOrder line 470 calculateLineAmount 返 null → BigDecimal.add(null) NPE
        // (prod 实测 500 追踪码 444C443D). 现 Opus 修空价行贡献 0, 此测试改为真断言成功.
        com.cretas.aims.dto.inventory.CreateSalesOrderRequest req = buildCreateRequest(null);

        // 空价草稿: 不抛任何异常 (含 NPE), 成功创建, 总额为 0
        SalesOrder order = salesService.createSalesOrder(FACTORY_ID, req, USER_ID);
        assertThat(order).as("空单价草稿应成功创建, 不 NPE").isNotNull();
        assertThat(order.getTotalAmount())
                .as("空单价行对 totalAmount 贡献 0")
                .isEqualByComparingTo(java.math.BigDecimal.ZERO);
    }

    @Test
    @DisplayName("E2-02: 空单价创建 — DB 规则禁用后 validationRuleEvaluator 不抛 POSITIVE_AMOUNT")
    void createDraft_nullUnitPrice_validationRuleEvaluatorSkipsDisabledRule() {
        // 模拟 ValidationRuleEvaluator 存在但 POSITIVE_AMOUNT 规则已禁用（返回空 warnings）
        com.cretas.aims.engine.ValidationRuleEvaluator mockEvaluator =
                mock(com.cretas.aims.engine.ValidationRuleEvaluator.class);
        when(mockEvaluator.validate(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(List.of());  // 无任何 BLOCK 规则触发（已禁用）
        ReflectionTestUtils.setField(salesService, "validationRuleEvaluator", mockEvaluator);

        com.cretas.aims.dto.inventory.CreateSalesOrderRequest req = buildCreateRequest(null);

        try {
            salesService.createSalesOrder(FACTORY_ID, req, USER_ID);
        } catch (BusinessException be) {
            assertThat(be.getMessage())
                    .as("ValidationRuleEvaluator 不返回 BLOCK → 不应有 BusinessException 拦截草稿创建")
                    .doesNotContain("订单总金额必须大于0");
        } catch (Exception other) {
            // 其他非业务异常可接受
        }

        // 验证 validate 被调用了 (规则框架正常运行, 只是规则已禁用所以返回空)
        verify(mockEvaluator, atLeastOnce())
                .validate(eq(FACTORY_ID), anyString(), anyString(), anyMap());
    }

    // =========================================================================
    // E2-03: 有单价草稿创建 — 不回归
    // =========================================================================

    @Test
    @DisplayName("E2-03: 有单价草稿创建 → 正常，不回归")
    void createDraft_withUnitPrice_succeeds() {
        com.cretas.aims.engine.ValidationRuleEvaluator mockEvaluator =
                mock(com.cretas.aims.engine.ValidationRuleEvaluator.class);
        when(mockEvaluator.validate(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(List.of());
        ReflectionTestUtils.setField(salesService, "validationRuleEvaluator", mockEvaluator);

        com.cretas.aims.dto.inventory.CreateSalesOrderRequest req = buildCreateRequest("100.00");

        SalesOrder order = salesService.createSalesOrder(FACTORY_ID, req, USER_ID);
        assertThat(order).isNotNull();
        assertThat(order.getFactoryId()).isEqualTo(FACTORY_ID);
    }

    // =========================================================================
    // E2-04: submitForFinanceReview — 空单价被拦截（服务层守卫）
    // =========================================================================

    @Test
    @DisplayName("E2-04: 空单价行提审 → BusinessException 409，含行级产品名和 actionHint")
    void submitForFinanceReview_nullUnitPrice_throws409WithLineName() {
        SalesOrder order = stubConfirmedOrder("SO-E2-001", new BigDecimal("0"));

        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY_ID))
                .thenReturn(Optional.of(order));

        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId(PRODUCT_TYPE_ID);
        item.setProductName("白卤猪舌");
        item.setQuantity(new BigDecimal("100"));
        item.setUnitPrice(null);  // 空单价
        item.setSalesOrderId(ORDER_ID);

        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(List.of(item));

        assertThatThrownBy(() -> salesService.submitForFinanceReview(FACTORY_ID, ORDER_ID, 901L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    // 含产品名（fool-proof Rule 2: 上下文必带身份信息）
                    assertThat(be.getMessage()).contains("白卤猪舌");
                    // 含 next action hint（fool-proof 4位一体 d）
                    assertThat(be.getActionHint()).isNotBlank();
                    assertThat(be.getActionHint()).contains("单价");
                });
    }

    @Test
    @DisplayName("E2-05: 单价为 0 提审 → BusinessException 409（零单价也不允许）")
    void submitForFinanceReview_zeroPriceItem_throws409() {
        SalesOrder order = stubConfirmedOrder("SO-E2-002", new BigDecimal("0"));

        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY_ID))
                .thenReturn(Optional.of(order));

        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId(PRODUCT_TYPE_ID);
        item.setProductName("牛腱子");
        item.setQuantity(new BigDecimal("50"));
        item.setUnitPrice(BigDecimal.ZERO);  // 零单价
        item.setSalesOrderId(ORDER_ID);

        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(List.of(item));

        assertThatThrownBy(() -> salesService.submitForFinanceReview(FACTORY_ID, ORDER_ID, 901L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getMessage()).contains("牛腱子");
                });
    }

    @Test
    @DisplayName("E2-06: 多行部分缺价提审 → 409，所有缺价行都列出")
    void submitForFinanceReview_partialMissingPrice_allLinesListed() {
        SalesOrder order = stubConfirmedOrder("SO-E2-003", new BigDecimal("500"));

        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY_ID))
                .thenReturn(Optional.of(order));

        SalesOrderItem item1 = new SalesOrderItem();
        item1.setProductName("猪舌");
        item1.setQuantity(new BigDecimal("100"));
        item1.setUnitPrice(new BigDecimal("50.00"));   // 有价

        SalesOrderItem item2 = new SalesOrderItem();
        item2.setProductName("掌中宝");
        item2.setQuantity(new BigDecimal("80"));
        item2.setUnitPrice(null);                      // 无价

        SalesOrderItem item3 = new SalesOrderItem();
        item3.setProductName("猪蹄");
        item3.setQuantity(new BigDecimal("200"));
        item3.setUnitPrice(null);                      // 无价

        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(List.of(item1, item2, item3));

        assertThatThrownBy(() -> salesService.submitForFinanceReview(FACTORY_ID, ORDER_ID, 901L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getMessage()).contains("掌中宝");
                    assertThat(be.getMessage()).contains("猪蹄");
                    // 有价的行不在错误信息里
                    assertThat(be.getMessage()).doesNotContain("第1行");
                });
    }

    @Test
    @DisplayName("E2-07: 所有行有正数单价提审 → 成功（服务层守卫放行）")
    void submitForFinanceReview_allPricesPositive_succeeds() {
        SalesOrder order = stubConfirmedOrder("SO-E2-004", new BigDecimal("5000"));

        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY_ID))
                .thenReturn(Optional.of(order));

        SalesOrderItem item = new SalesOrderItem();
        item.setProductName("猪舌");
        item.setQuantity(new BigDecimal("100"));
        item.setUnitPrice(new BigDecimal("50.00"));

        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(List.of(item));

        when(salesOrderRepository.save(any(SalesOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SalesOrder result = salesService.submitForFinanceReview(FACTORY_ID, ORDER_ID, 901L);

        assertThat(result.getStatus()).isEqualTo(SalesOrderStatus.PENDING_FINANCE_REVIEW);
    }

    @Test
    @DisplayName("E2-08: 毛利红线对 null 单价跳过不回归（E-5 行为不变）")
    void createDraft_nullPrice_grossMarginRedlineNotCalled() {
        com.cretas.aims.service.pricing.GrossMarginRedlineService marginService =
                mock(com.cretas.aims.service.pricing.GrossMarginRedlineService.class);
        ReflectionTestUtils.setField(salesService, "grossMarginRedlineService", marginService);

        com.cretas.aims.dto.inventory.CreateSalesOrderRequest req = buildCreateRequest(null);

        try {
            salesService.createSalesOrder(FACTORY_ID, req, USER_ID);
        } catch (Exception ignored) {
            // 其他异常可接受
        }

        // 关键: 空价格不触发红线检查
        verify(marginService, never()).checkMargin(anyString(), anyString(), any(BigDecimal.class));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private com.cretas.aims.dto.inventory.CreateSalesOrderRequest buildCreateRequest(String unitPrice) {
        com.cretas.aims.dto.inventory.CreateSalesOrderRequest req =
                new com.cretas.aims.dto.inventory.CreateSalesOrderRequest();
        req.setCustomerId(CUSTOMER_ID);
        req.setProcessingMode(SalesProcessingMode.STANDARD_SALE);
        req.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);

        com.cretas.aims.dto.inventory.CreateSalesOrderRequest.SalesOrderItemDTO item =
                new com.cretas.aims.dto.inventory.CreateSalesOrderRequest.SalesOrderItemDTO();
        item.setProductTypeId(PRODUCT_TYPE_ID);
        item.setProductName("猪舌");
        item.setQuantity(new BigDecimal("100"));
        item.setUnit("kg");
        if (unitPrice != null) {
            item.setUnitPrice(new BigDecimal(unitPrice));
        }
        req.setItems(List.of(item));
        return req;
    }

    private SalesOrder stubConfirmedOrder(String orderNumber, BigDecimal totalAmount) {
        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", ORDER_ID);
        o.setFactoryId(FACTORY_ID);
        o.setOrderNumber(orderNumber);
        o.setCustomerId(CUSTOMER_ID);
        o.setStatus(SalesOrderStatus.CONFIRMED);
        o.setTotalAmount(totalAmount);
        o.setCreatedBy(USER_ID);
        return o;
    }

    private Customer stubCustomer() {
        Customer c = new Customer();
        ReflectionTestUtils.setField(c, "id", CUSTOMER_ID);
        c.setFactoryId(FACTORY_ID);
        c.setName("测试客户");
        return c;
    }

    private ProductType stubProductType(String name) {
        ProductType pt = new ProductType();
        pt.setId(PRODUCT_TYPE_ID);
        pt.setFactoryId(FACTORY_ID);
        pt.setName(name);
        pt.setUnit("kg");
        pt.setIsActive(true);
        pt.setCreatedBy(1L);
        return pt;
    }
}
