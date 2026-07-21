package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PurchaseServiceOaSubmissionTest {

    @Mock PurchaseOrderRepository orderRepository;
    @Mock PurchaseOrderItemRepository itemRepository;
    @Mock PurchaseReceiveRecordRepository receiveRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock RawMaterialTypeRepository materialRepository;
    @Mock MaterialBatchRepository batchRepository;
    @Mock BomRecipeItemRepository bomRepository;
    @Mock com.cretas.aims.service.finance.ArApService arApService;
    @Mock ApplicationEventPublisher events;
    @Mock MaterialBatchService batchService;
    @Mock SupplierMaterialRepository supplierMaterialRepository;
    @Mock SupplierMaterialPurchaseSpecRepository purchaseSpecRepository;
    @Mock UnitContractService unitContractService;
    @Mock WorkflowEngineService workflowEngine;
    @Mock ApprovalWorkflowService approvalWorkflowService;
    @Mock UserRepository userRepository;

    PurchaseServiceImpl service;
    PurchaseOrder order;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(orderRepository, itemRepository, receiveRepository,
                supplierRepository, materialRepository, batchRepository, bomRepository,
                arApService, events, batchService);
        ReflectionTestUtils.setField(service, "supplierMaterialRepository", supplierMaterialRepository);
        ReflectionTestUtils.setField(service, "supplierMaterialPurchaseSpecRepository", purchaseSpecRepository);
        ReflectionTestUtils.setField(service, "unitContractService", unitContractService);
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
        ReflectionTestUtils.setField(service, "approvalWorkflowService", approvalWorkflowService);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "self", service);

        order = new PurchaseOrder();
        order.setId("po-1");
        order.setFactoryId("F006");
        order.setOrderNumber("PO-TEST-1");
        order.setSupplierId("supplier-1");
        order.setStatus(PurchaseOrderStatus.DRAFT);
        order.setTotalAmount(new BigDecimal("100"));
        order.setTaxAmount(new BigDecimal("13"));

        Supplier supplier = new Supplier();
        supplier.setId("supplier-1");
        supplier.setFactoryId("F006");
        supplier.setIsActive(true);
        lenient().when(supplierRepository.findByIdAndFactoryId("supplier-1", "F006"))
                .thenReturn(Optional.of(supplier));
        when(orderRepository.findByIdAndFactoryIdForUpdate("po-1", "F006"))
                .thenReturn(Optional.of(order));
        lenient().when(orderRepository.save(any(PurchaseOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(1L);
        item.setPurchaseOrderId("po-1");
        item.setMaterialTypeId("material-1");
        item.setMaterialName("原料A");
        item.setQuantity(BigDecimal.TEN);
        item.setUnit("kg");
        item.setPriceUnit("kg");
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        item.setUnitPrice(BigDecimal.TEN);
        lenient().when(itemRepository.findByPurchaseOrderId("po-1")).thenReturn(List.of(item));
        lenient().when(supplierMaterialRepository
                .existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
                        "F006", "supplier-1", "material-1"))
                .thenReturn(true);
        CanonicalUnit kg = new CanonicalUnit("kg", UnitDimension.MASS, "kg",
                BigDecimal.ONE, "公斤", 3);
        lenient().when(unitContractService.normalize(eq("F006"), anyString()))
                .thenAnswer(inv -> new UnitNormalizationResult(
                        inv.getArgument(1), "kg", kg));

        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setId("workflow-1");
        workflow.setFactoryId("F006");
        workflow.setNodesJson("[]");
        lenient().when(approvalWorkflowService.getById("F006", "workflow-1"))
                .thenReturn(Optional.of(workflow));
        lenient().when(approvalWorkflowService.deserializeNodes("[]"))
                .thenReturn(List.of(ApprovalWorkflowNode.builder()
                        .id("finance-approval")
                        .type("approval")
                        .label("财务审批")
                        .config(Map.of("approverRoles", List.of("finance_manager")))
                        .build()));
        User approver = new User();
        approver.setId(2001L);
        approver.setFactoryId("F006");
        approver.setRoleCode("finance_manager");
        approver.setIsActive(true);
        lenient().when(userRepository.findByFactoryIdAndRoleCode("F006", "finance_manager"))
                .thenReturn(List.of(approver));
    }

    private ApprovalWorkflowInstance instance(ApprovalWorkflowInstance.InstanceStatus status) {
        return ApprovalWorkflowInstance.builder()
                .id("instance-1")
                .factoryId("F006")
                .workflowId("workflow-1")
                .moduleCode("PURCHASE_ORDER")
                .businessEntityId("po-1")
                .status(status)
                .currentNodeIds(status == ApprovalWorkflowInstance.InstanceStatus.RUNNING
                        ? List.of("finance-approval") : List.of())
                .contextJson(Map.of())
                .initiatedBy(1309L)
                .build();
    }

    @Test
    void firstSubmitCreatesOneVisibleOaInstanceAndProjectsRunningState() {
        when(workflowEngine.hasActiveWorkflow("F006", "PURCHASE_ORDER")).thenReturn(true);
        when(workflowEngine.startWorkflow(eq("F006"), eq("PURCHASE_ORDER"), eq("po-1"),
                anyMap(), eq(1309L))).thenReturn(instance(ApprovalWorkflowInstance.InstanceStatus.RUNNING));

        PurchaseOrder result = service.submitOrder("F006", "po-1", 1309L);

        assertThat(result.getStatus()).isEqualTo(PurchaseOrderStatus.WORKFLOW_RUNNING);
        verify(workflowEngine, times(1)).startWorkflow(eq("F006"), eq("PURCHASE_ORDER"),
                eq("po-1"), anyMap(), eq(1309L));
    }

    @Test
    void repeatedSubmitReturnsExistingTruthWithoutSecondInstance() {
        order.setStatus(PurchaseOrderStatus.WORKFLOW_RUNNING);
        when(workflowEngine.getLatestInstance("F006", "PURCHASE_ORDER", "po-1"))
                .thenReturn(Optional.of(instance(ApprovalWorkflowInstance.InstanceStatus.RUNNING)));

        PurchaseOrder result = service.submitOrder("F006", "po-1", 1309L);

        assertThat(result).isSameAs(order);
        verify(workflowEngine, never()).startWorkflow(anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    void missingWorkflowFailsClosedAndLeavesDraft() {
        when(workflowEngine.hasActiveWorkflow("F006", "PURCHASE_ORDER")).thenReturn(false);

        assertThatThrownBy(() -> service.submitOrder("F006", "po-1", 1309L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("OA 审批流程");
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        verify(workflowEngine, never()).startWorkflow(anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    void autoApprovedWorkflowCompletesWholeBusinessAndFinanceChain() {
        when(workflowEngine.hasActiveWorkflow("F006", "PURCHASE_ORDER")).thenReturn(true);
        when(workflowEngine.startWorkflow(eq("F006"), eq("PURCHASE_ORDER"), eq("po-1"),
                anyMap(), eq(1309L))).thenReturn(instance(ApprovalWorkflowInstance.InstanceStatus.APPROVED));

        PurchaseOrder result = service.submitOrder("F006", "po-1", 1309L);

        assertThat(result.getStatus()).isEqualTo(PurchaseOrderStatus.FINANCE_APPROVED);
    }

    @Test
    void oaActionRejectsSelfApprovalBeforeTransition() {
        order.setStatus(PurchaseOrderStatus.WORKFLOW_RUNNING);
        when(workflowEngine.getInstance("F006", "instance-1"))
                .thenReturn(Optional.of(instance(ApprovalWorkflowInstance.InstanceStatus.RUNNING)));

        assertThatThrownBy(() -> service.applyWorkflowAction("F006", "po-1", "instance-1",
                1309L, "procurement_manager", HistoryAction.APPROVE, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能审批自己的采购单");
        verify(workflowEngine, never()).transitionNode(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void terminalOaActionReplayIsPureReadWithoutTimestampOrVersionWrite() {
        order.setStatus(PurchaseOrderStatus.FINANCE_APPROVED);
        order.setApprovedAt(java.time.LocalDateTime.of(2026, 7, 21, 10, 0));
        ApprovalWorkflowInstance completed = instance(ApprovalWorkflowInstance.InstanceStatus.APPROVED);
        when(workflowEngine.getInstance("F006", "instance-1"))
                .thenReturn(Optional.of(completed));

        PurchaseOrder result = service.applyWorkflowAction("F006", "po-1", "instance-1",
                2001L, "finance_manager", HistoryAction.APPROVE, null);

        assertThat(result).isSameAs(order);
        assertThat(result.getApprovedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 7, 21, 10, 0));
        verify(workflowEngine, never()).transitionNode(anyString(), any(), anyString(), any(), any());
        verify(orderRepository, never()).save(any(PurchaseOrder.class));
    }
}
