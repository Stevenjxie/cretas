package com.cretas.aims.service.finance;

import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PayablePaymentStatus;
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
 * 🔒 doomed-tx 回归 + 🔴🔒 应付金额口径回归 (2026-07-02 / 2026-07-03):
 * 采购入库 confirmReceive 对同一 PO 分批入库自动挂账。
 *
 * <p>doomed-tx bug (2026-07-02): {@code confirmReceive}(共享事务) 调 {@code recordPayable}, 后者对已挂账的
 * PO 抛 {@code BusinessException}(409)。因 recordPayable 是 {@code @Transactional} 已 join 当前事务, 抛异常把
 * 事务标记 rollback-only —— confirmReceive 的 try/catch 也救不回, 外层 commit 抛 {@code UnexpectedRollbackException}
 * → doomed-tx 兜底转误导性 409 → 同一 PO 第 2 次分批入库<b>永久无法确认</b>。
 *
 * <p>金额口径 bug (🔴🔒 2026-07-03): 旧实现按 <b>PO 计划总额</b> 挂账 + 按 PO 幂等。六膳门超收 130kg (计划 100kg)
 * 时应付只挂 100×20=2000, 实收 130×20=2600, 差 600 永久漏挂; 且分批入库只挂首笔全额。Fix: 按<b>每张入库单实收金额</b>
 * (Σ 实收数量 × 单价) 分笔挂账, 幂等键 = {@code (sourceType=PURCHASE_RECEIVE, sourceId=receiveId)}。
 *
 * <p>Fix: {@link ArApService#recordPayableIfAbsent} —— 对"已存在/金额缺失/供应商缺失/PO 状态不允许"等预期条件
 * 返回 existing / null 而<b>绝不抛异常</b>, 事务从不被 doom。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArApServiceImpl recordPayableIfAbsent (采购入库自动挂账: 实收值 + per-receive 幂等, doomed-tx 修复)")
class ArApServiceImplRecordPayableIfAbsentTest {

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;

    private static final String FACTORY = "F006";
    private static final String SUPPLIER = "SUP-001";
    private static final String PO_ID = "PO-2026-0001";
    private static final String SOURCE_TYPE = "PURCHASE_RECEIVE";
    private static final String RECEIVE_ID = "RCV-2026-0001";

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
    @DisplayName("🔴🔒 首次入库 — AP = 本次入库单实收金额 (130×20=2600), 非 PO 计划总额 (100×20=2000)")
    void firstReceive_booksActualReceivedValueNotPoPlanned() {
        ArApServiceImpl service = service();
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, RECEIVE_ID, ArApTransactionType.AP_INVOICE)).thenReturn(Optional.empty());
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER, FACTORY)).thenReturn(Optional.of(supplier(new BigDecimal("100.00"))));
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po(PurchaseOrderStatus.FINANCE_APPROVED)));
        when(transactionRepository.save(any(ArApTransaction.class))).thenAnswer(inv -> {
            ArApTransaction t = inv.getArgument(0);
            t.setId("AP-NEW-1");
            return t;
        });

        // 实收 130kg × ¥20 = ¥2600 (PO 计划 100kg × ¥20 = ¥2000, 旧实现的错值)
        ArApTransaction result = service.recordPayableIfAbsent(
                FACTORY, SUPPLIER, PO_ID, SOURCE_TYPE, RECEIVE_ID,
                new BigDecimal("2600.00"), LocalDate.of(2026, 8, 1), 9L, "首次入库自动挂账");

        assertNotNull(result);
        assertEquals("AP-NEW-1", result.getId());
        assertEquals(ArApTransactionType.AP_INVOICE, result.getTransactionType());
        assertEquals(CounterpartyType.SUPPLIER, result.getCounterpartyType());
        assertEquals(PO_ID, result.getPurchaseOrderId());
        assertEquals(SOURCE_TYPE, result.getSourceType(), "幂等键 sourceType 应写入");
        assertEquals(RECEIVE_ID, result.getSourceId(), "幂等键 sourceId (receiveId) 应写入");
        assertEquals(0, new BigDecimal("2600.00").compareTo(result.getAmount()), "AP 应=实收值 2600, 非计划 2000");
        assertEquals(0, new BigDecimal("0.00").compareTo(result.getSettledAmount()));
        assertEquals(0, new BigDecimal("2600.00").compareTo(result.getOutstandingAmount()));
        assertEquals(PayablePaymentStatus.UNPAID, result.getPaymentStatus());
        assertEquals("CNY", result.getCurrencyCode());
        ArgumentCaptor<Supplier> supCaptor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierRepository).save(supCaptor.capture());
        assertEquals(0, new BigDecimal("2700.00").compareTo(supCaptor.getValue().getCurrentBalance()),
                "供应商余额 100 + 实收 2600 = 2700");
    }

    @Test
    @DisplayName("🔒 同一入库单重复确认 — source 已挂账 → 返回已存在记录, 不抛异常, 不重复写库(金额不双计)")
    void sameReceiveTwice_idempotentBySourceNoThrow() {
        ArApServiceImpl service = service();
        ArApTransaction existing = new ArApTransaction();
        existing.setId("AP-EXISTING");
        existing.setPurchaseOrderId(PO_ID);
        existing.setSourceType(SOURCE_TYPE);
        existing.setSourceId(RECEIVE_ID);
        existing.setTransactionType(ArApTransactionType.AP_INVOICE);
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, RECEIVE_ID, ArApTransactionType.AP_INVOICE)).thenReturn(Optional.of(existing));

        ArApTransaction result = assertDoesNotThrow(() -> service.recordPayableIfAbsent(
                FACTORY, SUPPLIER, PO_ID, SOURCE_TYPE, RECEIVE_ID,
                new BigDecimal("2600.00"), LocalDate.of(2026, 8, 1), 9L, "重复确认"));

        assertSame(existing, result, "应返回已存在的应付记录 (幂等)");
        // 不重复写库: 供应商余额不再更新, 不再 save 新交易 → 金额不被双计。
        verify(supplierRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
        // 幂等命中在供应商/PO 查询之前短路。
        verifyNoInteractions(purchaseOrderRepository);
    }

    @Test
    @DisplayName("分批入库 — 不同入库单各挂各的实收值 (40+60), 累计=实收总额, 不受 PO 计划总额限制")
    void partialReceives_accumulatePerReceive() {
        ArApServiceImpl service = service();
        String receive1 = "RCV-A";
        String receive2 = "RCV-B";
        // 两张入库单都无既有 source-AP → 都新建。
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                eq(FACTORY), eq(SOURCE_TYPE), anyString(), eq(ArApTransactionType.AP_INVOICE)))
                .thenReturn(Optional.empty());
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po(PurchaseOrderStatus.FINANCE_APPROVED)));
        // 单一供应商对象, 余额随两次挂账累加。
        Supplier sup = supplier(new BigDecimal("0.00"));
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER, FACTORY)).thenReturn(Optional.of(sup));
        when(transactionRepository.save(any(ArApTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        ArApTransaction ap1 = service.recordPayableIfAbsent(FACTORY, SUPPLIER, PO_ID, SOURCE_TYPE, receive1,
                new BigDecimal("800.00"), LocalDate.of(2026, 8, 1), 9L, "分批1: 40kg×20");
        ArApTransaction ap2 = service.recordPayableIfAbsent(FACTORY, SUPPLIER, PO_ID, SOURCE_TYPE, receive2,
                new BigDecimal("1200.00"), LocalDate.of(2026, 8, 1), 9L, "分批2: 60kg×20");

        assertEquals(0, new BigDecimal("800.00").compareTo(ap1.getAmount()));
        assertEquals(0, new BigDecimal("1200.00").compareTo(ap2.getAmount()));
        assertEquals(receive1, ap1.getSourceId());
        assertEquals(receive2, ap2.getSourceId());
        // 两笔各建一条 → 供应商余额累计 = 800 + 1200 = 2000 (实收总额)。
        assertEquals(0, new BigDecimal("2000.00").compareTo(sup.getCurrentBalance()));
        verify(transactionRepository, times(2)).save(any(ArApTransaction.class));
    }

    @Test
    @DisplayName("金额缺失(入库单无单价) → 返回 null 诚实跳过, 不抛异常, 不写库(不伪造 0 应付)")
    void missingAmount_skipsNoThrow() {
        ArApServiceImpl service = service();

        ArApTransaction result = assertDoesNotThrow(() -> service.recordPayableIfAbsent(
                FACTORY, SUPPLIER, PO_ID, SOURCE_TYPE, RECEIVE_ID, null, LocalDate.of(2026, 8, 1), 9L, "缺金额"));

        assertNull(result);
        verify(transactionRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
    }

    @Test
    @DisplayName("金额非正(实收值为 0) → 返回 null 跳过, 不抛异常, 不写库")
    void nonPositiveAmount_skipsNoThrow() {
        ArApServiceImpl service = service();

        ArApTransaction result = assertDoesNotThrow(() -> service.recordPayableIfAbsent(
                FACTORY, SUPPLIER, PO_ID, SOURCE_TYPE, RECEIVE_ID, BigDecimal.ZERO, LocalDate.of(2026, 8, 1), 9L, "零金额"));

        assertNull(result);
        verify(transactionRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
    }

    @Test
    @DisplayName("供应商不存在 → 返回 null 跳过, 不抛异常, 不写库")
    void missingSupplier_skipsNoThrow() {
        ArApServiceImpl service = service();
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, RECEIVE_ID, ArApTransactionType.AP_INVOICE)).thenReturn(Optional.empty());
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER, FACTORY)).thenReturn(Optional.empty());

        ArApTransaction result = assertDoesNotThrow(() -> service.recordPayableIfAbsent(
                FACTORY, SUPPLIER, PO_ID, SOURCE_TYPE, RECEIVE_ID, new BigDecimal("500.00"), LocalDate.of(2026, 8, 1), 9L, "无供应商"));

        assertNull(result);
        verify(transactionRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
    }

    @Test
    @DisplayName("采购订单状态不允许挂账 → 返回 null 跳过, 不抛异常, 不阻塞收货")
    void poNotInvoiceable_skipsNoThrow() {
        ArApServiceImpl service = service();
        when(transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, RECEIVE_ID, ArApTransactionType.AP_INVOICE)).thenReturn(Optional.empty());
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER, FACTORY)).thenReturn(Optional.of(supplier(new BigDecimal("0.00"))));
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po(PurchaseOrderStatus.DRAFT)));

        ArApTransaction result = assertDoesNotThrow(() -> service.recordPayableIfAbsent(
                FACTORY, SUPPLIER, PO_ID, SOURCE_TYPE, RECEIVE_ID, new BigDecimal("500.00"), LocalDate.of(2026, 8, 1), 9L, "PO草稿"));

        assertNull(result);
        verify(transactionRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
    }
}
