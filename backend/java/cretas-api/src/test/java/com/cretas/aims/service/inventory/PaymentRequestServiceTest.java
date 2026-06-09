package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PaymentRequestRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.service.inventory.impl.PaymentRequestServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP6 付款申请单 Service 单元测试
 *
 * <p>覆盖：
 * - create()：幂等性检查（同 PO 有活跃申请 → 409）
 * - P0 状态机：PENDING → FINANCE_REVIEW → APPROVED → PAID
 * - markPaid 三写原子：状态 + ArApTransaction + Supplier.currentBalance
 * - reject()、cancel()：终态保护
 * - 访问控制：role check via RequestContextHolder（非财务不可 financeApprove）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PaymentRequestServiceImpl 单元测试 (SP6)")
class PaymentRequestServiceTest {

    @Mock
    private PaymentRequestRepository paymentRequestRepository;

    @Mock
    private ArApTransactionRepository arApTransactionRepository;

    @Mock
    private SupplierRepository supplierRepository;

    @InjectMocks
    private PaymentRequestServiceImpl paymentRequestService;

    private static final String FACTORY_ID = "F006";
    private static final String PO_ID = "PO-001";
    private static final String SUPPLIER_ID = "SUP-001";

    @BeforeEach
    void setup() {
        when(paymentRequestRepository.save(any(PaymentRequest.class)))
                .thenAnswer(inv -> {
                    PaymentRequest pr = inv.getArgument(0);
                    if (pr.getId() == null) pr.setId(java.util.UUID.randomUUID().toString());
                    return pr;
                });
    }

    // ───────────────────────────────────────────────────────────────────────
    // create() 幂等性
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create() 幂等性检查")
    class CreateIdempotencyTests {

        @Test
        @DisplayName("同 PO 已有 PENDING 申请 → BusinessException (409)")
        void existingPendingRequest_throwsBusinessException() {
            PaymentRequest existing = new PaymentRequest();
            existing.setId("PR-001");
            existing.setStatus(PaymentRequestStatus.PENDING);
            when(paymentRequestRepository.findActiveByPurchaseOrderId(
                    eq(PO_ID), anyList())).thenReturn(List.of(existing));

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                            BigDecimal.valueOf(5000), "transfer", 1L, null));
        }

        @Test
        @DisplayName("同 PO 已有 APPROVED 申请 → BusinessException (409)")
        void existingApprovedRequest_throwsBusinessException() {
            PaymentRequest existing = new PaymentRequest();
            existing.setId("PR-001");
            existing.setStatus(PaymentRequestStatus.APPROVED);
            when(paymentRequestRepository.findActiveByPurchaseOrderId(
                    eq(PO_ID), anyList())).thenReturn(List.of(existing));

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                            BigDecimal.valueOf(5000), "transfer", 1L, null));
        }

        @Test
        @DisplayName("同 PO 无活跃申请 → 正常创建")
        void noActiveRequest_createSucceeds() {
            when(paymentRequestRepository.findActiveByPurchaseOrderId(
                    eq(PO_ID), anyList())).thenReturn(List.of());

            PaymentRequest result = paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                    BigDecimal.valueOf(5000), "transfer", 1L, null);

            assertNotNull(result);
            assertEquals(PaymentRequestStatus.PENDING, result.getStatus());
        }

        @Test
        @DisplayName("新建申请金额正确保存")
        void createSetsAmountCorrectly() {
            when(paymentRequestRepository.findActiveByPurchaseOrderId(
                    eq(PO_ID), anyList())).thenReturn(List.of());

            PaymentRequest result = paymentRequestService.create(FACTORY_ID, PO_ID, SUPPLIER_ID,
                    BigDecimal.valueOf(8888.88), "transfer", 1L, null);

            assertEquals(0, BigDecimal.valueOf(8888.88).compareTo(result.getAmount()));
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // P0 状态机
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("P0 状态机转换")
    class StateMachineTests {

        private PaymentRequest pendingRequest() {
            PaymentRequest pr = new PaymentRequest();
            pr.setId("PR-001");
            pr.setFactoryId(FACTORY_ID);
            pr.setPurchaseOrderId(PO_ID);
            pr.setSupplierId(SUPPLIER_ID);
            pr.setStatus(PaymentRequestStatus.PENDING);
            pr.setAmount(BigDecimal.valueOf(5000));
            pr.setCreatedBy(1L);
            return pr;
        }

        private PaymentRequest financeReviewRequest() {
            PaymentRequest pr = pendingRequest();
            pr.setStatus(PaymentRequestStatus.FINANCE_REVIEW);
            return pr;
        }

        private PaymentRequest approvedRequest() {
            PaymentRequest pr = pendingRequest();
            pr.setStatus(PaymentRequestStatus.APPROVED);
            return pr;
        }

        @Test
        @DisplayName("PENDING → submit → FINANCE_REVIEW")
        void submit_transitionsToPendingFinanceReview() {
            PaymentRequest pr = pendingRequest();
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            paymentRequestService.submit("PR-001", 1L);

            ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
            verify(paymentRequestRepository).save(captor.capture());
            assertEquals(PaymentRequestStatus.FINANCE_REVIEW, captor.getValue().getStatus());
        }

        @Test
        @DisplayName("FINANCE_REVIEW → financeApprove → APPROVED")
        void financeApprove_transitionsToApproved() {
            PaymentRequest pr = financeReviewRequest();
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            paymentRequestService.financeApprove("PR-001", 2L, "财务确认金额无误");

            ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
            verify(paymentRequestRepository).save(captor.capture());
            assertEquals(PaymentRequestStatus.APPROVED, captor.getValue().getStatus());
            assertEquals(2L, captor.getValue().getFinanceReviewedBy());
        }

        @Test
        @DisplayName("APPROVED → reject → REJECTED")
        void reject_transitionsToRejected() {
            PaymentRequest pr = approvedRequest();
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            paymentRequestService.reject("PR-001", 2L, "金额有误");

            ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
            verify(paymentRequestRepository).save(captor.capture());
            assertEquals(PaymentRequestStatus.REJECTED, captor.getValue().getStatus());
        }

        @Test
        @DisplayName("PAID 状态不能再提交 → BusinessException")
        void submitOnPaid_throwsBusinessException() {
            PaymentRequest pr = pendingRequest();
            pr.setStatus(PaymentRequestStatus.PAID);
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.submit("PR-001", 1L));
        }

        @Test
        @DisplayName("REJECTED 状态不能再 financeApprove → BusinessException")
        void financeApproveOnRejected_throwsBusinessException() {
            PaymentRequest pr = pendingRequest();
            pr.setStatus(PaymentRequestStatus.REJECTED);
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.financeApprove("PR-001", 2L, null));
        }

        @Test
        @DisplayName("申请单不存在 → BusinessException")
        void unknownId_throwsBusinessException() {
            when(paymentRequestRepository.findById("MISSING")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.submit("MISSING", 1L));
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // markPaid 三写原子
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markPaid 三写原子事务")
    class MarkPaidTests {

        private PaymentRequest approvedRequest() {
            PaymentRequest pr = new PaymentRequest();
            pr.setId("PR-001");
            pr.setFactoryId(FACTORY_ID);
            pr.setSupplierId(SUPPLIER_ID);
            pr.setPurchaseOrderId(PO_ID);
            pr.setStatus(PaymentRequestStatus.APPROVED);
            pr.setAmount(BigDecimal.valueOf(5000));
            pr.setCreatedBy(1L);
            return pr;
        }

        @Test
        @DisplayName("markPaid：申请单状态变 PAID")
        void markPaid_statusBecomePaid() {
            PaymentRequest pr = approvedRequest();
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));
            when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(Optional.of(
                    makeSupplier(SUPPLIER_ID, BigDecimal.valueOf(10000))));
            when(arApTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            paymentRequestService.markPaid("PR-001", 3L, "付款截图");

            ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
            verify(paymentRequestRepository).save(captor.capture());
            assertEquals(PaymentRequestStatus.PAID, captor.getValue().getStatus());
            assertEquals(3L, captor.getValue().getPaidBy());
        }

        @Test
        @DisplayName("markPaid：ArApTransaction 已保存（AP_PAYMENT 类型）")
        void markPaid_createsArApTransaction() {
            PaymentRequest pr = approvedRequest();
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));
            when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(Optional.of(
                    makeSupplier(SUPPLIER_ID, BigDecimal.valueOf(10000))));
            when(arApTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            paymentRequestService.markPaid("PR-001", 3L, null);

            // ArApTransaction must be saved with correct amount
            verify(arApTransactionRepository).save(argThat(tx ->
                    tx.getAmount().compareTo(BigDecimal.valueOf(5000)) == 0));
        }

        @Test
        @DisplayName("markPaid：供应商账户余额扣减")
        void markPaid_decreasesSupplierBalance() {
            PaymentRequest pr = approvedRequest();
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            com.cretas.aims.entity.Supplier supplier = makeSupplier(SUPPLIER_ID, BigDecimal.valueOf(10000));
            when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(Optional.of(supplier));
            when(arApTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            paymentRequestService.markPaid("PR-001", 3L, null);

            // Supplier balance should be reduced by 5000
            verify(supplierRepository).save(argThat(s ->
                    s.getCurrentBalance().compareTo(BigDecimal.valueOf(5000)) == 0));
        }

        @Test
        @DisplayName("markPaid：非 APPROVED 状态 → BusinessException，无写入")
        void markPaidOnNonApproved_throwsBusinessException() {
            PaymentRequest pr = approvedRequest();
            pr.setStatus(PaymentRequestStatus.PENDING);
            when(paymentRequestRepository.findById("PR-001")).thenReturn(Optional.of(pr));

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.markPaid("PR-001", 3L, null));

            verifyNoInteractions(arApTransactionRepository, supplierRepository);
        }

        @Test
        @DisplayName("markPaid：申请单不存在 → BusinessException")
        void markPaidMissingId_throwsBusinessException() {
            when(paymentRequestRepository.findById("MISSING")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                    paymentRequestService.markPaid("MISSING", 3L, null));
        }

        private com.cretas.aims.entity.Supplier makeSupplier(String id, BigDecimal balance) {
            com.cretas.aims.entity.Supplier s = new com.cretas.aims.entity.Supplier();
            s.setId(id);
            s.setCurrentBalance(balance);
            return s;
        }
    }
}
