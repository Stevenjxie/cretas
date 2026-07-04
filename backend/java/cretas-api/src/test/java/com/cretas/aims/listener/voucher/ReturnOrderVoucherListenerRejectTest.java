package com.cretas.aims.listener.voucher;

import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.event.ReturnOrderRejectedEvent;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Bug 9 修复 (2026-07-04): 退货单驳回 → 作废 RETURN 凭证。
 * 验证 {@link ReturnOrderVoucherListener#onReturnOrderRejected}。
 */
@ExtendWith(MockitoExtension.class)
class ReturnOrderVoucherListenerRejectTest {

    @Mock private ReturnOrderRepository returnOrderRepo;
    @Mock private VoucherService voucherService;

    private ReturnOrderVoucherListener listener;

    private static final String FACTORY_ID = "F006";
    private static final String RO_ID = "RO-REJECT-001";

    @BeforeEach
    void setUp() {
        listener = new ReturnOrderVoucherListener(returnOrderRepo, voucherService);
    }

    @Test
    @DisplayName("驳回退货单 → 已存在的 RETURN 凭证被作废")
    void rejectedReturn_voidsExistingVoucher() {
        Voucher v = Voucher.builder().id("RV-1").voucherNumber("V-2026-0009")
                .status(VoucherStatus.DRAFT).build();
        when(voucherService.findBySourceBusiness("RETURN_ORDER", RO_ID)).thenReturn(Optional.of(v));

        listener.onReturnOrderRejected(new ReturnOrderRejectedEvent(this, FACTORY_ID, RO_ID));

        verify(voucherService).voidVoucher(eq(FACTORY_ID), eq("RV-1"), anyString(), isNull());
    }

    @Test
    @DisplayName("驳回退货单但无凭证 → 不作废 (no-op)")
    void rejectedReturn_noVoucher_noOp() {
        when(voucherService.findBySourceBusiness("RETURN_ORDER", RO_ID)).thenReturn(Optional.empty());

        listener.onReturnOrderRejected(new ReturnOrderRejectedEvent(this, FACTORY_ID, RO_ID));

        verify(voucherService, never()).voidVoucher(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("凭证已是 REVERSED 终态 → 幂等跳过")
    void rejectedReturn_alreadyReversed_idempotentSkip() {
        Voucher v = Voucher.builder().id("RV-2").voucherNumber("V-2026-0010")
                .status(VoucherStatus.REVERSED).build();
        when(voucherService.findBySourceBusiness("RETURN_ORDER", RO_ID)).thenReturn(Optional.of(v));

        listener.onReturnOrderRejected(new ReturnOrderRejectedEvent(this, FACTORY_ID, RO_ID));

        verify(voucherService, never()).voidVoucher(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("voidVoucher 抛异常 → fail-soft, 不重抛")
    void rejectedReturn_voidThrows_failSoft() {
        Voucher v = Voucher.builder().id("RV-3").voucherNumber("V-2026-0011")
                .status(VoucherStatus.DRAFT).build();
        when(voucherService.findBySourceBusiness("RETURN_ORDER", RO_ID)).thenReturn(Optional.of(v));
        doThrow(new IllegalStateException("boom")).when(voucherService)
                .voidVoucher(anyString(), anyString(), anyString(), any());

        listener.onReturnOrderRejected(new ReturnOrderRejectedEvent(this, FACTORY_ID, RO_ID));

        verify(voucherService).voidVoucher(eq(FACTORY_ID), eq("RV-3"), anyString(), isNull());
    }
}
