package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.enums.CustomerSource;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesServiceImplApprovalThresholdN9Test {

    private static final String FACTORY = "F006";
    private static final String ORDER_ID = "SO-ID-1";

    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ApprovalChainService approvalChainService;
    @Mock private FactoryConfigService factoryConfigService;

    private SalesServiceImpl salesService;

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
        ReflectionTestUtils.setField(salesService, "approvalChainService", approvalChainService);
        when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(approvalChainService.getConfigsByDecisionType(FACTORY, DecisionType.SALES_ORDER_APPROVAL))
                .thenReturn(List.of(ApprovalChainConfig.builder()
                        .factoryId(FACTORY)
                        .decisionType(DecisionType.SALES_ORDER_APPROVAL)
                        .name("sales amount threshold")
                        .approvalLevel(1)
                        .approverRoles("[\"finance_manager\"]")
                        .build()));
    }

    @Test
    void confirm_above_threshold_routes_to_pending_finance_review() {
        SalesOrder order = salesOrder(SalesOrderStatus.DRAFT, "6000.00");
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(item()));
        when(approvalChainService.requiresApproval(eq(FACTORY), eq(DecisionType.SALES_ORDER_APPROVAL), anyMap()))
                .thenReturn(true);

        SalesOrder result = salesService.confirmOrder(FACTORY, ORDER_ID);

        assertEquals(SalesOrderStatus.PENDING_FINANCE_REVIEW, result.getStatus());
        verify(eventPublisher, never()).publishEvent(any(SalesOrderFinanceApprovedEvent.class));
    }

    @Test
    void confirm_below_threshold_auto_finance_approves() {
        SalesOrder order = salesOrder(SalesOrderStatus.DRAFT, "4000.00");
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(item()));
        when(approvalChainService.requiresApproval(eq(FACTORY), eq(DecisionType.SALES_ORDER_APPROVAL), anyMap()))
                .thenReturn(false);

        SalesOrder result = salesService.confirmOrder(FACTORY, ORDER_ID);

        assertEquals(SalesOrderStatus.FINANCE_APPROVED, result.getStatus());
        verify(approvalChainService)
                .requiresApproval(eq(FACTORY), eq(DecisionType.SALES_ORDER_APPROVAL), anyMap());
        verify(eventPublisher).publishEvent(any(SalesOrderFinanceApprovedEvent.class));
    }

    @Test
    void confirm_external_order_title_auto_finance_approves_without_threshold_check() {
        SalesOrder order = salesOrder(SalesOrderStatus.DRAFT, "6000.00");
        order.setExternalOrderTitle("external-channel-0601-T2");
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(item()));

        SalesOrder result = salesService.confirmOrder(FACTORY, ORDER_ID);

        assertEquals(SalesOrderStatus.FINANCE_APPROVED, result.getStatus());
        verify(approvalChainService, never())
                .requiresApproval(eq(FACTORY), eq(DecisionType.SALES_ORDER_APPROVAL), anyMap());
        verify(eventPublisher).publishEvent(any(SalesOrderFinanceApprovedEvent.class));
    }

    @Test
    void confirm_platform_customer_auto_finance_approves_without_channel_specific_logic() {
        SalesOrder order = salesOrder(SalesOrderStatus.DRAFT, "6000.00");
        Customer customer = new Customer();
        customer.setId("C-001");
        customer.setFactoryId(FACTORY);
        customer.setName("Marketplace Customer");
        customer.setSource(CustomerSource.PLATFORM);

        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(item()));
        when(customerRepository.findByIdAndFactoryId("C-001", FACTORY)).thenReturn(Optional.of(customer));

        SalesOrder result = salesService.confirmOrder(FACTORY, ORDER_ID);

        assertEquals(SalesOrderStatus.FINANCE_APPROVED, result.getStatus());
        verify(approvalChainService, never())
                .requiresApproval(eq(FACTORY), eq(DecisionType.SALES_ORDER_APPROVAL), anyMap());
        verify(eventPublisher).publishEvent(any(SalesOrderFinanceApprovedEvent.class));
    }

    /**
     * 六扇门财审阈值状态机回归: 即便 sales_order 状态机 schema 缺少 CONFIRMED → FINANCE_APPROVED
     * 直通边 (真实 seed 只有 CONFIRMED → PENDING_FINANCE_REVIEW → FINANCE_APPROVED),
     * 低额订单自动通过路径也必须能落到 FINANCE_APPROVED, 不能 409 卡死在 DRAFT/CONFIRMED.
     *
     * <p>这是真实 prod/test env 暴露的 bug: 之前 N9 单测 factoryConfigService=null → checkTransitionAllowed
     * 默认 ALLOW, mock 通过但真实带 schema 配置的环境 409. 本测试显式 wire schema 以复现.
     */
    @Test
    void confirm_below_threshold_auto_approves_even_when_schema_lacks_direct_confirmed_to_approved_edge() {
        ReflectionTestUtils.setField(salesService, "factoryConfigService", factoryConfigService);
        // 模拟真实 seed: 缺 CONFIRMED → FINANCE_APPROVED, 但有合法中间边
        when(factoryConfigService.isTransitionAllowed(FACTORY, "sales_order", "DRAFT", "CONFIRMED"))
                .thenReturn(true);
        when(factoryConfigService.isTransitionAllowed(FACTORY, "sales_order", "CONFIRMED", "PENDING_FINANCE_REVIEW"))
                .thenReturn(true);
        when(factoryConfigService.isTransitionAllowed(FACTORY, "sales_order", "PENDING_FINANCE_REVIEW", "FINANCE_APPROVED"))
                .thenReturn(true);
        // CONFIRMED → FINANCE_APPROVED 显式 NOT allowed (真实 schema 没有这条边).
        // lenient: 修复后此路径绝不应被查询 (走中间态), stub 留作意图文档; 若被查询会拿到 false → 409.
        org.mockito.Mockito.lenient()
                .when(factoryConfigService.isTransitionAllowed(FACTORY, "sales_order", "CONFIRMED", "FINANCE_APPROVED"))
                .thenReturn(false);

        SalesOrder order = salesOrder(SalesOrderStatus.DRAFT, "4000.00");
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(item()));
        when(approvalChainService.requiresApproval(eq(FACTORY), eq(DecisionType.SALES_ORDER_APPROVAL), anyMap()))
                .thenReturn(false);

        SalesOrder result = salesService.confirmOrder(FACTORY, ORDER_ID);

        assertEquals(SalesOrderStatus.FINANCE_APPROVED, result.getStatus());
        verify(eventPublisher).publishEvent(any(SalesOrderFinanceApprovedEvent.class));
    }

    /**
     * 高额订单仍走人工审批路径 (CONFIRMED → PENDING_FINANCE_REVIEW), 不自动通过.
     * 防止本次修复把高额单也误自动放过.
     */
    @Test
    void confirm_above_threshold_still_routes_to_pending_finance_review_with_schema() {
        ReflectionTestUtils.setField(salesService, "factoryConfigService", factoryConfigService);
        when(factoryConfigService.isTransitionAllowed(FACTORY, "sales_order", "DRAFT", "CONFIRMED"))
                .thenReturn(true);
        when(factoryConfigService.isTransitionAllowed(FACTORY, "sales_order", "CONFIRMED", "PENDING_FINANCE_REVIEW"))
                .thenReturn(true);

        SalesOrder order = salesOrder(SalesOrderStatus.DRAFT, "6000.00");
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(item()));
        when(approvalChainService.requiresApproval(eq(FACTORY), eq(DecisionType.SALES_ORDER_APPROVAL), anyMap()))
                .thenReturn(true);

        SalesOrder result = salesService.confirmOrder(FACTORY, ORDER_ID);

        assertEquals(SalesOrderStatus.PENDING_FINANCE_REVIEW, result.getStatus());
        verify(eventPublisher, never()).publishEvent(any(SalesOrderFinanceApprovedEvent.class));
    }

    private SalesOrder salesOrder(SalesOrderStatus status, String totalAmount) {
        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY);
        order.setOrderNumber("SO-20260612-001");
        order.setCustomerId("C-001");
        order.setOrderDate(LocalDate.of(2026, 6, 12));
        order.setCreatedBy(101L);
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal(totalAmount));
        return order;
    }

    private SalesOrderItem item() {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId("PT-001");
        item.setProductName("Product A");
        item.setQuantity(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("10.00"));
        return item;
    }
}
