package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PayrollRecordRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.service.voucher.VoucherGeneratorRegistry;
import com.cretas.aims.service.voucher.impl.SalesReceiptVoucherGenerator;
import com.cretas.aims.exception.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoucherServiceImplTest {

    @Mock private VoucherRepository voucherRepo;
    @Mock private VoucherGeneratorRegistry registry;
    @Mock private SalesOrderRepository salesOrderRepo;
    @Mock private PurchaseOrderRepository purchaseOrderRepo;
    @Mock private ReturnOrderRepository returnOrderRepo;
    @Mock private InternalTransferRepository internalTransferRepo;
    @Mock private WastageRecordRepository wastageRecordRepo;
    @Mock private PayrollRecordRepository payrollRecordRepo;
    @Mock private ProductionPlanRepository productionPlanRepo;
    @Mock private LinkArrayService linkArrayService;

    @InjectMocks
    private VoucherServiceImpl service;

    private final SalesReceiptVoucherGenerator realGenerator = new SalesReceiptVoucherGenerator();

    @BeforeEach
    void setUp() {
        // No-op — Mockito wires mocks
    }

    @Test
    void createFromBusinessReturnsExistingVoucherWhenIdempotentHit() {
        Voucher existing = Voucher.builder().id("v-1").factoryId("F001").build();
        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("SALES_ORDER", "so-1"))
                .thenReturn(Optional.of(existing));

        Voucher result = service.createFromBusiness("F001", "SALES_ORDER", "so-1");

        assertSame(existing, result);
        verifyNoInteractions(registry);
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void createFromBusinessHappyPathGeneratesAndSaves() {
        SalesOrder order = new SalesOrder();
        order.setId("so-1");
        order.setOrderNumber("SO-2026-0001");
        order.setFactoryId("F001");
        order.setOrderDate(LocalDate.of(2026, 5, 16));
        order.setTotalAmount(new BigDecimal("500.00"));

        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("SALES_ORDER", "so-1"))
                .thenReturn(Optional.empty());
        when(salesOrderRepo.findById("so-1")).thenReturn(Optional.of(order));
        when(registry.findByBusinessType("SALES_ORDER")).thenReturn(Optional.of(realGenerator));
        when(voucherRepo.countByFactoryIdAndYear(eq("F001"), eq("2026"))).thenReturn(0L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> inv.getArgument(0));

        Voucher v = service.createFromBusiness("F001", "SALES_ORDER", "so-1");

        assertEquals("V-2026-0001", v.getVoucherNumber());
        assertEquals(VoucherStatus.DRAFT, v.getStatus());
        assertEquals(new BigDecimal("500.00"), v.getTotalDebit());
        assertEquals(new BigDecimal("500.00"), v.getTotalCredit());
        verify(voucherRepo).save(any(Voucher.class));
    }

    @Test
    void createFromBusinessCallsLinkArrayServiceAfterSave() {
        SalesOrder order = new SalesOrder();
        order.setId("so-1");
        order.setOrderNumber("SO-2026-0001");
        order.setFactoryId("F001");
        order.setOrderDate(LocalDate.of(2026, 5, 16));
        order.setTotalAmount(new BigDecimal("500.00"));

        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("SALES_ORDER", "so-1"))
                .thenReturn(Optional.empty());
        when(salesOrderRepo.findById("so-1")).thenReturn(Optional.of(order));
        when(registry.findByBusinessType("SALES_ORDER")).thenReturn(Optional.of(realGenerator));
        when(voucherRepo.countByFactoryIdAndYear(eq("F001"), eq("2026"))).thenReturn(0L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            v.setId("v-new-1");
            return v;
        });

        service.createFromBusiness("F001", "SALES_ORDER", "so-1");

        verify(linkArrayService).link(
                eq("F001"),
                eq("VOUCHER"), eq("v-new-1"),
                eq("sale"),
                eq("SALES_ORDER"), eq("so-1"),
                any(), eq(null));
    }

    @Test
    void createFromBusinessSwallowsLinkArrayServiceException() {
        SalesOrder order = new SalesOrder();
        order.setId("so-2");
        order.setOrderNumber("SO-2026-0002");
        order.setFactoryId("F001");
        order.setOrderDate(LocalDate.of(2026, 5, 16));
        order.setTotalAmount(new BigDecimal("300.00"));

        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("SALES_ORDER", "so-2"))
                .thenReturn(Optional.empty());
        when(salesOrderRepo.findById("so-2")).thenReturn(Optional.of(order));
        when(registry.findByBusinessType("SALES_ORDER")).thenReturn(Optional.of(realGenerator));
        when(voucherRepo.countByFactoryIdAndYear(eq("F001"), eq("2026"))).thenReturn(0L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            v.setId("v-new-2");
            return v;
        });
        when(linkArrayService.link(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("link table locked"));

        Voucher v = assertDoesNotThrow(
                () -> service.createFromBusiness("F001", "SALES_ORDER", "so-2"));

        assertEquals("V-2026-0001", v.getVoucherNumber());
        verify(voucherRepo).save(any(Voucher.class));
    }

    @Test
    void createFromBusinessThrowsWhenEntityMissing() {
        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("SALES_ORDER", "missing"))
                .thenReturn(Optional.empty());
        when(salesOrderRepo.findById("missing")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.createFromBusiness("F001", "SALES_ORDER", "missing"));
    }

    @Test
    void createDepreciationGeneratesFromMapInput() {
        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("DEPRECATION", "DEP-202605"))
                .thenReturn(Optional.empty());
        when(registry.findByBusinessType("DEPRECATION"))
                .thenReturn(Optional.of(new DepreciationVoucherGenerator()));
        when(voucherRepo.countByFactoryIdAndYear(eq("F001"), eq("2026"))).thenReturn(5L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> input = Map.of(
                "businessId", "DEP-202605",
                "amount", new BigDecimal("1000.00"),
                "voucherDate", LocalDate.of(2026, 5, 31),
                "assetCategory", "厂房"
        );
        Voucher v = service.createDepreciation("F001", input);

        assertEquals("V-2026-0006", v.getVoucherNumber());
        assertEquals(new BigDecimal("1000.00"), v.getTotalDebit());
    }

    @Test
    void postTransitionsDraftToPosted() {
        Voucher v = Voucher.builder().id("v-1").factoryId("F001").status(VoucherStatus.DRAFT).build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(v));
        when(voucherRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Voucher result = service.post("F001", "v-1", 42L);

        assertEquals(VoucherStatus.POSTED, result.getStatus());
        assertEquals(42L, result.getApprovedBy());
        assertNotNull(result.getApprovedAt());
    }

    @Test
    void postRejectsAlreadyPosted() {
        Voucher v = Voucher.builder().id("v-1").factoryId("F001").status(VoucherStatus.POSTED).build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(v));

        assertThrows(IllegalStateException.class, () -> service.post("F001", "v-1", 42L));
    }

    @Test
    void postRejectsCrossTenant() {
        // 跨租户: F002 调用者过账 F001 的凭证 → findByIdAndFactoryId 命中不到 → EntityNotFoundException, 不写库。
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F002")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.post("F002", "v-1", 42L));
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void voidVoucherRejectsCrossTenant() {
        // 跨租户: F002 调用者作废 F001 的凭证 → findByIdAndFactoryId 命中不到 → EntityNotFoundException, 不写库。
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F002")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.voidVoucher("F002", "v-1", "越权作废", 42L));
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void voidVoucherDraftSetsVoidStatus() {
        // DRAFT 凭证未过账, 直接置 VOID (向后兼容, 无需红字冲销)。
        Voucher v = Voucher.builder().id("v-1").factoryId("F001").status(VoucherStatus.DRAFT).description("foo").build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(v));
        when(voucherRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.voidVoucher("F001", "v-1", "录入错误", 42L);

        assertEquals(VoucherStatus.VOID, v.getStatus());
        assertTrue(v.getDescription().contains("录入错误"));
    }

    @Test
    void voidVoucherRejectsAlreadyVoided() {
        // H-BUG-4 (2026-06-21 transcript-e2e R1): 已作废凭证不可重复作废 (幂等)。
        Voucher v = Voucher.builder().id("v-1").factoryId("F001")
                .status(VoucherStatus.VOID).description("foo [作废: 第一次]").build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(v));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.voidVoucher("F001", "v-1", "第二次", 42L));
        assertEquals(409, ex.getCode());
        assertEquals("VOUCHER_ALREADY_VOID", ex.getErrorCode());
        // 不应再次写库, description 不应被二次追加
        verify(voucherRepo, never()).save(any());
        assertEquals("foo [作废: 第一次]", v.getDescription());
    }

    // ==================== Feature #7 (R12): 红字冲销 ====================

    /** POSTED 凭证作废 → 生成红字冲销凭证 (借贷互换), 原凭证标 REVERSED, 双向关联。 */
    @Test
    void voidPostedVoucherGeneratesRedLetterReversal() {
        Voucher original = Voucher.builder()
                .id("v-orig").factoryId("F001").voucherNumber("V-2026-0007")
                .voucherType(VoucherType.SALES_RECEIPT)
                .voucherDate(LocalDate.of(2026, 6, 1))
                .sourceBusinessType("SALES_ORDER").sourceBusinessId("so-9")
                .status(VoucherStatus.POSTED)
                .totalDebit(new BigDecimal("500.00")).totalCredit(new BigDecimal("500.00"))
                .description("销售订单 SO-9")
                .build();
        // 原分录: 借 应收 500 / 贷 收入 500 (含客户辅助核算)
        original.getEntries().add(com.cretas.aims.entity.finance.VoucherEntry.builder()
                .lineNo(1).subjectCode("1122").subjectName("应收账款")
                .debit(new BigDecimal("500.00")).credit(BigDecimal.ZERO)
                .auxiliaryType(com.cretas.aims.entity.enums.AuxiliaryType.CUSTOMER)
                .auxiliaryEntityId("cust-1").description("销售收入挂账").build());
        original.getEntries().add(com.cretas.aims.entity.finance.VoucherEntry.builder()
                .lineNo(2).subjectCode("6001").subjectName("主营业务收入")
                .debit(BigDecimal.ZERO).credit(new BigDecimal("500.00"))
                .description("销售订单 SO-9").build());

        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-orig", "F001"))
                .thenReturn(Optional.of(original));
        when(voucherRepo.countByFactoryIdAndYear("F001", "2026")).thenReturn(7L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            if (v.getId() == null) v.setId("v-reversal");
            return v;
        });

        service.voidVoucher("F001", "v-orig", "客户退单", 42L);

        // 捕获两次 save: 冲销凭证 + 原凭证
        org.mockito.ArgumentCaptor<Voucher> captor = org.mockito.ArgumentCaptor.forClass(Voucher.class);
        verify(voucherRepo, times(2)).save(captor.capture());
        Voucher reversal = captor.getAllValues().get(0);
        Voucher savedOriginal = captor.getAllValues().get(1);

        // 红字冲销凭证: POSTED, 关联原凭证, 借贷互换
        assertEquals(VoucherStatus.POSTED, reversal.getStatus());
        assertEquals("v-orig", reversal.getOriginalVoucherId());
        assertEquals("V-2026-0008", reversal.getVoucherNumber());
        assertEquals(VoucherType.SALES_RECEIPT, reversal.getVoucherType());
        assertEquals(LocalDate.of(2026, 6, 1), reversal.getVoucherDate(), "冲销凭证沿用原凭证期间");
        assertEquals(2, reversal.getEntries().size());

        // ⚠️ 红字冲销凭证 source = 合成 (VOUCHER_REVERSAL, 原凭证id): 非 null 满足 prod NOT NULL 约束,
        // 且不撞原凭证的 uk_voucher_source_business 唯一约束 (type 不同). 关联另由 originalVoucherId 承载.
        assertEquals("VOUCHER_REVERSAL", reversal.getSourceBusinessType(), "冲销凭证用合成 source type (非 null, 不撞原凭证)");
        assertEquals("v-orig", reversal.getSourceBusinessId(), "冲销凭证 sourceBusinessId = 原凭证 id (唯一, 非 null)");

        // line1: 原借 应收 500 → 冲销贷 应收 500 (方向互换, 金额正数, 辅助核算保留)
        VoucherEntry r1 = reversal.getEntries().get(0);
        assertEquals("1122", r1.getSubjectCode());
        assertEquals(0, BigDecimal.ZERO.compareTo(r1.getDebit()));
        assertEquals(0, new BigDecimal("500.00").compareTo(r1.getCredit()));
        assertEquals(com.cretas.aims.entity.enums.AuxiliaryType.CUSTOMER, r1.getAuxiliaryType());
        assertEquals("cust-1", r1.getAuxiliaryEntityId());
        // line2: 原贷 收入 500 → 冲销借 收入 500
        VoucherEntry r2 = reversal.getEntries().get(1);
        assertEquals("6001", r2.getSubjectCode());
        assertEquals(0, new BigDecimal("500.00").compareTo(r2.getDebit()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r2.getCredit()));

        // 借贷必平: totalDebit == totalCredit == 500
        assertEquals(0, new BigDecimal("500.00").compareTo(reversal.getTotalDebit()));
        assertEquals(0, new BigDecimal("500.00").compareTo(reversal.getTotalCredit()));

        // 原凭证: REVERSED, 反向关联冲销凭证
        assertEquals(VoucherStatus.REVERSED, savedOriginal.getStatus());
        assertEquals("v-reversal", savedOriginal.getReversalVoucherId());
        assertTrue(savedOriginal.getDescription().contains("红字冲销"));
        assertTrue(savedOriginal.getDescription().contains("客户退单"));
    }

    /** POSTED 凭证含销项税 3 行 — 互换后仍借贷平衡。 */
    @Test
    void voidPostedVoucherWithTaxLineBalances() {
        Voucher original = Voucher.builder()
                .id("v-tax").factoryId("F001").voucherNumber("V-2026-0010")
                .voucherType(VoucherType.SALES_RECEIPT)
                .voucherDate(LocalDate.of(2026, 6, 2))
                .sourceBusinessType("SALES_ORDER").sourceBusinessId("so-tax")
                .status(VoucherStatus.POSTED)
                .totalDebit(new BigDecimal("1130.00")).totalCredit(new BigDecimal("1130.00"))
                .build();
        original.getEntries().add(com.cretas.aims.entity.finance.VoucherEntry.builder()
                .lineNo(1).subjectCode("1122").subjectName("应收账款")
                .debit(new BigDecimal("1130.00")).credit(BigDecimal.ZERO).build());
        original.getEntries().add(com.cretas.aims.entity.finance.VoucherEntry.builder()
                .lineNo(2).subjectCode("6001").subjectName("主营业务收入")
                .debit(BigDecimal.ZERO).credit(new BigDecimal("1000.00")).build());
        original.getEntries().add(com.cretas.aims.entity.finance.VoucherEntry.builder()
                .lineNo(3).subjectCode("2221.01").subjectName("应交税费-销项税额")
                .debit(BigDecimal.ZERO).credit(new BigDecimal("130.00")).build());

        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-tax", "F001"))
                .thenReturn(Optional.of(original));
        when(voucherRepo.countByFactoryIdAndYear("F001", "2026")).thenReturn(10L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            if (v.getId() == null) v.setId("v-rev-tax");
            return v;
        });

        // 不抛 UnbalancedVoucherException 即证明 validateBalanced 通过
        assertDoesNotThrow(() -> service.voidVoucher("F001", "v-tax", "开票错误", 1L));

        org.mockito.ArgumentCaptor<Voucher> captor = org.mockito.ArgumentCaptor.forClass(Voucher.class);
        verify(voucherRepo, times(2)).save(captor.capture());
        Voucher reversal = captor.getAllValues().get(0);
        assertEquals(3, reversal.getEntries().size());
        // 互换: 借 1000+130=1130 (原贷两行) / 贷 1130 (原借一行)
        assertEquals(0, new BigDecimal("1130.00").compareTo(reversal.getTotalDebit()));
        assertEquals(0, new BigDecimal("1130.00").compareTo(reversal.getTotalCredit()));
    }

    /** 已 REVERSED 凭证不可再次冲销/作废 (幂等 409)。 */
    @Test
    void voidReversedVoucherRejected() {
        Voucher v = Voucher.builder().id("v-r").factoryId("F001")
                .status(VoucherStatus.REVERSED).reversalVoucherId("v-rev").build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-r", "F001")).thenReturn(Optional.of(v));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.voidVoucher("F001", "v-r", "重复", 1L));
        assertEquals(409, ex.getCode());
        assertEquals("VOUCHER_ALREADY_REVERSED", ex.getErrorCode());
        verify(voucherRepo, never()).save(any());
    }

    /** 红字冲销凭证本身 (original_voucher_id 非 null) 不可被作废/再冲销。 */
    @Test
    void voidReversalVoucherItselfRejected() {
        Voucher reversal = Voucher.builder().id("v-rev").factoryId("F001")
                .status(VoucherStatus.POSTED).originalVoucherId("v-orig").build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-rev", "F001"))
                .thenReturn(Optional.of(reversal));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.voidVoucher("F001", "v-rev", "x", 1L));
        assertEquals(409, ex.getCode());
        assertEquals("VOUCHER_IS_REVERSAL", ex.getErrorCode());
        verify(voucherRepo, never()).save(any());
    }
}
