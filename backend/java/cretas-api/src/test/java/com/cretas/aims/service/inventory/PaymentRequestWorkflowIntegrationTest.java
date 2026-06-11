package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PaymentRequestRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.impl.PaymentRequestServiceImpl;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 六扇门 #30 — PaymentRequest 可配置审批流（替代钉钉）接入测试。
 *
 * <p>验证 {@code financeApprove} 接入现成 {@link WorkflowEngineService}（moduleCode=PURCHASE_PAYMENT），
 * 镜像 PurchaseServiceImpl.approveOrder 已验证的接入模式：
 *
 * <ul>
 *   <li>WF-01: 无 workflowEngine bean → legacy 硬编码（FINANCE_REVIEW → APPROVED），向后兼容</li>
 *   <li>WF-02: factory 无 active PURCHASE_PAYMENT workflow → legacy，不调 startWorkflow</li>
 *   <li>WF-03: 有 workflow，首次启动终态 APPROVED → PR APPROVED + workflowInstanceId 已记录</li>
 *   <li>WF-04: 有 workflow，多级审批中 RUNNING → PR 停 FINANCE_REVIEW（资金红线：不解锁付款）</li>
 *   <li>WF-05: 有 workflow，已有 RUNNING 实例 → 走 transitionNode（resume），不重复 startWorkflow</li>
 *   <li>WF-06: workflow 终态 REJECTED → PR REJECTED + rejectReason</li>
 *   <li>WF-07: workflow CANCELLED/TIMEOUT → PR 回 FINANCE_REVIEW 等人工重审</li>
 *   <li>WF-08: 资金红线 — 工作流 RUNNING 的 PR（停 FINANCE_REVIEW）调 markPaid → 拒绝</li>
 * </ul>
 *
 * <p>workflowEngine 是 {@code @Autowired(required=false)} 字段注入，
 * 通过 {@link ReflectionTestUtils#setField} 灌入（mirror PurchaseServiceWorkflowIntegrationTest）。
 */
@DisplayName("六扇门 #30: PaymentRequest 可配置审批流接入")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentRequestWorkflowIntegrationTest {

    @Mock private PaymentRequestRepository paymentRequestRepository;
    @Mock private ArApTransactionRepository arApTransactionRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private WorkflowEngineService workflowEngine;

    private PaymentRequestServiceImpl service;

    private static final String REQUEST_ID = "pr-uuid-001";
    private static final String FACTORY_ID = "F006";
    private static final String SUPPLIER_ID = "SUP-001";
    private static final String PO_ID = "PO-001";
    private static final Long USER_ID = 42L;
    private static final String INSTANCE_ID = "wf-payment-001";
    private static final String MODULE = "PURCHASE_PAYMENT";

    @BeforeEach
    void setUp() {
        service = new PaymentRequestServiceImpl(
                paymentRequestRepository,
                arApTransactionRepository,
                supplierRepository,
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                customerRepository);
        when(paymentRequestRepository.save(any(PaymentRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private PaymentRequest financeReviewRequest() {
        PaymentRequest pr = new PaymentRequest();
        pr.setId(REQUEST_ID);
        pr.setFactoryId(FACTORY_ID);
        pr.setRequestNumber("PR-F006-20260611-0001");
        pr.setPurchaseOrderId(PO_ID);
        pr.setSupplierId(SUPPLIER_ID);
        pr.setAmount(BigDecimal.valueOf(50000));
        pr.setStatus(PaymentRequestStatus.FINANCE_REVIEW);
        pr.setCreatedBy(1L);
        return pr;
    }

    private ApprovalWorkflowInstance instanceWith(InstanceStatus status) {
        ApprovalWorkflowInstance inst = new ApprovalWorkflowInstance();
        inst.setId(INSTANCE_ID);
        inst.setStatus(status);
        return inst;
    }

    // ──────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("无 workflow 配置 → legacy 硬编码（向后兼容）")
    class LegacyFallback {

        @Test
        @DisplayName("WF-01: workflowEngine bean 不可用 → legacy 直接 APPROVED")
        void noEngineBean_legacyApprove() {
            // service 构造后未 setField workflowEngine → null
            PaymentRequest pr = financeReviewRequest();
            when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pr));

            PaymentRequest result = service.financeApprove(REQUEST_ID, USER_ID, "金额无误");

            assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.APPROVED);
            assertThat(result.getFinanceReviewedBy()).isEqualTo(USER_ID);
            assertThat(result.getApprovedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("WF-02: factory 无 active workflow → legacy，不调 startWorkflow")
        void noActiveWorkflow_legacyApprove() {
            ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
            when(workflowEngine.hasActiveWorkflow(FACTORY_ID, MODULE)).thenReturn(false);
            PaymentRequest pr = financeReviewRequest();
            when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pr));

            PaymentRequest result = service.financeApprove(REQUEST_ID, USER_ID, "金额无误");

            assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.APPROVED);
            verify(workflowEngine, never()).startWorkflow(anyString(), anyString(), anyString(), any(), any());
            verify(workflowEngine, never()).transitionNode(anyString(), any(), anyString(), any(), anyString());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("有 workflow 配置 → 走引擎，按终态翻译 PR 状态")
    class WorkflowPath {

        @BeforeEach
        void enableEngine() {
            ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
            when(workflowEngine.hasActiveWorkflow(FACTORY_ID, MODULE)).thenReturn(true);
        }

        @Test
        @DisplayName("WF-03: 首次启动，终态 APPROVED → PR APPROVED + workflowInstanceId")
        void startWorkflow_terminalApproved() {
            PaymentRequest pr = financeReviewRequest();
            when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pr));
            when(workflowEngine.getCurrentInstance(FACTORY_ID, MODULE, REQUEST_ID))
                    .thenReturn(Optional.empty());
            when(workflowEngine.startWorkflow(eq(FACTORY_ID), eq(MODULE), eq(REQUEST_ID), any(), eq(USER_ID)))
                    .thenReturn(instanceWith(InstanceStatus.APPROVED));

            PaymentRequest result = service.financeApprove(REQUEST_ID, USER_ID, "审批通过");

            assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.APPROVED);
            assertThat(result.getWorkflowInstanceId()).isEqualTo(INSTANCE_ID);
            assertThat(result.getApprovedBy()).isEqualTo(USER_ID);
            assertThat(result.getApprovedAt()).isNotNull();
            verify(workflowEngine, never()).transitionNode(anyString(), any(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("WF-04: 多级审批中 RUNNING → PR 停 FINANCE_REVIEW（不解锁付款）")
        void startWorkflow_runningStaysFinanceReview() {
            PaymentRequest pr = financeReviewRequest();
            when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pr));
            when(workflowEngine.getCurrentInstance(FACTORY_ID, MODULE, REQUEST_ID))
                    .thenReturn(Optional.empty());
            when(workflowEngine.startWorkflow(eq(FACTORY_ID), eq(MODULE), eq(REQUEST_ID), any(), eq(USER_ID)))
                    .thenReturn(instanceWith(InstanceStatus.RUNNING));

            PaymentRequest result = service.financeApprove(REQUEST_ID, USER_ID, "一级通过，待二级");

            assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.FINANCE_REVIEW);
            assertThat(result.getWorkflowInstanceId()).isEqualTo(INSTANCE_ID);
            // RUNNING 时不写最终批准痕迹（不解锁付款）
            assertThat(result.getApprovedBy()).isNull();
            assertThat(result.getApprovedAt()).isNull();
        }

        @Test
        @DisplayName("WF-05: 已有 RUNNING 实例 → transitionNode（resume），不重复 startWorkflow")
        void resumeRunningInstance_transitions() {
            PaymentRequest pr = financeReviewRequest();
            when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pr));
            when(workflowEngine.getCurrentInstance(FACTORY_ID, MODULE, REQUEST_ID))
                    .thenReturn(Optional.of(instanceWith(InstanceStatus.RUNNING)));
            when(workflowEngine.transitionNode(eq(INSTANCE_ID), eq(USER_ID), anyString(),
                    eq(HistoryAction.APPROVE), anyString()))
                    .thenReturn(instanceWith(InstanceStatus.APPROVED));

            PaymentRequest result = service.financeApprove(REQUEST_ID, USER_ID, "二级通过");

            assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.APPROVED);
            verify(workflowEngine).transitionNode(eq(INSTANCE_ID), eq(USER_ID), anyString(),
                    eq(HistoryAction.APPROVE), anyString());
            verify(workflowEngine, never()).startWorkflow(anyString(), anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("WF-06: 终态 REJECTED → PR REJECTED + rejectReason")
        void workflowRejected() {
            PaymentRequest pr = financeReviewRequest();
            when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pr));
            when(workflowEngine.getCurrentInstance(FACTORY_ID, MODULE, REQUEST_ID))
                    .thenReturn(Optional.empty());
            when(workflowEngine.startWorkflow(eq(FACTORY_ID), eq(MODULE), eq(REQUEST_ID), any(), eq(USER_ID)))
                    .thenReturn(instanceWith(InstanceStatus.REJECTED));

            PaymentRequest result = service.financeApprove(REQUEST_ID, USER_ID, "金额超预算");

            assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.REJECTED);
            assertThat(result.getRejectReason()).isEqualTo("金额超预算");
            assertThat(result.getApprovedBy()).isNull();
        }

        @Test
        @DisplayName("WF-07: 终态 CANCELLED → PR 回 FINANCE_REVIEW 等人工重审")
        void workflowCancelled_backToReview() {
            PaymentRequest pr = financeReviewRequest();
            when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pr));
            when(workflowEngine.getCurrentInstance(FACTORY_ID, MODULE, REQUEST_ID))
                    .thenReturn(Optional.empty());
            when(workflowEngine.startWorkflow(eq(FACTORY_ID), eq(MODULE), eq(REQUEST_ID), any(), eq(USER_ID)))
                    .thenReturn(instanceWith(InstanceStatus.CANCELLED));

            PaymentRequest result = service.financeApprove(REQUEST_ID, USER_ID, "");

            assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.FINANCE_REVIEW);
            assertThat(result.getApprovedBy()).isNull();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("资金红线：审批通过才能付款（workflow 与 legacy 共同保证）")
    class FundsRedLine {

        @Test
        @DisplayName("WF-08: 工作流 RUNNING 的 PR（停 FINANCE_REVIEW）调 markPaid → 拒绝")
        void runningWorkflow_cannotMarkPaid() {
            // financeApprove 走 workflow，停在 FINANCE_REVIEW（RUNNING）
            ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
            when(workflowEngine.hasActiveWorkflow(FACTORY_ID, MODULE)).thenReturn(true);
            PaymentRequest pr = financeReviewRequest();
            when(paymentRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pr));
            when(workflowEngine.getCurrentInstance(FACTORY_ID, MODULE, REQUEST_ID))
                    .thenReturn(Optional.empty());
            when(workflowEngine.startWorkflow(eq(FACTORY_ID), eq(MODULE), eq(REQUEST_ID), any(), eq(USER_ID)))
                    .thenReturn(instanceWith(InstanceStatus.RUNNING));

            service.financeApprove(REQUEST_ID, USER_ID, "一级通过");
            assertThat(pr.getStatus()).isEqualTo(PaymentRequestStatus.FINANCE_REVIEW);

            // markPaid 要求 APPROVED → FINANCE_REVIEW 被 requireStatus 拒绝
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.cretas.aims.exception.BusinessException.class,
                    () -> service.markPaid(REQUEST_ID, USER_ID, "凭证X"));
            // 资金路径未触达（无 ArApTransaction 写入）
            verify(arApTransactionRepository, never()).save(any());
        }
    }
}
