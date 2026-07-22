package com.cretas.aims.service;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.impl.SalesFinanceApproveVoucherListener;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SP5 / #754: SalesFinanceApproveVoucherListener 单元测试.
 *
 * <p>验证财审事件监听器的核心行为 (升级后: 自动生成销售凭证):
 * <ul>
 *   <li>收到事件 → 调 createFromBusiness 生成凭证 + vflag=CREATED</li>
 *   <li>凭证生成失败 → fail-soft, vflag=FAILED, 不抛异常 (不影响财审)</li>
 *   <li>订单不存在 → 静默跳过 (不生成凭证, 不抛)</li>
 *   <li>已是 CREATED/FAILED → 幂等不重复生成</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SalesFinanceApproveVoucherListenerTest {

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private VoucherService voucherService;

    private SalesFinanceApproveVoucherListener listener;

    private static final String FACTORY_ID = "F006";
    private static final String ORDER_ID = "SO-TEST-001";

    @BeforeEach
    void setUp() {
        listener = new SalesFinanceApproveVoucherListener(salesOrderRepository, voucherService);
    }

    @Test
    @DisplayName("SP5-VL-01: 财审事件 → 自动生成销售凭证 + vflag=CREATED")
    void onFinanceApproved_autoGeneratesVoucher_setsFlagCreated() {
        SalesOrder order = stubOrder(VoucherFlag.UNCREATED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        Voucher voucher = Voucher.builder()
                .voucherNumber("V-2026-0001")
                .totalDebit(new BigDecimal("113.00"))
                .build();
        when(voucherService.createFromBusiness(FACTORY_ID, "SALES_ORDER", ORDER_ID))
                .thenReturn(voucher);

        SalesOrderFinanceApprovedEvent event = new SalesOrderFinanceApprovedEvent(
                this, FACTORY_ID, ORDER_ID, 99L);

        listener.onFinanceApproved(event);

        // 凭证被生成 (走正确 businessType)
        verify(voucherService).createFromBusiness(FACTORY_ID, "SALES_ORDER", ORDER_ID);
        // 状态机: PENDING → CREATED (save 两次), 最终态 CREATED.
        // 注: captor 捕获的是同一被 mutate 的对象引用, 最终态即终态 CREATED;
        // 用 save 调用次数 (>=2) 佐证经过 PENDING 中间态.
        verify(salesOrderRepository, atLeast(2)).save(order);
        assertThat(order.getVflag()).isEqualTo(VoucherFlag.CREATED);
    }

    @Test
    @DisplayName("SP5-VL-02: 凭证生成失败 → fail-soft, vflag=FAILED, 不抛异常")
    void onFinanceApproved_voucherFails_failSoft_setsFlagFailed() {
        SalesOrder order = stubOrder(VoucherFlag.UNCREATED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(voucherService.createFromBusiness(eq(FACTORY_ID), eq("SALES_ORDER"), eq(ORDER_ID)))
                .thenThrow(new IllegalStateException("借贷不平衡 (模拟)"));

        SalesOrderFinanceApprovedEvent event = new SalesOrderFinanceApprovedEvent(
                this, FACTORY_ID, ORDER_ID, 99L);

        // fail-soft: 不抛
        listener.onFinanceApproved(event);

        // 状态机: PENDING → FAILED, 最终态 FAILED (save >=2 次)
        verify(salesOrderRepository, atLeast(2)).save(order);
        assertThat(order.getVflag()).isEqualTo(VoucherFlag.FAILED);
    }

    @Test
    @DisplayName("SP5-VL-03: 订单不存在 → 静默跳过, 不生成凭证不抛异常")
    void onFinanceApproved_orderNotFound_silentSkip() {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        SalesOrderFinanceApprovedEvent event = new SalesOrderFinanceApprovedEvent(
                this, FACTORY_ID, ORDER_ID, 99L);

        listener.onFinanceApproved(event);  // 不抛

        verify(voucherService, never()).createFromBusiness(anyString(), anyString(), anyString());
        verify(salesOrderRepository, never()).save(any());
    }

    @Test
    @DisplayName("SP5-VL-04: 已是 CREATED → 幂等, 不重复生成凭证")
    void onFinanceApproved_alreadyCreated_idempotent() {
        SalesOrder order = stubOrder(VoucherFlag.CREATED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        SalesOrderFinanceApprovedEvent event = new SalesOrderFinanceApprovedEvent(
                this, FACTORY_ID, ORDER_ID, 99L);

        listener.onFinanceApproved(event);

        verify(voucherService, never()).createFromBusiness(anyString(), anyString(), anyString());
        verify(salesOrderRepository, never()).save(any());
    }

    @Test
    void finance_approved_event_replay_generates_voucher_only_once() {
        SalesOrder order = stubOrder(VoucherFlag.UNCREATED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(voucherService.createFromBusiness(FACTORY_ID, "SALES_ORDER", ORDER_ID))
                .thenReturn(Voucher.builder().voucherNumber("V-2026-ONCE").build());
        SalesOrderFinanceApprovedEvent event = new SalesOrderFinanceApprovedEvent(
                this, FACTORY_ID, ORDER_ID, 99L);

        listener.onFinanceApproved(event);
        listener.onFinanceApproved(event);

        verify(voucherService, times(1))
                .createFromBusiness(FACTORY_ID, "SALES_ORDER", ORDER_ID);
        assertThat(order.getVflag()).isEqualTo(VoucherFlag.CREATED);
    }

    @Test
    @DisplayName("SP5-VL-05: 已是 FAILED → 幂等, 不重复生成凭证")
    void onFinanceApproved_alreadyFailed_idempotent() {
        SalesOrder order = stubOrder(VoucherFlag.FAILED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        SalesOrderFinanceApprovedEvent event = new SalesOrderFinanceApprovedEvent(
                this, FACTORY_ID, ORDER_ID, 99L);

        listener.onFinanceApproved(event);

        verify(voucherService, never()).createFromBusiness(anyString(), anyString(), anyString());
        verify(salesOrderRepository, never()).save(any());
    }

    private SalesOrder stubOrder(VoucherFlag vflag) {
        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY_ID);
        order.setVflag(vflag);
        return order;
    }
}
