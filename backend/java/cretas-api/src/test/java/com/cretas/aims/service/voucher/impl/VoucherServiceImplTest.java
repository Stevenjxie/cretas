package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.SalesOrder;
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
import jakarta.persistence.EntityNotFoundException;
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
    void voidVoucherSetsVoidStatus() {
        Voucher v = Voucher.builder().id("v-1").factoryId("F001").status(VoucherStatus.POSTED).description("foo").build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(v));
        when(voucherRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.voidVoucher("F001", "v-1", "录入错误", 42L);

        assertEquals(VoucherStatus.VOID, v.getStatus());
        assertTrue(v.getDescription().contains("录入错误"));
    }
}
