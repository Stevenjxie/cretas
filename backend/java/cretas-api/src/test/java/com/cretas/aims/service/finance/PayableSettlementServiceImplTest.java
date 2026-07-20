package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.PayableSettlementRequest;
import com.cretas.aims.dto.finance.PayableSettlementResult;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PayablePaymentStatus;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.finance.ArApPaymentAllocation;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApPaymentAllocationRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.impl.PayableSettlementServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PayableSettlementService AP open-item settlement")
class PayableSettlementServiceImplTest {

    private static final String FACTORY = "F006";
    private static final String PAYABLE_ID = "AP-INVOICE-1";
    private static final String SUPPLIER_ID = "SUP-1";

    @Mock ArApTransactionRepository transactionRepository;
    @Mock ArApPaymentAllocationRepository allocationRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    private PayableSettlementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PayableSettlementServiceImpl(
                transactionRepository, allocationRepository, supplierRepository, eventPublisher);
    }

    @Test
    @DisplayName("partial payment atomically updates payable, payment, allocation and supplier balance")
    void partialSettlementPostsAllocation() {
        ArApTransaction payable = payable("100.00", "0.00", "100.00", PayablePaymentStatus.UNPAID);
        Supplier supplier = supplier("100.00");
        stubFresh(payable, supplier);

        PayableSettlementResult result = service.settle(
                FACTORY, PAYABLE_ID, request("40.00", "idem-1"), 9L);

        assertFalse(result.isReplayed());
        assertMoney("40.00", result.getSettledAmount());
        assertMoney("60.00", result.getOutstandingAmount());
        assertEquals(PayablePaymentStatus.PARTIALLY_PAID, result.getPaymentStatus());
        assertMoney("60.00", supplier.getCurrentBalance());

        ArgumentCaptor<ArApTransaction> transactionCaptor = ArgumentCaptor.forClass(ArApTransaction.class);
        verify(transactionRepository, org.mockito.Mockito.times(2)).save(transactionCaptor.capture());
        ArApTransaction payment = transactionCaptor.getAllValues().stream()
                .filter(t -> t.getTransactionType() == ArApTransactionType.AP_PAYMENT)
                .findFirst().orElseThrow();
        assertMoney("-40.00", payment.getAmount());
        assertEquals("PAYABLE_SETTLEMENT", payment.getSourceType());
        assertEquals("idem-1", payment.getSourceId());
        assertEquals(PAYABLE_ID, payable.getId());

        ArgumentCaptor<ArApPaymentAllocation> allocationCaptor =
                ArgumentCaptor.forClass(ArApPaymentAllocation.class);
        verify(allocationRepository).save(allocationCaptor.capture());
        assertEquals(payment.getId(), allocationCaptor.getValue().getPaymentTransactionId());
        assertEquals(PAYABLE_ID, allocationCaptor.getValue().getPayableTransactionId());
        assertMoney("40.00", allocationCaptor.getValue().getAllocatedAmount());
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    @DisplayName("full payment closes payable")
    void fullSettlementMarksPaid() {
        ArApTransaction payable = payable("100.00", "40.00", "60.00", PayablePaymentStatus.PARTIALLY_PAID);
        Supplier supplier = supplier("60.00");
        stubFresh(payable, supplier);

        PayableSettlementResult result = service.settle(
                FACTORY, PAYABLE_ID, request("60.00", "idem-full"), 9L);

        assertEquals(PayablePaymentStatus.PAID, result.getPaymentStatus());
        assertMoney("100.00", result.getSettledAmount());
        assertMoney("0.00", result.getOutstandingAmount());
        assertMoney("0.00", supplier.getCurrentBalance());
    }

    @Test
    @DisplayName("overpayment fails before any mutation")
    void overpaymentRejected() {
        ArApTransaction payable = payable("100.00", "40.00", "60.00", PayablePaymentStatus.PARTIALLY_PAID);
        when(transactionRepository.findPayableForSettlement(FACTORY, PAYABLE_ID))
                .thenReturn(Optional.of(payable));
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, "PAYABLE_SETTLEMENT", "idem-over", ArApTransactionType.AP_PAYMENT))
                .thenReturn(Optional.empty());
        when(transactionRepository
                .findByFactoryIdAndPurchaseOrderIdAndCounterpartyIdAndTransactionTypeAndDeletedAtIsNull(
                        FACTORY, "PO-1", SUPPLIER_ID, ArApTransactionType.AP_PAYMENT))
                .thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class, () ->
                service.settle(FACTORY, PAYABLE_ID, request("60.01", "idem-over"), 9L));

        assertEquals("AP_PAYMENT_EXCEEDS_OUTSTANDING", error.getErrorCode());
        verify(transactionRepository, never()).save(any());
        verify(allocationRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
    }

    @Test
    @DisplayName("supplier and currency identity are fail-closed")
    void identityMismatchRejected() {
        ArApTransaction payable = payable("100.00", "0.00", "100.00", PayablePaymentStatus.UNPAID);
        when(transactionRepository.findPayableForSettlement(FACTORY, PAYABLE_ID))
                .thenReturn(Optional.of(payable));
        PayableSettlementRequest request = request("10.00", "idem-identity");
        request.setSupplierId("SUP-OTHER");

        BusinessException supplierError = assertThrows(BusinessException.class, () ->
                service.settle(FACTORY, PAYABLE_ID, request, 9L));
        assertEquals("AP_SUPPLIER_IDENTITY_MISMATCH", supplierError.getErrorCode());

        request.setSupplierId(SUPPLIER_ID);
        request.setCurrencyCode("USD");
        BusinessException currencyError = assertThrows(BusinessException.class, () ->
                service.settle(FACTORY, PAYABLE_ID, request, 9L));
        assertEquals("AP_CURRENCY_MISMATCH", currencyError.getErrorCode());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("same idempotency key and payload replays existing result without writes")
    void idempotentReplay() {
        ArApTransaction payable = payable("100.00", "40.00", "60.00", PayablePaymentStatus.PARTIALLY_PAID);
        ArApTransaction payment = payment("PAY-1", "40.00", "idem-replay");
        ArApPaymentAllocation allocation = allocation(payment.getId(), "40.00");
        when(transactionRepository.findPayableForSettlement(FACTORY, PAYABLE_ID))
                .thenReturn(Optional.of(payable));
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, "PAYABLE_SETTLEMENT", "idem-replay", ArApTransactionType.AP_PAYMENT))
                .thenReturn(Optional.of(payment));
        when(allocationRepository.findByFactoryIdAndPaymentTransactionIdAndPayableTransactionId(
                FACTORY, payment.getId(), PAYABLE_ID)).thenReturn(Optional.of(allocation));

        PayableSettlementResult result = service.settle(
                FACTORY, PAYABLE_ID, request("40.00", "idem-replay"), 9L);

        assertTrue(result.isReplayed());
        assertEquals("PAY-1", result.getPaymentTransactionId());
        verify(transactionRepository, never()).save(any());
        verify(allocationRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("legacy unallocated payment blocks new settlement and is never auto-matched")
    void legacyUnallocatedPaymentBlocksSettlement() {
        ArApTransaction payable = payable("100.00", "0.00", "100.00", PayablePaymentStatus.UNPAID);
        ArApTransaction orphan = payment("PAY-OLD", "100.00", null);
        when(transactionRepository.findPayableForSettlement(FACTORY, PAYABLE_ID))
                .thenReturn(Optional.of(payable));
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, "PAYABLE_SETTLEMENT", "idem-new", ArApTransactionType.AP_PAYMENT))
                .thenReturn(Optional.empty());
        when(transactionRepository
                .findByFactoryIdAndPurchaseOrderIdAndCounterpartyIdAndTransactionTypeAndDeletedAtIsNull(
                        FACTORY, "PO-1", SUPPLIER_ID, ArApTransactionType.AP_PAYMENT))
                .thenReturn(List.of(orphan));
        when(allocationRepository.existsByFactoryIdAndPaymentTransactionId(FACTORY, "PAY-OLD"))
                .thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class, () ->
                service.settle(FACTORY, PAYABLE_ID, request("10.00", "idem-new"), 9L));

        assertEquals("AP_LEGACY_UNALLOCATED_PAYMENT", error.getErrorCode());
        verify(transactionRepository, never()).save(any());
        verify(allocationRepository, never()).save(any());
    }

    @Test
    @DisplayName("legacy payable with unknown allocations cannot be reopened as unpaid")
    void unreconciledLegacyPayableIsFailClosed() {
        ArApTransaction payable = payable(
                "100.00", "0.00", "100.00", PayablePaymentStatus.NEEDS_RECONCILIATION);
        payable.setSettledAmount(null);
        payable.setOutstandingAmount(null);
        when(transactionRepository.findPayableForSettlement(FACTORY, PAYABLE_ID))
                .thenReturn(Optional.of(payable));
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, "PAYABLE_SETTLEMENT", "idem-legacy", ArApTransactionType.AP_PAYMENT))
                .thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class, () ->
                service.settle(FACTORY, PAYABLE_ID, request("10.00", "idem-legacy"), 9L));

        assertEquals("AP_LEGACY_PAYABLE_NEEDS_RECONCILIATION", error.getErrorCode());
        verify(transactionRepository, never()).save(any());
        verify(allocationRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    private void stubFresh(ArApTransaction payable, Supplier supplier) {
        when(transactionRepository.findPayableForSettlement(FACTORY, PAYABLE_ID))
                .thenReturn(Optional.of(payable));
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq(FACTORY),
                org.mockito.ArgumentMatchers.eq("PAYABLE_SETTLEMENT"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(ArApTransactionType.AP_PAYMENT)))
                .thenReturn(Optional.empty());
        when(transactionRepository
                .findByFactoryIdAndPurchaseOrderIdAndCounterpartyIdAndTransactionTypeAndDeletedAtIsNull(
                        FACTORY, "PO-1", SUPPLIER_ID, ArApTransactionType.AP_PAYMENT))
                .thenReturn(List.of());
        when(transactionRepository.existsByFactoryIdAndPaymentReference(
                org.mockito.ArgumentMatchers.eq(FACTORY), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(false);
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY))
                .thenReturn(Optional.of(supplier));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(allocationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(supplierRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ArApTransaction payable(String amount, String settled, String outstanding, PayablePaymentStatus status) {
        ArApTransaction payable = new ArApTransaction();
        payable.setId(PAYABLE_ID);
        payable.setFactoryId(FACTORY);
        payable.setTransactionNumber("AP-1");
        payable.setTransactionType(ArApTransactionType.AP_INVOICE);
        payable.setCounterpartyType(CounterpartyType.SUPPLIER);
        payable.setCounterpartyId(SUPPLIER_ID);
        payable.setCounterpartyName("供应商一");
        payable.setPurchaseOrderId("PO-1");
        payable.setAmount(new BigDecimal(amount));
        payable.setBalanceAfter(new BigDecimal(amount));
        payable.setSettledAmount(new BigDecimal(settled));
        payable.setOutstandingAmount(new BigDecimal(outstanding));
        payable.setPaymentStatus(status);
        payable.setCurrencyCode("CNY");
        payable.setTransactionDate(LocalDate.of(2026, 7, 20));
        return payable;
    }

    private Supplier supplier(String balance) {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setFactoryId(FACTORY);
        supplier.setName("供应商一");
        supplier.setCurrentBalance(new BigDecimal(balance));
        return supplier;
    }

    private PayableSettlementRequest request(String amount, String idempotencyKey) {
        PayableSettlementRequest request = new PayableSettlementRequest();
        request.setSupplierId(SUPPLIER_ID);
        request.setAmount(new BigDecimal(amount));
        request.setCurrencyCode("CNY");
        request.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        request.setPaymentReference("BANK-" + idempotencyKey);
        request.setIdempotencyKey(idempotencyKey);
        request.setRemark("付款核销");
        return request;
    }

    private ArApTransaction payment(String id, String amount, String idempotencyKey) {
        ArApTransaction payment = new ArApTransaction();
        payment.setId(id);
        payment.setTransactionNumber("AP-PAY-1");
        payment.setFactoryId(FACTORY);
        payment.setTransactionType(ArApTransactionType.AP_PAYMENT);
        payment.setCounterpartyType(CounterpartyType.SUPPLIER);
        payment.setCounterpartyId(SUPPLIER_ID);
        payment.setPurchaseOrderId("PO-1");
        payment.setAmount(new BigDecimal(amount).negate());
        payment.setCurrencyCode("CNY");
        payment.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        payment.setPaymentReference("BANK-" + idempotencyKey);
        payment.setSourceType(idempotencyKey != null ? "PAYABLE_SETTLEMENT" : null);
        payment.setSourceId(idempotencyKey);
        return payment;
    }

    private ArApPaymentAllocation allocation(String paymentId, String amount) {
        ArApPaymentAllocation allocation = new ArApPaymentAllocation();
        allocation.setId("ALLOC-1");
        allocation.setFactoryId(FACTORY);
        allocation.setPaymentTransactionId(paymentId);
        allocation.setPayableTransactionId(PAYABLE_ID);
        allocation.setAllocatedAmount(new BigDecimal(amount));
        allocation.setCurrencyCode("CNY");
        allocation.setOperatedBy(9L);
        return allocation;
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
