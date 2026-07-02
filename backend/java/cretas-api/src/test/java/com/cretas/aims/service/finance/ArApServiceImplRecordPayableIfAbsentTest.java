package com.cretas.aims.service.finance;

import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 🔒 doomed-tx 回归测试 (2026-07-02): 采购入库 confirmReceive 对同一 PO 分批入库自动挂账。
 *
 * <p>Bug: {@code confirmReceive}(共享事务) 调 {@code recordPayable}, 后者对已挂账的 PO 抛
 * {@code BusinessException}(409)。因 recordPayable 是 {@code @Transactional} 已 join 当前事务,
 * 抛异常把事务标记 rollback-only —— confirmReceive 的 try/catch 也救不回, 外层 commit 抛
 * {@code UnexpectedRollbackException} → doomed-tx 兜底转误导性 409 → 同一 PO 第 2 次分批入库
 * <b>永久无法确认</b>。
 *
 * <p>Fix: 新增 {@link ArApService#recordPayableIfAbsent} —— 对"已存在/金额缺失/供应商缺失/PO 状态
 * 不允许"等预期条件返回 existing / null 而<b>绝不抛异常</b>, 事务从不被 doom。
 *
 * <p>核心断言: 对已挂账的 PO 再次调用 recordPayableIfAbsent <b>不抛异常</b>, 返回已存在记录,
 * 且<b>不重复写库</b>(不再次更新供应商余额 / 不再 save 新交易 → 金额不双计)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArApServiceImpl recordPayableIfAbsent (采购入库自动挂账幂等, doomed-tx 修复)")
class ArApServiceImplRecordPayableIfAbsentTest {

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;

    private static final String FACTORY = "F006";
    private static final String SUPPLIER = "SUP-001";
    private static final String PO_ID = "PO-2026-0001";

    private ArApServiceImpl service() {
        ArApServiceImpl service = new ArApServiceImpl(transactionRepository, customerRepository, supplierRepository);
        // purchaseOrderRepository is @Autowired field-injected in ArApServiceImpl.
        ReflectionTestUtils.setField(service, "purchaseOrderRepository", purchaseOrderRepository);
        return service;
    }

    private Supplier supplier(BigDecimal balance) {
        Supplier s = new Supplier();
        s.setId(SUPPLIER);
        s.setFactoryId(FACTORY);
        s.setName("测试供应商");
        s.setCurrentBalance(balance);
        return s;
    }

    private PurchaseOrder po(PurchaseOrderStatus status) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(PO_ID);
        po.setFactoryId(FACTORY);
        po.setStatus(status);
        return po;
    }

    @Test
    @DisplayName("首次入库 — 无已存在应付 → 创建 AP_INVOICE 并更新供应商余额")
    void firstReceive_createsPayable() {
        ArApServiceImpl service = service();
        when(transactionRepository.findFirstByFactoryIdAndPurchaseOrderIdAndTransactionType(
                FACTORY, PO_ID, ArApTransactionType.AP_INVOICE)).thenReturn(Optional.empty());
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER, FACTORY)).thenReturn(Optional.of(supplier(new BigDecimal("100.00"))));
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po(PurchaseOrderStatus.FINANCE_APPROVED)));
        when(transactionRepository.save(any(ArApTransaction.class))).thenAnswer(inv -> {
            ArApTransaction t = inv.getArgument(0);
            t.setId("AP-NEW-1");
            return t;
        });

        ArApTransaction result = service.recordPayableIfAbsent(
                FACTORY, SUPPLIER, PO_ID, new BigDecimal("500.00"), LocalDate.of(2026, 8, 1), 9L, "首次入库自动挂账");

        assertNotNull(result);
        assertEquals("AP-NEW-1", result.getId());
        assertEquals(ArApTransactionType.AP_INVOICE, result.getTransactionType());
        assertEquals(CounterpartyType.SUPPLIER, result.getCounterpartyType());
        assertEquals(PO_ID, result.getPurchaseOrderId());
        assertEquals(0, new BigDecimal("500.00").compareTo(result.getAmount()));
        ArgumentCaptor<Supplier> supCaptor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierRepository).save(supCaptor.capture());
        assertEquals(0, new BigDecimal("600.00").compareTo(supCaptor.getValue().getCurrentBalance()));
    }

    @Test
    @DisplayName("🔒 第 2 次入库 — PO 已有应付 → 返回已存在记录, 不抛异常, 不重复写库(金额不双计)")
    void secondReceive_existingApIsIdempotentNoThrow() {
        ArApServiceImpl service = service();
        ArApTransaction existing = new ArApTransaction();
        existing.setId("AP-EXISTING");
        existing.setPurchaseOrderId(PO_ID);
        existing.setTransactionType(ArApTransactionType.AP_INVOICE);
        when(transactionRepository.findFirstByFactoryIdAndPurchaseOrderIdAndTransactionType(
                FACTORY, PO_ID, ArApTransactionType.AP_INVOICE)).thenReturn(Optional.of(existing));

        ArApTransaction result = assertDoesNotThrow(() -> service.recordPayableIfAbsent(
                FACTORY, SUPPLIER, PO_ID, new BigDecimal("500.00"), LocalDate.of(2026, 8, 1), 9L, "第2次入库自动挂账"));

        assertSame(existing, result, "应返回已存在的应付记录 (幂等)");
        // 不重复写库: 供应商余额不再更新, 不再 save 新交易 → 整单金额不被双计。
        verify(supplierRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
        // 幂等命中在供应商/PO 查询之前短路。
        verifyNoInteractions(purchaseOrderRepository);
    }

    @Test
    @DisplayName("金额缺失 → 返回 null 跳过, 不抛异常, 不写库")
    void missingAmount_skipsNoThrow() {
        ArApServiceImpl service = service();

        ArApTransaction result = assertDoesNotThrow(() -> service.recordPayableIfAbsent(
                FACTORY, SUPPLIER, PO_ID, null, LocalDate.of(2026, 8, 1), 9L, "缺金额"));

        assertNull(result);
        verify(transactionRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
    }

    @Test
    @DisplayName("金额非正 → 返回 null 跳过, 不抛异常, 不写库")
    void nonPositiveAmount_skipsNoThrow() {
        ArApServiceImpl service = service();

        ArApTransaction result = assertDoesNotThrow(() -> service.recordPayableIfAbsent(
                FACTORY, SUPPLIER, PO_ID, BigDecimal.ZERO, LocalDate.of(2026, 8, 1), 9L, "零金额"));

        assertNull(result);
        verify(transactionRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
    }

    @Test
    @DisplayName("供应商不存在 → 返回 null 跳过, 不抛异常, 不写库")
    void missingSupplier_skipsNoThrow() {
        ArApServiceImpl service = service();
        when(transactionRepository.findFirstByFactoryIdAndPurchaseOrderIdAndTransactionType(
                FACTORY, PO_ID, ArApTransactionType.AP_INVOICE)).thenReturn(Optional.empty());
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER, FACTORY)).thenReturn(Optional.empty());

        ArApTransaction result = assertDoesNotThrow(() -> service.recordPayableIfAbsent(
                FACTORY, SUPPLIER, PO_ID, new BigDecimal("500.00"), LocalDate.of(2026, 8, 1), 9L, "无供应商"));

        assertNull(result);
        verify(transactionRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
    }

    @Test
    @DisplayName("采购订单状态不允许挂账 → 返回 null 跳过, 不抛异常, 不阻塞收货")
    void poNotInvoiceable_skipsNoThrow() {
        ArApServiceImpl service = service();
        when(transactionRepository.findFirstByFactoryIdAndPurchaseOrderIdAndTransactionType(
                FACTORY, PO_ID, ArApTransactionType.AP_INVOICE)).thenReturn(Optional.empty());
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER, FACTORY)).thenReturn(Optional.of(supplier(new BigDecimal("0.00"))));
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po(PurchaseOrderStatus.DRAFT)));

        ArApTransaction result = assertDoesNotThrow(() -> service.recordPayableIfAbsent(
                FACTORY, SUPPLIER, PO_ID, new BigDecimal("500.00"), LocalDate.of(2026, 8, 1), 9L, "PO草稿"));

        assertNull(result);
        verify(transactionRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
    }
}
