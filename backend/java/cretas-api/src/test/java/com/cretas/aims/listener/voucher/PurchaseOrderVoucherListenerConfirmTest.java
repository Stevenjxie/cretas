package com.cretas.aims.listener.voucher;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.event.PurchaseReceiveConfirmedEvent;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Bug 4 修复 (2026-07-04): PURCHASE_PAYMENT 凭证生成时点从入库单 DRAFT 创建迁到确认入库。
 * 验证 {@link PurchaseOrderVoucherListener#onPurchaseReceiveConfirmed} 在确认事件下生成一张凭证。
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderVoucherListenerConfirmTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepo;
    @Mock private VoucherService voucherService;

    private PurchaseOrderVoucherListener listener;

    private static final String FACTORY_ID = "F006";
    private static final String PO_ID = "PO-001";

    @BeforeEach
    void setUp() {
        listener = new PurchaseOrderVoucherListener(purchaseOrderRepo, voucherService);
    }

    private PurchaseReceiveConfirmedEvent event(String poId) {
        return new PurchaseReceiveConfirmedEvent(this, FACTORY_ID, "RCV-1", "RCV-20260704-001",
                poId, new BigDecimal("2000.00"));
    }

    @Test
    @DisplayName("确认入库 → 生成一张 PURCHASE_PAYMENT 凭证 + vflag=CREATED")
    void confirmed_generatesOneVoucher() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(PO_ID);
        po.setVflag(VoucherFlag.UNCREATED);
        when(purchaseOrderRepo.findById(PO_ID)).thenReturn(Optional.of(po));
        when(voucherService.createFromBusiness(FACTORY_ID, "PURCHASE_ORDER", PO_ID))
                .thenReturn(Voucher.builder().voucherNumber("V-2026-0001").build());

        listener.onPurchaseReceiveConfirmed(event(PO_ID));

        verify(voucherService).createFromBusiness(FACTORY_ID, "PURCHASE_ORDER", PO_ID);
        assertThat(po.getVflag()).isEqualTo(VoucherFlag.CREATED);
    }

    @Test
    @DisplayName("无 purchaseOrderId → 跳过, 不生成凭证")
    void noPurchaseOrderId_skip() {
        listener.onPurchaseReceiveConfirmed(event(null));
        verify(voucherService, never()).createFromBusiness(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("PO vflag 已非 UNCREATED → 幂等跳过 (同 PO 多次入库只生成一张)")
    void alreadyCreated_idempotentSkip() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(PO_ID);
        po.setVflag(VoucherFlag.CREATED);
        when(purchaseOrderRepo.findById(PO_ID)).thenReturn(Optional.of(po));

        listener.onPurchaseReceiveConfirmed(event(PO_ID));

        verify(voucherService, never()).createFromBusiness(anyString(), anyString(), anyString());
    }
}
