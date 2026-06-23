package com.cretas.aims.service;

import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.DisposalRecordRepository;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP12 T5: DisposalRecord workflow 集成测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>UT-DR-WF-01: submitForApproval 正常流 → workflowInstanceId 设置</li>
 *   <li>UT-DR-WF-02: submitForApproval 重复提交 → 409</li>
 *   <li>UT-DR-WF-03: submitForApproval 无 workflowEngine → instanceId=null, 不抛异常</li>
 *   <li>UT-DR-WF-04: submitForApproval 记录不存在 → 404</li>
 * </ul>
 */
@DisplayName("SP12 T5: DisposalRecord workflow 集成测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisposalWorkflowIntegrationTest {

    @Mock private DisposalRecordRepository disposalRecordRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;
    @Mock private WorkflowEngineService workflowEngine;

    private DisposalRecordService service;

    private static final Long RECORD_ID = 101L;
    private static final String FACTORY_ID = "F006";
    private static final Long USER_ID = 42L;
    private static final String INSTANCE_ID = "wf-disposal-001";

    @BeforeEach
    void setUp() {
        service = new DisposalRecordService(disposalRecordRepository, materialBatchRepository,
                materialBatchAdjustmentRepository, productionBatchRepository,
                finishedGoodsBatchRepository, finishedGoodsAdjustmentLogRepository);
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
    }

    // -------------------------------------------------------
    // UT-DR-WF-01: submitForApproval 正常流
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-DR-WF-01: submitForApproval → workflowInstanceId 已设置并返回")
    void submitForApproval_normalFlow_setsWorkflowInstanceId() {
        DisposalRecord record = buildRecord(null);
        when(disposalRecordRepository.findById(RECORD_ID)).thenReturn(Optional.of(record));
        when(disposalRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApprovalWorkflowInstance instance = mock(ApprovalWorkflowInstance.class);
        when(instance.getId()).thenReturn(INSTANCE_ID);
        when(workflowEngine.startWorkflow(eq(FACTORY_ID), eq("DISPOSAL_APPROVAL"),
                eq(String.valueOf(RECORD_ID)), any(), eq(USER_ID))).thenReturn(instance);

        String result = service.submitForApproval(RECORD_ID, FACTORY_ID, USER_ID);

        assertThat(result).isEqualTo(INSTANCE_ID);
        assertThat(record.getWorkflowInstanceId()).isEqualTo(INSTANCE_ID);
        verify(disposalRecordRepository).save(record);
    }

    // -------------------------------------------------------
    // UT-DR-WF-02: submitForApproval 重复提交 → 409
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-DR-WF-02: submitForApproval 重复提交 (workflowInstanceId 已存在) → 409")
    void submitForApproval_duplicate_throws409() {
        DisposalRecord record = buildRecord(INSTANCE_ID);  // already has instanceId
        when(disposalRecordRepository.findById(RECORD_ID)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.submitForApproval(RECORD_ID, FACTORY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(409);

        verify(disposalRecordRepository, never()).save(any());
        verify(workflowEngine, never()).startWorkflow(any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------
    // UT-DR-WF-03: submitForApproval 无 workflowEngine → instanceId=null
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-DR-WF-03: submitForApproval 无 workflowEngine → instanceId=null, 不抛异常")
    void submitForApproval_noWorkflowEngine_returnsNull() {
        ReflectionTestUtils.setField(service, "workflowEngine", null);

        DisposalRecord record = buildRecord(null);
        when(disposalRecordRepository.findById(RECORD_ID)).thenReturn(Optional.of(record));
        when(disposalRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String result = service.submitForApproval(RECORD_ID, FACTORY_ID, USER_ID);

        assertThat(result).isNull();
        assertThat(record.getWorkflowInstanceId()).isNull();
        verify(disposalRecordRepository).save(record);

        // Restore for other tests
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
    }

    // -------------------------------------------------------
    // UT-DR-WF-04: submitForApproval 记录不存在 → 404
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-DR-WF-04: submitForApproval 记录不存在 → 404")
    void submitForApproval_notFound_throws404() {
        when(disposalRecordRepository.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitForApproval(RECORD_ID, FACTORY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(404);

        verify(disposalRecordRepository, never()).save(any());
    }

    // -------------------------------------------------------
    // helper
    // -------------------------------------------------------
    private DisposalRecord buildRecord(String workflowInstanceId) {
        DisposalRecord record = new DisposalRecord();
        record.setId(RECORD_ID);
        record.setFactoryId(FACTORY_ID);
        record.setDisposalType("SCRAP");
        record.setDisposalQuantity(java.math.BigDecimal.TEN);
        record.setIsApproved(false);
        record.setWorkflowInstanceId(workflowInstanceId);
        return record;
    }
}
