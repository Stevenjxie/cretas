package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.PaymentRecord;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.enums.PaymentRecordStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface PaymentRecordService {

    PaymentRecord recordPayment(String factoryId, String salesOrderId, BigDecimal amount,
                                 PaymentMethod method, LocalDate paymentDate,
                                 String paymentReference, String receiptUrl,
                                 Long recordedBy, String remark);

    /**
     * 报销付款 (AP — accounts payable, 出向员工).
     *
     * <p>跟 {@link #recordPayment} 区别:
     * <ul>
     *   <li>无 SalesOrder 关联 (salesOrderId/customerId/customerName 全 null)</li>
     *   <li>remark 自动加 {@code EXPENSE_REIMBURSEMENT:<expenseRequestId>} 前缀作为追溯标记</li>
     *   <li>status=PENDING, 等财务确认转账后调 {@link #verifyPayment}</li>
     * </ul>
     *
     * <p>Sprint 4 W2 H-EXP-1: ExpenseRequest.approve() 触发, paymentRecordId 双向链接.
     *
     * @since 2026-05-17
     */
    PaymentRecord recordExpenseReimbursement(String factoryId, String expenseRequestId,
                                             BigDecimal amount, Long recordedBy, String remark);

    PaymentRecord verifyPayment(String factoryId, String paymentId, Long verifiedBy);

    PaymentRecord rejectPayment(String factoryId, String paymentId, Long verifiedBy, String reason);

    Page<PaymentRecord> listPayments(String factoryId, PaymentRecordStatus status, Pageable pageable);

    /** 按工厂作用域加载收款记录 (跨租户校验: 记录须属于该工厂)。 */
    PaymentRecord getPayment(String factoryId, String paymentId);

    /** List all payment records for a given sales order, factory-scoped. */
    java.util.List<PaymentRecord> listPaymentsBySalesOrder(String factoryId, String salesOrderId);
}
