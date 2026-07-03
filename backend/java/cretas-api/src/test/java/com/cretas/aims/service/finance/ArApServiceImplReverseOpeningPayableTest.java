package com.cretas.aims.service.finance;

import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ArApServiceImpl.reverseOpeningPayable 单元测试 (期初建账修正: 红冲幽灵应付).
 *
 * <p>核心: 客户误把期初存货当采购入库 → 挂了供应商应付。reverseOpeningPayable 建一笔反向
 * AP_CREDIT_NOTE (金额取负) 把供应商余额减回, 净额归零。幂等 (同一应付重复调用不双冲) +
 * 类型校验 (只能红冲 AP_INVOICE)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArApServiceImpl reverseOpeningPayable (期初建账修正: 红冲幽灵应付)")
class ArApServiceImplReverseOpeningPayableTest {

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SupplierRepository supplierRepository;

    private static final String FACTORY = "F006";
    private static final String SUPPLIER = "SUP-001";
    private static final String AP_ID = "AP-2026-0001";
    private static final String SRC = "OPENING_AP_CORRECTION";

    private ArApServiceImpl service() {
        return new ArApServiceImpl(transactionRepository, customerRepository, supplierRepository);
    }

    private ArApTransaction phantomAp(BigDecimal amount) {
        ArApTransaction t = new ArApTransaction();
        t.setId(AP_ID);
        t.setFactoryId(FACTORY);
        t.setTransactionNumber("AP-20260701-0001");
        t.setTransactionType(ArApTransactionType.AP_INVOICE);
        t.setCounterpartyType(CounterpartyType.SUPPLIER);
        t.setCounterpartyId(SUPPLIER);
        t.setAmount(amount);
        return t;
    }

    private Supplier supplier(BigDecimal balance) {
        Supplier s = new Supplier();
        s.setId(SUPPLIER);
        s.setFactoryId(FACTORY);
        s.setName("测试供应商");
        s.setCurrentBalance(balance);
        return s;
    }

    @Test
    @DisplayName("红冲幽灵应付 → 建 AP_CREDIT_NOTE(负额) + 供应商余额减回")
    void reverse_createsCreditNote_decrementsBalance() {
        ArApServiceImpl service = service();
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SRC, AP_ID, ArApTransactionType.AP_CREDIT_NOTE)).thenReturn(Optional.empty());
        when(transactionRepository.findById(AP_ID)).thenReturn(Optional.of(phantomAp(new BigDecimal("436632.00"))));
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER, FACTORY))
                .thenReturn(Optional.of(supplier(new BigDecimal("436632.00"))));
        when(transactionRepository.save(any(ArApTransaction.class))).thenAnswer(inv -> {
            ArApTransaction t = inv.getArgument(0);
            t.setId("REV-1");
            return t;
        });

        ArApTransaction reversal = service.reverseOpeningPayable(FACTORY, AP_ID, "期初误挂", 9L);

        assertEquals(ArApTransactionType.AP_CREDIT_NOTE, reversal.getTransactionType());
        assertEquals(0, new BigDecimal("-436632.00").compareTo(reversal.getAmount()), "红冲额取负");
        assertEquals(SRC, reversal.getSourceType());
        assertEquals(AP_ID, reversal.getSourceId());

        // supplier balance restored to 0 (436632 - 436632).
        ArgumentCaptor<Supplier> supCap = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierRepository).save(supCap.capture());
        assertEquals(0, BigDecimal.ZERO.compareTo(supCap.getValue().getCurrentBalance()));
    }

    @Test
    @DisplayName("幂等: 该应付已红冲过 → 返回既有红冲, 不再冲减余额")
    void reverse_idempotent_returnsExisting() {
        ArApServiceImpl service = service();
        ArApTransaction existing = new ArApTransaction();
        existing.setId("REV-EXISTING");
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SRC, AP_ID, ArApTransactionType.AP_CREDIT_NOTE)).thenReturn(Optional.of(existing));

        ArApTransaction reversal = service.reverseOpeningPayable(FACTORY, AP_ID, "重复", 9L);

        assertSame(existing, reversal);
        verify(supplierRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
        verify(transactionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("类型不符 (非 AP_INVOICE) → 抛业务异常, 不写库")
    void reverse_wrongType_throws() {
        ArApServiceImpl service = service();
        ArApTransaction payment = phantomAp(new BigDecimal("100"));
        payment.setTransactionType(ArApTransactionType.AP_PAYMENT);
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SRC, AP_ID, ArApTransactionType.AP_CREDIT_NOTE)).thenReturn(Optional.empty());
        when(transactionRepository.findById(AP_ID)).thenReturn(Optional.of(payment));

        assertThrows(BusinessException.class,
                () -> service.reverseOpeningPayable(FACTORY, AP_ID, null, 9L));
        verify(supplierRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("跨租户/不存在 → 抛异常, 不写库")
    void reverse_notFoundOrCrossTenant_throws() {
        ArApServiceImpl service = service();
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SRC, AP_ID, ArApTransactionType.AP_CREDIT_NOTE)).thenReturn(Optional.empty());
        when(transactionRepository.findById(AP_ID)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class,
                () -> service.reverseOpeningPayable(FACTORY, AP_ID, null, 9L));
        verify(transactionRepository, never()).save(any());
    }
}
