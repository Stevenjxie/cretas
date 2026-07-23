package com.cretas.aims.service.factory.impl;

import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeItemRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP12 T4: FactoryStocktake workflow 集成测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>UT-ST-WF-01: submitForApproval → PENDING_APPROVAL + workflowInstanceId set</li>
 *   <li>UT-ST-WF-02: submitForApproval 重复提交 → 返回既有实例</li>
 *   <li>UT-ST-WF-03: submitForApproval 无 workflowEngine → fail-closed</li>
 *   <li>UT-ST-WF-04: executeAdjustment 无 workflowInstanceId → 403 (红线 R1)</li>
 *   <li>UT-ST-WF-05: executeAdjustment 已 APPLIED → 409 幂等</li>
 *   <li>UT-ST-WF-06: executeAdjustment 状态非 APPROVED → 409</li>
 * </ul>
 */
@DisplayName("SP12 T4: FactoryStocktake workflow 集成测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StocktakeWorkflowIntegrationTest {

    @Mock private FactoryStocktakeRepository stocktakeRepo;
    @Mock private FactoryStocktakeItemRepository stocktakeItemRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialBatchAdjustmentRepository adjustmentRepo;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepo;
    @Mock private MaterialConsumptionRepository materialConsumptionRepo;
    @Mock private WorkflowEngineService workflowEngine;

    private FactoryStocktakeServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String STOCKTAKE_ID = "ST-202606-TEST";
    private static final Long USER_ID = 42L;
    private static final String INSTANCE_ID = "wf-instance-001";

    @BeforeEach
    void setUp() {
        // Use @RequiredArgsConstructor-compatible constructor (6 final fields)
        service = new FactoryStocktakeServiceImpl(
                stocktakeRepo,
                stocktakeItemRepo,
                materialBatchRepo,
                adjustmentRepo,
                rawMaterialTypeRepo,
                materialConsumptionRepo);
        // Inject optional workflowEngine via ReflectionTestUtils
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
        lenient().when(stocktakeRepo.findByIdAndFactoryIdForUpdate(anyString(), eq(FACTORY_ID)))
                .thenAnswer(invocation -> stocktakeRepo.findById(invocation.getArgument(0)));
    }

    // -------------------------------------------------------
    // UT-ST-WF-01: submitForApproval 正常流 → PENDING_APPROVAL + workflowInstanceId
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-ST-WF-01: submitForApproval → PENDING_APPROVAL + workflowInstanceId set")
    void submitForApproval_counting_transitionsToPendingApproval() {
        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.COUNTING);
        when(stocktakeRepo.findById(STOCKTAKE_ID)).thenReturn(Optional.of(stocktake));
        when(stocktakeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApprovalWorkflowInstance instance = mock(ApprovalWorkflowInstance.class);
        when(instance.getId()).thenReturn(INSTANCE_ID);
        when(workflowEngine.hasActiveWorkflow(FACTORY_ID, "INVENTORY_ADJUSTMENT")).thenReturn(true);
        when(workflowEngine.startWorkflow(eq(FACTORY_ID), eq("INVENTORY_ADJUSTMENT"),
                eq(STOCKTAKE_ID), any(), eq(USER_ID))).thenReturn(instance);

        String resultInstanceId = service.submitForApproval(STOCKTAKE_ID, FACTORY_ID, USER_ID);

        assertThat(stocktake.getStatus()).isEqualTo(FactoryStocktake.Status.PENDING_APPROVAL);
        assertThat(stocktake.getSubmittedBy()).isEqualTo(USER_ID);
        assertThat(stocktake.getWorkflowInstanceId()).isEqualTo(INSTANCE_ID);
        assertThat(resultInstanceId).isEqualTo(INSTANCE_ID);
        verify(stocktakeRepo).save(stocktake);
    }

    // -------------------------------------------------------
    // UT-ST-WF-02: submitForApproval 重复提交 → 409
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-ST-WF-02: submitForApproval 重复提交返回既有 OA 实例")
    void submitForApproval_duplicate_returnsExistingInstance() {
        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.PENDING_APPROVAL);
        stocktake.setWorkflowInstanceId(INSTANCE_ID);
        when(stocktakeRepo.findById(STOCKTAKE_ID)).thenReturn(Optional.of(stocktake));

        assertThat(service.submitForApproval(STOCKTAKE_ID, FACTORY_ID, USER_ID)).isEqualTo(INSTANCE_ID);

        verify(stocktakeRepo, never()).save(any());
    }

    // -------------------------------------------------------
    // UT-ST-WF-03: submitForApproval 无 workflowEngine → fail-closed
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-ST-WF-03: submitForApproval 无 workflow engine → 422 且不改变盘点状态")
    void submitForApproval_noWorkflowEngine_failsClosed() {
        // Override workflowEngine to null
        ReflectionTestUtils.setField(service, "workflowEngine", null);

        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.COUNTING);
        when(stocktakeRepo.findById(STOCKTAKE_ID)).thenReturn(Optional.of(stocktake));
        assertThatThrownBy(() -> service.submitForApproval(STOCKTAKE_ID, FACTORY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(422);
        assertThat(stocktake.getStatus()).isEqualTo(FactoryStocktake.Status.COUNTING);
        assertThat(stocktake.getWorkflowInstanceId()).isNull();
        verify(stocktakeRepo, never()).save(any());

        // Restore for other tests
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
    }

    // -------------------------------------------------------
    // UT-ST-WF-04: executeAdjustment 无 workflowInstanceId → 403 (红线 R1)
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-ST-WF-04: executeAdjustment workflowInstanceId=null → 403 (红线 R1 禁止绕过)")
    void executeAdjustment_noWorkflowInstanceId_throws403() {
        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.APPROVED);
        stocktake.setWorkflowInstanceId(null); // no workflow bypass!
        when(stocktakeRepo.findById(STOCKTAKE_ID)).thenReturn(Optional.of(stocktake));

        assertThatThrownBy(() -> service.executeAdjustment(STOCKTAKE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(403);
    }

    // -------------------------------------------------------
    // UT-ST-WF-05: executeAdjustment 已 APPLIED → 409 幂等
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-ST-WF-05: executeAdjustment 已 APPLIED → 409 幂等防重")
    void executeAdjustment_alreadyApplied_throws409() {
        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.APPLIED);
        stocktake.setWorkflowInstanceId(INSTANCE_ID);
        stocktake.setAppliedAt(LocalDateTime.now().minusHours(1));
        when(stocktakeRepo.findById(STOCKTAKE_ID)).thenReturn(Optional.of(stocktake));

        assertThatThrownBy(() -> service.executeAdjustment(STOCKTAKE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(409);
    }

    // -------------------------------------------------------
    // UT-ST-WF-06: executeAdjustment 状态非 APPROVED → 409
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-ST-WF-06: executeAdjustment 状态 PENDING_APPROVAL → 409")
    void executeAdjustment_notApproved_throws409() {
        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.PENDING_APPROVAL);
        stocktake.setWorkflowInstanceId(INSTANCE_ID);
        when(stocktakeRepo.findById(STOCKTAKE_ID)).thenReturn(Optional.of(stocktake));

        assertThatThrownBy(() -> service.executeAdjustment(STOCKTAKE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(409);
    }

    // -------------------------------------------------------
    // helper
    // -------------------------------------------------------
    private FactoryStocktake buildStocktake(FactoryStocktake.Status status) {
        FactoryStocktake s = new FactoryStocktake();
        s.setId(STOCKTAKE_ID);
        s.setFactoryId(FACTORY_ID);
        s.setStocktakeNo("ST-202606-ABCD");
        s.setWarehouseId("WH-001");
        s.setPeriodMonth("2026-06");
        s.setStatus(status);
        s.setInitiatedBy(USER_ID);
        s.setInitiatedAt(LocalDateTime.now());
        s.setItems(Collections.emptyList());
        return s;
    }
}
