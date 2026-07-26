package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.InternalTransferItemRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferOaApprovalIntegrationTest {

    private static final String FACTORY = "F006";
    private static final String TRANSFER_ID = "trf-oa-1";
    private static final Long INITIATOR = 1309L;

    @Mock private InternalTransferRepository transferRepository;
    @Mock private InternalTransferItemRepository transferItemRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private WorkflowEngineService workflowEngine;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private UserRepository userRepository;

    private TransferServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TransferServiceImpl(
                transferRepository,
                transferItemRepository,
                materialBatchRepository,
                finishedGoodsBatchRepository,
                applicationEventPublisher,
                materialBatchService,
                rawMaterialTypeRepository);
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
        ReflectionTestUtils.setField(service, "approvalWorkflowService", approvalWorkflowService);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
    }

    @Test
    void submit_starts_one_unified_oa_instance_and_projects_requested() {
        InternalTransfer transfer = draftTransfer();
        ApprovalWorkflowInstance running = runningInstance(INITIATOR);
        when(transferRepository.findByIdAndEitherFactoryId(TRANSFER_ID, FACTORY))
                .thenReturn(Optional.of(transfer));
        when(workflowEngine.getLatestInstance(FACTORY, "INVENTORY_TRANSFER", TRANSFER_ID))
                .thenReturn(Optional.empty());
        when(workflowEngine.startWorkflowIfConfigured(
                eq(FACTORY), eq("INVENTORY_TRANSFER"), eq(TRANSFER_ID), any(), eq(INITIATOR)))
                .thenReturn(Optional.of(running));
        mockRunnableRoute(running, 2001L);
        when(transferRepository.save(transfer)).thenReturn(transfer);

        InternalTransfer result = service.requestTransfer(FACTORY, TRANSFER_ID, INITIATOR);

        assertEquals(TransferStatus.REQUESTED, result.getStatus());
        assertEquals(INITIATOR, result.getRequestedBy());
        verify(workflowEngine).startWorkflowIfConfigured(
                eq(FACTORY), eq("INVENTORY_TRANSFER"), eq(TRANSFER_ID), any(), eq(INITIATOR));
        verify(transferRepository).save(transfer);
    }

    @Test
    void missing_route_means_no_approval_and_directly_approves() {
        InternalTransfer transfer = draftTransfer();
        when(transferRepository.findByIdAndEitherFactoryId(TRANSFER_ID, FACTORY))
                .thenReturn(Optional.of(transfer));
        when(workflowEngine.getLatestInstance(FACTORY, "INVENTORY_TRANSFER", TRANSFER_ID))
                .thenReturn(Optional.empty());
        when(workflowEngine.startWorkflowIfConfigured(
                eq(FACTORY), eq("INVENTORY_TRANSFER"), eq(TRANSFER_ID), any(), eq(INITIATOR)))
                .thenReturn(Optional.empty());
        when(transferRepository.save(transfer)).thenReturn(transfer);

        InternalTransfer result = service.requestTransfer(FACTORY, TRANSFER_ID, INITIATOR);

        assertEquals(TransferStatus.APPROVED, result.getStatus());
        assertEquals(INITIATOR, result.getApprovedBy());
        verify(transferRepository).save(transfer);
    }

    @Test
    void replay_of_requested_transfer_with_running_instance_is_idempotent() {
        InternalTransfer transfer = draftTransfer();
        transfer.setStatus(TransferStatus.REQUESTED);
        ApprovalWorkflowInstance running = runningInstance(INITIATOR);
        when(transferRepository.findByIdAndEitherFactoryId(TRANSFER_ID, FACTORY))
                .thenReturn(Optional.of(transfer));
        when(workflowEngine.getLatestInstance(FACTORY, "INVENTORY_TRANSFER", TRANSFER_ID))
                .thenReturn(Optional.of(running));

        InternalTransfer result = service.requestTransfer(FACTORY, TRANSFER_ID, INITIATOR);

        assertEquals(TransferStatus.REQUESTED, result.getStatus());
        verify(workflowEngine, never()).startWorkflow(any(), any(), any(), any(), any());
        verify(transferRepository, never()).save(any());
    }

    @Test
    void oa_approve_projects_approved_and_self_approval_is_rejected() {
        InternalTransfer transfer = draftTransfer();
        transfer.setStatus(TransferStatus.REQUESTED);
        ApprovalWorkflowInstance running = runningInstance(INITIATOR);
        when(transferRepository.findByIdAndEitherFactoryId(TRANSFER_ID, FACTORY))
                .thenReturn(Optional.of(transfer));
        when(workflowEngine.getInstance(FACTORY, running.getId()))
                .thenReturn(Optional.of(running));

        BusinessException selfApproval = assertThrows(
                BusinessException.class,
                () -> service.applyWorkflowAction(
                        FACTORY, TRANSFER_ID, running.getId(), INITIATOR,
                        "warehouse_manager", HistoryAction.APPROVE, "同意"));
        assertEquals("TRANSFER_SELF_APPROVAL_FORBIDDEN", selfApproval.getErrorCode());
        verify(workflowEngine, never()).transitionNode(any(), any(), any(), any(), any());

        ApprovalWorkflowInstance approved = runningInstance(INITIATOR);
        approved.setStatus(ApprovalWorkflowInstance.InstanceStatus.APPROVED);
        approved.setCurrentNodeIds(new ArrayList<>());
        when(workflowEngine.transitionNode(
                running.getId(), 2001L, "warehouse_manager", HistoryAction.APPROVE, "同意"))
                .thenReturn(approved);
        when(transferRepository.save(transfer)).thenReturn(transfer);

        InternalTransfer result = service.applyWorkflowAction(
                FACTORY, TRANSFER_ID, running.getId(), 2001L,
                "warehouse_manager", HistoryAction.APPROVE, "同意");

        assertEquals(TransferStatus.APPROVED, result.getStatus());
        assertEquals(2001L, result.getApprovedBy());
    }

    private InternalTransfer draftTransfer() {
        InternalTransfer transfer = new InternalTransfer();
        transfer.setId(TRANSFER_ID);
        transfer.setTransferNumber("TRF-20260723-TEST");
        transfer.setSourceFactoryId(FACTORY);
        transfer.setTargetFactoryId(FACTORY);
        transfer.setSourceWarehouseId("WH-RAW");
        transfer.setTargetWarehouseId("WH-WKS");
        transfer.setTransferType(TransferType.WAREHOUSE_TO_WAREHOUSE);
        transfer.setStatus(TransferStatus.DRAFT);
        transfer.setTotalAmount(BigDecimal.ZERO);
        transfer.setItems(new ArrayList<>());
        return transfer;
    }

    private ApprovalWorkflowInstance runningInstance(Long initiator) {
        return ApprovalWorkflowInstance.builder()
                .id("inst-transfer-1")
                .factoryId(FACTORY)
                .workflowId("wf-transfer")
                .moduleCode("INVENTORY_TRANSFER")
                .businessEntityId(TRANSFER_ID)
                .status(ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                .currentNodeIds(new ArrayList<>(List.of("approval_warehouse")))
                .contextJson(Map.of())
                .initiatedBy(initiator)
                .build();
    }

    private void mockRunnableRoute(ApprovalWorkflowInstance instance, Long approverId) {
        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setId(instance.getWorkflowId());
        workflow.setFactoryId(FACTORY);
        workflow.setNodesJson("[]");
        when(approvalWorkflowService.getById(FACTORY, instance.getWorkflowId()))
                .thenReturn(Optional.of(workflow));
        when(approvalWorkflowService.deserializeNodes("[]"))
                .thenReturn(List.of(ApprovalWorkflowNode.builder()
                        .id("approval_warehouse")
                        .type("approval")
                        .label("仓储经理审批")
                        .config(Map.of("approverRoles", List.of("warehouse_manager")))
                        .build()));
        User approver = new User();
        approver.setId(approverId);
        approver.setFactoryId(FACTORY);
        approver.setRoleCode("warehouse_manager");
        approver.setIsActive(true);
        when(userRepository.findByFactoryIdAndRoleCode(FACTORY, "warehouse_manager"))
                .thenReturn(List.of(approver));
    }
}
