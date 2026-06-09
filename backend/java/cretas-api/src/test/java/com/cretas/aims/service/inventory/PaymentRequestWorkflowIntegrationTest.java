package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PaymentRequestRepository;
import com.cretas.aims.service.inventory.impl.PaymentRequestServiceImpl;
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

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP12 T6: PaymentRequest workflow 集成测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>UT-PR-WF-01: submitForApproval 正常流 → workflowInstanceId + submittedBy 设置</li>
 *   <li>UT-PR-WF-02: submitForApproval 重复提交 (workflowInstanceId 已存在) → 409</li>
 *   <li>UT-PR-WF-03: submitForApproval 无 workflowEngine → instanceId=null, 不抛异常</li>
 *   <li>UT-PR-WF-04: submitForApproval 申请单不存在 → 404</li>
 *   <li>UT-PR-WF-05: submitForApproval 状态不是 PENDING → 400</li>
 * </ul>
 */
@DisplayName("SP12 T6: PaymentRequest workflow 集成测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentRequestWorkflowIntegrationTest {

    @Mock private PaymentRequestRepository paymentRequestRepository;
    @Mock private ArApTransactionRepository arApTransactionRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private WorkflowEngineService workflowEngine;

    private PaymentRequestServiceImpl service;

    private static final String REQUEST_ID = "pr-uuid-001";
    private static final String FACTORY_ID = "F006";
    private static final Long USER_ID = 42L;
    private static final String INSTANCE_ID = "wf-payment-001";

    @BeforeEach
    void setUp() {
        service = new PaymentRequestServiceImpl(
                paymentRequestRepository, arApTransactionRepository, supplierRepository);
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
    }

    // -------------------------------------------------------
    // UT-PR-WF-01: submitForApproval 正常流
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-PR-WF-01: submitForApproval → workflowInstanceId 和 submittedBy 已设置并返回")
    void submitForApproval_normalFlow_setsFieldsAndReturns() {
        PaymentRequest pr = buildRequest(null, PaymentRequestStatus.PENDING);
        when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pr));
        when(paymentRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApprovalWorkflowInstance instance = mock(ApprovalWorkflowInstance.class);
        when(instance.getId()).thenReturn(INSTANCE_ID);
        when(workflowEngine.startWorkflow(eq(FACTORY_ID), eq("PAYMENT_APPROVAL"),
                eq(REQUEST_ID), any(), eq(USER_ID))).thenReturn(instance);

        String result = service.submitForApproval(REQUEST_ID, FACTORY_ID, USER_ID);

        assertThat(result).isEqualTo(INSTANCE_ID);
        assertThat(pr.getWorkflowInstanceId()).isEqualTo(INSTANCE_ID);
        assertThat(pr.getSubmittedBy()).isEqualTo(USER_ID);
        verify(paymentRequestRepository).save(pr);
    }

    // -------------------------------------------------------
    // UT-PR-WF-02: submitForApproval 重复提交 → 409
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-PR-WF-02: submitForApproval 重复提交 (workflowInstanceId 已存在) → 409")
    void submitForApproval_duplicate_throws409() {
        PaymentRequest pr = buildRequest(INSTANCE_ID, PaymentRequestStatus.PENDING);
        when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pr));

        assertThatThrownBy(() -> service.submitForApproval(REQUEST_ID, FACTORY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(409);

        verify(paymentRequestRepository, never()).save(any());
        verify(workflowEngine, never()).startWorkflow(any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------
    // UT-PR-WF-03: submitForApproval 无 workflowEngine → instanceId=null
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-PR-WF-03: submitForApproval 无 workflowEngine → instanceId=null, 不抛异常")
    void submitForApproval_noWorkflowEngine_returnsNull() {
        ReflectionTestUtils.setField(service, "workflowEngine", null);

        PaymentRequest pr = buildRequest(null, PaymentRequestStatus.PENDING);
        when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pr));
        when(paymentRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String result = service.submitForApproval(REQUEST_ID, FACTORY_ID, USER_ID);

        assertThat(result).isNull();
        assertThat(pr.getWorkflowInstanceId()).isNull();
        assertThat(pr.getSubmittedBy()).isEqualTo(USER_ID);
        verify(paymentRequestRepository).save(pr);

        // Restore for other tests
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
    }

    // -------------------------------------------------------
    // UT-PR-WF-04: submitForApproval 申请单不存在 → 404
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-PR-WF-04: submitForApproval 申请单不存在 → 404")
    void submitForApproval_notFound_throws404() {
        when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitForApproval(REQUEST_ID, FACTORY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(404);

        verify(paymentRequestRepository, never()).save(any());
    }

    // -------------------------------------------------------
    // UT-PR-WF-05: submitForApproval 状态不是 PENDING → 400
    // -------------------------------------------------------
    @Test
    @DisplayName("UT-PR-WF-05: submitForApproval 状态不是 PENDING (e.g. APPROVED) → 400")
    void submitForApproval_wrongStatus_throws400() {
        PaymentRequest pr = buildRequest(null, PaymentRequestStatus.APPROVED);
        when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pr));

        assertThatThrownBy(() -> service.submitForApproval(REQUEST_ID, FACTORY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(400);

        verify(paymentRequestRepository, never()).save(any());
        verify(workflowEngine, never()).startWorkflow(any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------
    // helper
    // -------------------------------------------------------
    private PaymentRequest buildRequest(String workflowInstanceId, PaymentRequestStatus status) {
        PaymentRequest pr = new PaymentRequest();
        pr.setFactoryId(FACTORY_ID);
        pr.setStatus(status);
        pr.setAmount(BigDecimal.valueOf(5000));
        pr.setSupplierId("SUP-001");
        pr.setPurchaseOrderId("PO-001");
        pr.setCreatedBy(USER_ID);
        pr.setWorkflowInstanceId(workflowInstanceId);
        return pr;
    }
}
