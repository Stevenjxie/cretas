package com.cretas.aims.service.finance.impl;

import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.event.SalesOrderSettledEvent;
import com.cretas.aims.service.finance.InvoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * E-10 / #754: 收款结清 → 开票联动.
 *
 * <p>监听 {@link SalesOrderSettledEvent} (由 {@code PaymentRecordServiceImpl.verifyPayment}
 * 在订单收款全额结清时发布). 行为按配置 {@code cretas.finance.auto-invoice-on-settlement}:
 * <ul>
 *   <li><b>true (自动)</b>: 自动调 {@link InvoiceService#requestInvoiceFromOrder} 创建开票申请
 *       (按税率分组). 该方法内部已幂等 — 同 SO 已有 REQUESTED/APPROVED 发票时抛 409, 被 catch
 *       视为"已有申请", 不重复创建.</li>
 *   <li><b>false (默认/半自动)</b>: 仅记录"该订单已结清, 可开票"提示日志, 由财务手动在
 *       开票 Tab 触发 — 避免在未确认开票信息 (抬头/税号) 时自动生成错误发票.</li>
 * </ul>
 *
 * <p>默认 false: 开票涉及客户抬头/税号/发票类型等财务敏感信息, 默认让财务显式确认更稳;
 * 客户可在 {@code application.properties} 开 {@code cretas.finance.auto-invoice-on-settlement=true}
 * 启用全自动.
 *
 * <p>设计选择 (同 {@link com.cretas.aims.service.impl.SalesFinanceApproveVoucherListener}):
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)}: 结清事务提交后触发, SO.settlementFlag
 *       已落库; 父事务 rollback 时不触发开票.</li>
 *   <li>{@code @Async}: 不阻塞收款确认主流程.</li>
 *   <li>fail-soft: 开票异常被 catch + 告警, <b>绝不抛出</b> (不影响已提交的收款确认;
 *       财务可手动补开票). 409 "已有开票申请" 是幂等正常路径, 仅 debug 日志.</li>
 * </ul>
 *
 * <p>🔒 资金红线: 本监听器只<b>触发</b>开票申请, 不直接动凭证/资金. 发票金额按税率分组
 * 由 {@code requestInvoiceFromOrder} 从 SO 行项聚合 (amount=未税, taxAmount=税额,
 * totalAmount=含税), 与收款金额解耦; 开票不改账, 不破坏借贷平衡.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSettledInvoiceListener {

    /** 默认开票类型 (普票). requestInvoiceFromOrder 内部会按 SO 客户偏好 resolve. */
    private static final String DEFAULT_INVOICE_TYPE = "NORMAL";

    private final InvoiceService invoiceService;

    @Value("${cretas.finance.auto-invoice-on-settlement:false}")
    private boolean autoInvoiceOnSettlement;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSalesOrderSettled(SalesOrderSettledEvent event) {
        String factoryId = event.getFactoryId();
        String salesOrderId = event.getSalesOrderId();

        if (!autoInvoiceOnSettlement) {
            log.info("E-10 PaymentSettledInvoiceListener: 订单 {} 已结清 (factoryId={}, totalPaid={}), "
                    + "自动开票未启用 (cretas.finance.auto-invoice-on-settlement=false) — "
                    + "请财务在开票 Tab 手动申请开票", salesOrderId, factoryId, event.getTotalPaid());
            return;
        }

        // 自动开票 (幂等): requestInvoiceFromOrder 同 SO 已有 REQUESTED/APPROVED 发票时抛 409.
        // fail-soft: 任何异常仅告警, 不抛出 (不影响已提交的收款确认).
        try {
            InvoiceRecord invoice = invoiceService.requestInvoiceFromOrder(
                    factoryId, salesOrderId, DEFAULT_INVOICE_TYPE, null,
                    "收款结清自动开票 (E-10 联动)");
            log.info("E-10 PaymentSettledInvoiceListener: 订单 {} 结清自动开票申请创建 {} "
                    + "(factoryId={}, totalAmount={})",
                    salesOrderId, invoice.getInvoiceNumber(), factoryId, invoice.getTotalAmount());
        } catch (com.cretas.aims.exception.BusinessException e) {
            // 409 已有开票申请 = 幂等正常路径, debug 即可; 其他业务异常 warn.
            if (Integer.valueOf(409).equals(e.getCode())) {
                log.debug("E-10 PaymentSettledInvoiceListener: 订单 {} 已有开票申请, 跳过 (幂等): {}",
                        salesOrderId, e.getMessage());
            } else {
                log.warn("E-10 PaymentSettledInvoiceListener: 订单 {} 自动开票被拒 (不影响收款确认): {}",
                        salesOrderId, e.getMessage());
            }
        } catch (Exception e) {
            log.error("E-10 PaymentSettledInvoiceListener: 订单 {} 自动开票失败 (不影响收款确认; "
                    + "财务可手动开票): {}", salesOrderId, e.getMessage(), e);
        }
    }
}
