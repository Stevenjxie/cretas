package com.cretas.aims.service.finance;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PayablePaymentStatus;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArApServiceImpl restaurant source payable")
class ArApServiceImplRestaurantSourceTest {

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SupplierRepository supplierRepository;

    private static final String FACTORY = "RES_3101_009";
    private static final String SUPPLIER = "SUP-QHJ";
    private static final String SOURCE_TYPE = "SUPPLIER_DELIVERY_NOTE";
    private static final String SOURCE_ID = "SDN-QHJ-001";

    @Test
    @DisplayName("recordPayableFromSource creates AP invoice and supplier balance")
    void recordPayableFromSource_createsApInvoice() {
        ArApServiceImpl service = service();
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER);
        supplier.setFactoryId(FACTORY);
        supplier.setName("QHJ Supplier");
        supplier.setCurrentBalance(new BigDecimal("20.00"));

        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, SOURCE_ID, ArApTransactionType.AP_INVOICE))
                .thenReturn(Optional.empty());
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER, FACTORY)).thenReturn(Optional.of(supplier));
        when(transactionRepository.save(any(ArApTransaction.class))).thenAnswer(inv -> {
            ArApTransaction transaction = inv.getArgument(0);
            transaction.setId("AP-SDN-1");
            return transaction;
        });

        ArApTransaction result = service.recordPayableFromSource(
                FACTORY, SUPPLIER, SOURCE_TYPE, SOURCE_ID,
                new BigDecimal("125.00"), LocalDate.of(2026, 7, 5), 9L, "restaurant inbound");

        assertEquals("AP-SDN-1", result.getId());
        assertEquals(ArApTransactionType.AP_INVOICE, result.getTransactionType());
        assertEquals(CounterpartyType.SUPPLIER, result.getCounterpartyType());
        assertEquals(SOURCE_TYPE, result.getSourceType());
        assertEquals(SOURCE_ID, result.getSourceId());
        assertNull(result.getPurchaseOrderId());
        assertEquals(0, new BigDecimal("125.00").compareTo(result.getAmount()));
        assertEquals(0, new BigDecimal("0.00").compareTo(result.getSettledAmount()));
        assertEquals(0, new BigDecimal("125.00").compareTo(result.getOutstandingAmount()));
        assertEquals(PayablePaymentStatus.UNPAID, result.getPaymentStatus());
        assertEquals("CNY", result.getCurrencyCode());
        assertEquals(0, new BigDecimal("145.00").compareTo(supplier.getCurrentBalance()));
        verify(supplierRepository).save(supplier);
    }

    @Test
    @DisplayName("recordPayableFromSource returns existing transaction without duplicate balance")
    void recordPayableFromSource_existingIsIdempotent() {
        ArApServiceImpl service = service();
        ArApTransaction existing = new ArApTransaction();
        existing.setId("AP-EXISTING");
        existing.setSourceType(SOURCE_TYPE);
        existing.setSourceId(SOURCE_ID);

        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, SOURCE_ID, ArApTransactionType.AP_INVOICE))
                .thenReturn(Optional.of(existing));

        ArApTransaction result = service.recordPayableFromSource(
                FACTORY, SUPPLIER, SOURCE_TYPE, SOURCE_ID,
                new BigDecimal("125.00"), LocalDate.of(2026, 7, 5), 9L, "retry");

        assertSame(existing, result);
        verifyNoInteractions(supplierRepository);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordPayableFromSource rejects missing amount")
    void recordPayableFromSource_missingAmountFailsClosed() {
        ArApServiceImpl service = service();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recordPayableFromSource(
                        FACTORY, SUPPLIER, SOURCE_TYPE, SOURCE_ID,
                        null, LocalDate.of(2026, 7, 5), 9L, "missing amount"));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("缺少金额"));
        verifyNoInteractions(transactionRepository, supplierRepository);
    }

    @Test
    @DisplayName("recordPayableFromSource persists expected transaction source")
    void recordPayableFromSource_persistsSourceFields() {
        ArApServiceImpl service = service();
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER);
        supplier.setFactoryId(FACTORY);
        supplier.setName("QHJ Supplier");

        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, SOURCE_ID, ArApTransactionType.AP_INVOICE))
                .thenReturn(Optional.empty());
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER, FACTORY)).thenReturn(Optional.of(supplier));
        when(transactionRepository.save(any(ArApTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordPayableFromSource(
                FACTORY, SUPPLIER, SOURCE_TYPE, SOURCE_ID,
                new BigDecimal("88.00"), LocalDate.of(2026, 7, 5), 9L, "restaurant inbound");

        ArgumentCaptor<ArApTransaction> captor = ArgumentCaptor.forClass(ArApTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertEquals(SOURCE_TYPE, captor.getValue().getSourceType());
        assertEquals(SOURCE_ID, captor.getValue().getSourceId());
        assertEquals("QHJ Supplier", captor.getValue().getCounterpartyName());
    }

    private ArApServiceImpl service() {
        return new ArApServiceImpl(transactionRepository, customerRepository, supplierRepository);
    }
}
