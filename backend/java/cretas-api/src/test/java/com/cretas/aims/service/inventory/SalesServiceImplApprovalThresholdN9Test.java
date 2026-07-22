package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.event.SalesOrderConfirmedEvent;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesServiceImplApprovalThresholdN9Test {

    private static final String FACTORY = "F006";
    private static final String ORDER_ID = "SO-ID-1";
    private static final Long INITIATOR = 901L;

    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private WorkflowEngineService workflowEngine;

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
        ReflectionTestUtils.setField(salesService, "workflowEngine", workflowEngine);
        lenient().when(salesOrderRepository.save(any(SalesOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void confirm_starts_persisted_oa_with_actual_jwt_initiator() {
        SalesOrder order = order(SalesOrderStatus.DRAFT);
        mockConfirmInput(order);
        when(workflowEngine.hasActiveWorkflow(FACTORY, "SALES_ORDER")).thenReturn(true);
        ApprovalWorkflowInstance running = instance(InstanceStatus.RUNNING, INITIATOR);
        when(workflowEngine.startWorkflow(
                eq(FACTORY), eq("SALES_ORDER"), eq(ORDER_ID), anyMap(), eq(INITIATOR)))
                .thenReturn(running);

        SalesOrder result = salesService.confirmOrder(FACTORY, ORDER_ID, INITIATOR);

        assertEquals(SalesOrderStatus.PENDING_FINANCE_REVIEW, result.getStatus());
        verify(workflowEngine).startWorkflow(
                eq(FACTORY), eq("SALES_ORDER"), eq(ORDER_ID), anyMap(), eq(INITIATOR));
        verify(eventPublisher).publishEvent(any(SalesOrderConfirmedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(SalesOrderFinanceApprovedEvent.class));
    }

    @Test
    void confirm_auto_route_still_persists_terminal_oa_instance() {
        SalesOrder order = order(SalesOrderStatus.DRAFT);
        mockConfirmInput(order);
        when(workflowEngine.hasActiveWorkflow(FACTORY, "SALES_ORDER")).thenReturn(true);
        when(workflowEngine.startWorkflow(
                eq(FACTORY), eq("SALES_ORDER"), eq(ORDER_ID), anyMap(), eq(INITIATOR)))
                .thenReturn(instance(InstanceStatus.APPROVED, INITIATOR));

        SalesOrder result = salesService.confirmOrder(FACTORY, ORDER_ID, INITIATOR);

        assertEquals(SalesOrderStatus.FINANCE_APPROVED, result.getStatus());
        verify(workflowEngine).startWorkflow(
                eq(FACTORY), eq("SALES_ORDER"), eq(ORDER_ID), anyMap(), eq(INITIATOR));
        verify(eventPublisher).publishEvent(any(SalesOrderFinanceApprovedEvent.class));
    }

    @Test
    void confirm_without_active_sales_graph_fails_before_domain_mutation() {
        SalesOrder order = order(SalesOrderStatus.DRAFT);
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));
        when(workflowEngine.hasActiveWorkflow(FACTORY, "SALES_ORDER")).thenReturn(false);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> salesService.confirmOrder(FACTORY, ORDER_ID, INITIATOR));

        assertEquals("SALES_APPROVAL_WORKFLOW_REQUIRED", error.getErrorCode());
        assertEquals(SalesOrderStatus.DRAFT, order.getStatus());
        verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        verify(eventPublisher, never()).publishEvent(any(SalesOrderConfirmedEvent.class));
    }

    @Test
    void repeated_confirm_under_order_lock_starts_only_one_oa_instance() {
        SalesOrder order = order(SalesOrderStatus.DRAFT);
        mockConfirmInput(order);
        when(workflowEngine.hasActiveWorkflow(FACTORY, "SALES_ORDER")).thenReturn(true);
        when(workflowEngine.startWorkflow(
                eq(FACTORY), eq("SALES_ORDER"), eq(ORDER_ID), anyMap(), eq(INITIATOR)))
                .thenReturn(instance(InstanceStatus.RUNNING, INITIATOR));

        SalesOrder first = salesService.confirmOrder(FACTORY, ORDER_ID, INITIATOR);
        BusinessException replay = assertThrows(
                BusinessException.class,
                () -> salesService.confirmOrder(FACTORY, ORDER_ID, INITIATOR));

        assertEquals(SalesOrderStatus.PENDING_FINANCE_REVIEW, first.getStatus());
        assertEquals(409, replay.getCode());
        verify(salesOrderRepository, times(2))
                .findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY);
        verify(workflowEngine, times(1)).startWorkflow(
                eq(FACTORY), eq("SALES_ORDER"), eq(ORDER_ID), anyMap(), eq(INITIATOR));
    }

    @Test
    void oa_approve_projects_terminal_state_back_to_sales_order() {
        SalesOrder order = order(SalesOrderStatus.PENDING_FINANCE_REVIEW);
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));
        ApprovalWorkflowInstance running = instance(InstanceStatus.RUNNING, 101L);
        when(workflowEngine.getInstance(FACTORY, "inst-1")).thenReturn(Optional.of(running));
        when(workflowEngine.transitionNode(
                "inst-1", 202L, "finance_manager", HistoryAction.APPROVE, "approved"))
                .thenReturn(instance(InstanceStatus.APPROVED, 101L));

        SalesOrder result = salesService.applyWorkflowAction(
                FACTORY, ORDER_ID, "inst-1", 202L, "finance_manager",
                HistoryAction.APPROVE, "approved");

        assertEquals(SalesOrderStatus.FINANCE_APPROVED, result.getStatus());
        assertEquals(202L, result.getFinanceReviewedBy());
        verify(eventPublisher).publishEvent(any(SalesOrderFinanceApprovedEvent.class));
    }

    @Test
    void oa_reject_projects_reason_back_to_sales_order() {
        SalesOrder order = order(SalesOrderStatus.PENDING_FINANCE_REVIEW);
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));
        ApprovalWorkflowInstance running = instance(InstanceStatus.RUNNING, 101L);
        when(workflowEngine.getInstance(FACTORY, "inst-1")).thenReturn(Optional.of(running));
        when(workflowEngine.transitionNode(
                "inst-1", 202L, "finance_manager", HistoryAction.REJECT, "price mismatch"))
                .thenReturn(instance(InstanceStatus.REJECTED, 101L));

        SalesOrder result = salesService.applyWorkflowAction(
                FACTORY, ORDER_ID, "inst-1", 202L, "finance_manager",
                HistoryAction.REJECT, "price mismatch");

        assertEquals(SalesOrderStatus.FINANCE_REJECTED, result.getStatus());
        assertEquals("price mismatch", result.getFinanceReviewNotes());
    }

    @Test
    void direct_finance_endpoint_cannot_bypass_existing_oa_instance() {
        SalesOrder order = order(SalesOrderStatus.PENDING_FINANCE_REVIEW);
        when(salesOrderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(order));
        when(workflowEngine.getLatestInstance(FACTORY, "SALES_ORDER", ORDER_ID))
                .thenReturn(Optional.of(instance(InstanceStatus.RUNNING, 101L)));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> salesService.financeApproveOrder(FACTORY, ORDER_ID, "bypass", 202L));
        BusinessException rejectError = assertThrows(
                BusinessException.class,
                () -> salesService.financeRejectOrder(FACTORY, ORDER_ID, "bypass", 202L));

        assertEquals("SALES_APPROVAL_OA_ONLY", error.getErrorCode());
        assertEquals("SALES_APPROVAL_OA_ONLY", rejectError.getErrorCode());
        assertEquals(SalesOrderStatus.PENDING_FINANCE_REVIEW, order.getStatus());
        verify(salesOrderRepository, never()).save(any(SalesOrder.class));
    }

    private void mockConfirmInput(SalesOrder order) {
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate(ORDER_ID, FACTORY))
                .thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID)).thenReturn(List.of(item()));
    }

    private SalesOrder order(SalesOrderStatus status) {
        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY);
        order.setOrderNumber("SO-20260722-0001");
        order.setCustomerId("C-001");
        order.setOrderDate(LocalDate.of(2026, 7, 22));
        order.setCreatedBy(101L);
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("6000.00"));
        order.setItems(new ArrayList<>());
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

    private ApprovalWorkflowInstance instance(InstanceStatus status, Long initiatedBy) {
        return ApprovalWorkflowInstance.builder()
                .id("inst-1")
                .factoryId(FACTORY)
                .workflowId("wf-sales")
                .moduleCode("SALES_ORDER")
                .businessEntityId(ORDER_ID)
                .status(status)
                .currentNodeIds(status == InstanceStatus.RUNNING
                        ? new ArrayList<>(List.of("approval_finance"))
                        : new ArrayList<>())
                .contextJson(Map.of())
                .initiatedBy(initiatedBy)
                .build();
    }
}
