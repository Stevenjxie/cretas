package com.cretas.aims.listener.voucher;

import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.event.SalesOrderCancelledEvent;
import com.cretas.aims.event.SalesOrderConfirmedEvent;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Bug 3 修复 (2026-07-04): 销售订单取消 → 作废 SALES_RECEIPT 凭证。
 * 验证 {@link SalesOrderVoucherListener#onSalesOrderCancelled}。
 */
@ExtendWith(MockitoExtension.class)
class SalesOrderVoucherListenerCancelTest {

    @Mock private VoucherService voucherService;

    private SalesOrderVoucherListener listener;

    private static final String FACTORY_ID = "F006";
    private static final String ORDER_ID = "SO-CANCEL-001";

    @BeforeEach
    void setUp() {
        listener = new SalesOrderVoucherListener(voucherService);
    }

    @Test
    @DisplayName("取消订单 → 已存在的 DRAFT 凭证被作废")
    void cancelledOrder_voidsExistingDraftVoucher() {
        Voucher v = Voucher.builder().id("V-1").voucherNumber("V-2026-0001")
                .status(VoucherStatus.DRAFT).build();
        when(voucherService.findBySourceBusiness("SALES_ORDER", ORDER_ID)).thenReturn(Optional.of(v));

        listener.onSalesOrderCancelled(new SalesOrderCancelledEvent(this, FACTORY_ID, ORDER_ID, "客户撤单"));

        verify(voucherService).voidVoucher(eq(FACTORY_ID), eq("V-1"), anyString(), isNull());
    }

    @Test
    @DisplayName("取消订单但无凭证 → 不作废 (no-op)")
    void cancelledOrder_noVoucher_noOp() {
        when(voucherService.findBySourceBusiness("SALES_ORDER", ORDER_ID)).thenReturn(Optional.empty());

        listener.onSalesOrderCancelled(new SalesOrderCancelledEvent(this, FACTORY_ID, ORDER_ID, "客户撤单"));

        verify(voucherService, never()).voidVoucher(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("凭证已是 VOID 终态 → 幂等跳过, 不重复作废")
    void cancelledOrder_alreadyVoid_idempotentSkip() {
        Voucher v = Voucher.builder().id("V-2").voucherNumber("V-2026-0002")
                .status(VoucherStatus.VOID).build();
        when(voucherService.findBySourceBusiness("SALES_ORDER", ORDER_ID)).thenReturn(Optional.of(v));

        listener.onSalesOrderCancelled(new SalesOrderCancelledEvent(this, FACTORY_ID, ORDER_ID, "客户撤单"));

        verify(voucherService, never()).voidVoucher(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("voidVoucher 抛异常 → fail-soft, 不重抛 (不阻塞取消)")
    void cancelledOrder_voidThrows_failSoft() {
        Voucher v = Voucher.builder().id("V-3").voucherNumber("V-2026-0003")
                .status(VoucherStatus.DRAFT).build();
        when(voucherService.findBySourceBusiness("SALES_ORDER", ORDER_ID)).thenReturn(Optional.of(v));
        doThrow(new IllegalStateException("期间已结账")).when(voucherService)
                .voidVoucher(anyString(), anyString(), anyString(), any());

        // 不抛
        listener.onSalesOrderCancelled(new SalesOrderCancelledEvent(this, FACTORY_ID, ORDER_ID, "客户撤单"));

        verify(voucherService).voidVoucher(eq(FACTORY_ID), eq("V-3"), anyString(), isNull());
    }

    @Test
    void confirmed_event_no_longer_generates_sales_voucher() {
        boolean hasConfirmedListener = java.util.Arrays.stream(
                        SalesOrderVoucherListener.class.getDeclaredMethods())
                .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                .anyMatch(SalesOrderConfirmedEvent.class::equals);

        assertFalse(hasConfirmedListener,
                "销售凭证只能在财务审批通过事件后生成，不能在订单确认时提前生成");
    }
}
