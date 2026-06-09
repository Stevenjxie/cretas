package com.cretas.aims.service;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.impl.SalesFinanceApproveVoucherListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SP5: SalesFinanceApproveVoucherListener 单元测试.
 *
 * <p>验证财审事件监听器的核心行为:
 * <ul>
 *   <li>收到 SalesOrderFinanceApprovedEvent → 将 voucherFlag 更新为 PENDING</li>
 *   <li>若订单不存在 → 静默跳过（不抛异常）</li>
 *   <li>若已是 CREATED → 不重复更新（幂等）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SalesFinanceApproveVoucherListenerTest {

    @Mock
    private SalesOrderRepository salesOrderRepository;

    private SalesFinanceApproveVoucherListener listener;

    private static final String FACTORY_ID = "F006";
    private static final String ORDER_ID = "SO-TEST-001";

    @BeforeEach
    void setUp() {
        listener = new SalesFinanceApproveVoucherListener(salesOrderRepository);
    }

    @Test
    @DisplayName("SP5-VL-01: 财审事件 → voucherFlag 设为 PENDING 并 save")
    void onFinanceApproved_setsVoucherFlagToPending() {
        SalesOrder order = stubOrder(VoucherFlag.UNCREATED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        SalesOrderFinanceApprovedEvent event = new SalesOrderFinanceApprovedEvent(
                this, FACTORY_ID, ORDER_ID, 99L);

        listener.onFinanceApproved(event);

        ArgumentCaptor<SalesOrder> captor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderRepository).save(captor.capture());
        SalesOrder saved = captor.getValue();
        assertThat(saved.getVflag()).isEqualTo(VoucherFlag.PENDING);
    }

    @Test
    @DisplayName("SP5-VL-02: 订单不存在 → 静默跳过，不 save 不抛异常")
    void onFinanceApproved_orderNotFound_silentSkip() {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        SalesOrderFinanceApprovedEvent event = new SalesOrderFinanceApprovedEvent(
                this, FACTORY_ID, ORDER_ID, 99L);

        listener.onFinanceApproved(event);  // 不抛

        verify(salesOrderRepository, never()).save(any());
    }

    @Test
    @DisplayName("SP5-VL-03: 已是 CREATED → 幂等，不更新")
    void onFinanceApproved_alreadyCreated_idempotent() {
        SalesOrder order = stubOrder(VoucherFlag.CREATED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        SalesOrderFinanceApprovedEvent event = new SalesOrderFinanceApprovedEvent(
                this, FACTORY_ID, ORDER_ID, 99L);

        listener.onFinanceApproved(event);

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
