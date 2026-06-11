package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.event.SalesOrderSettledEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.finance.impl.PaymentSettledInvoiceListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E-10 / #754: PaymentSettledInvoiceListener 单元测试.
 *
 * <p>验证收款结清 → 开票联动:
 * <ul>
 *   <li>auto=false (默认) → 仅日志提示, 不触发开票</li>
 *   <li>auto=true → 调 requestInvoiceFromOrder 自动开票</li>
 *   <li>auto=true 但已有开票申请 (409) → 幂等吞掉, 不抛</li>
 *   <li>auto=true 但开票异常 → fail-soft, 不抛 (不影响收款确认)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PaymentSettledInvoiceListenerTest {

    @Mock
    private InvoiceService invoiceService;

    private PaymentSettledInvoiceListener listener;

    private static final String FACTORY_ID = "F006";
    private static final String ORDER_ID = "SO-TEST-001";

    @BeforeEach
    void setUp() {
        listener = new PaymentSettledInvoiceListener(invoiceService);
    }

    private SalesOrderSettledEvent event() {
        return new SalesOrderSettledEvent(this, FACTORY_ID, ORDER_ID, new BigDecimal("113.00"));
    }

    @Test
    @DisplayName("E-10-01: auto=false (默认) → 不触发开票")
    void settled_autoDisabled_noInvoice() {
        ReflectionTestUtils.setField(listener, "autoInvoiceOnSettlement", false);

        listener.onSalesOrderSettled(event());

        verify(invoiceService, never()).requestInvoiceFromOrder(
                anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("E-10-02: auto=true → 自动调 requestInvoiceFromOrder 开票")
    void settled_autoEnabled_triggersInvoice() {
        ReflectionTestUtils.setField(listener, "autoInvoiceOnSettlement", true);
        InvoiceRecord rec = new InvoiceRecord();
        rec.setInvoiceNumber("INV-2026-0001");
        rec.setTotalAmount(new BigDecimal("113.00"));
        when(invoiceService.requestInvoiceFromOrder(eq(FACTORY_ID), eq(ORDER_ID), anyString(), isNull(), anyString()))
                .thenReturn(rec);

        listener.onSalesOrderSettled(event());

        verify(invoiceService).requestInvoiceFromOrder(eq(FACTORY_ID), eq(ORDER_ID), anyString(), isNull(), anyString());
    }

    @Test
    @DisplayName("E-10-03: auto=true 已有开票申请 (409) → 幂等吞掉不抛")
    void settled_duplicateInvoice_idempotentSwallow() {
        ReflectionTestUtils.setField(listener, "autoInvoiceOnSettlement", true);
        when(invoiceService.requestInvoiceFromOrder(anyString(), anyString(), anyString(), isNull(), anyString()))
                .thenThrow(new BusinessException(409, "该销售订单已有待处理开票申请"));

        // 不抛
        listener.onSalesOrderSettled(event());

        verify(invoiceService).requestInvoiceFromOrder(anyString(), anyString(), anyString(), isNull(), anyString());
    }

    @Test
    @DisplayName("E-10-04: auto=true 开票异常 → fail-soft 不抛 (不影响收款确认)")
    void settled_invoiceError_failSoft() {
        ReflectionTestUtils.setField(listener, "autoInvoiceOnSettlement", true);
        when(invoiceService.requestInvoiceFromOrder(anyString(), anyString(), anyString(), isNull(), anyString()))
                .thenThrow(new IllegalStateException("DB 连接超时 (模拟)"));

        // fail-soft: 不抛
        listener.onSalesOrderSettled(event());

        verify(invoiceService).requestInvoiceFromOrder(anyString(), anyString(), anyString(), isNull(), anyString());
    }
}
