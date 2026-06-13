package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
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
        assertEquals("未触发销售审批阈值或满足免审配置，自动通过", result.getFinanceReviewNotes());
        verify(eventPublisher).publishEvent(any(SalesOrderFinanceApprovedEvent.class));
    }

    @Test
    void confirm_external_channel_order_auto_finance_approves_by_config() {
        SalesOrder order = salesOrder(SalesOrderStatus.DRAFT, "6000.00");
        order.setExternalOrderTitle("external-channel-0601-T2");
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(item()));
        when(approvalChainService.requiresApproval(eq(FACTORY), eq(DecisionType.SALES_ORDER_APPROVAL), anyMap()))
                .thenReturn(false);

        SalesOrder result = salesService.confirmOrder(FACTORY, ORDER_ID);

        assertEquals(SalesOrderStatus.FINANCE_APPROVED, result.getStatus());
        assertEquals("未触发销售审批阈值或满足免审配置，自动通过", result.getFinanceReviewNotes());
        verify(approvalChainService)
                .requiresApproval(eq(FACTORY), eq(DecisionType.SALES_ORDER_APPROVAL), anyMap());
        verify(eventPublisher).publishEvent(any(SalesOrderFinanceApprovedEvent.class));
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
