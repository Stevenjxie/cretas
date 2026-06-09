package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.PaymentRequest;

import java.math.BigDecimal;
import java.util.List;

/**
 * 付款申请单 Service（SP6 P0）
 *
 * <p>状态流转：PENDING → FINANCE_REVIEW → APPROVED → PAID
 *
 * <p>markPaid 三写原子（红线）：PaymentRequest.status=PAID + ArApTransaction + Supplier.currentBalance
 * 在单一 @Transactional 中完成，任一失败全部回滚，禁止 fail-soft 吞异常。
 */
public interface PaymentRequestService {

    /**
     * 创建付款申请单。
     * 幂等：同一 purchaseOrderId 若已有 PENDING/FINANCE_REVIEW/APPROVED 的申请单则抛 BusinessException(409)。
     */
    PaymentRequest create(
            String factoryId,
            String poId,
            String supplierId,
            BigDecimal amount,
            String paymentMethod,
            Long userId,
            String remark);

    /** 提交初审（PENDING → FINANCE_REVIEW） */
    PaymentRequest submit(String requestId, Long userId);

    /** 财务初审通过（FINANCE_REVIEW → APPROVED） */
    PaymentRequest financeApprove(String requestId, Long userId, String note);

    /** 拒绝申请（任意非终态 → REJECTED） */
    PaymentRequest reject(String requestId, Long userId, String reason);

    /**
     * 标记付款完成（APPROVED → PAID）。
     *
     * <p>三写原子（⛔红线）：
     * 1. PaymentRequest.status = PAID
     * 2. 保存 ArApTransaction(AP_PAYMENT)
     * 3. Supplier.currentBalance -= amount
     *
     * 全部在单一 @Transactional 中，任一失败全部回滚，禁止 fail-soft。
     */
    PaymentRequest markPaid(String requestId, Long userId, String evidence);

    /** 查询某工厂已审批等待付款的申请单（出纳视图） */
    List<PaymentRequest> listApprovedForPayment(String factoryId);
}
