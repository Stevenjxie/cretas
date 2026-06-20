package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.PaymentRequestApprovedDTO;
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
     * G2：非预付类（PREPAID）结算方式的 PO 要求全入库（status=COMPLETED）后才可创建。
     * G7：创建时继承 PO.settlementType → PaymentRequest.settlementType。
     */
    PaymentRequest create(
            String factoryId,
            String poId,
            String supplierId,
            BigDecimal amount,
            String paymentMethod,
            Long userId,
            String remark);

    /**
     * #29 创建销售方向付款申请（对客户 outbound 退款/返利/销售费用）。
     *
     * <p>sourceType=SALES，关联 salesOrderId/customerId（XOR：不得同时带 purchaseOrderId/supplierId）。
     * 幂等：同一 salesOrderId 若已有 PENDING/FINANCE_REVIEW/APPROVED 的销售付款申请则抛 BusinessException(409)。
     * customerId 必填（markPaid 时调整 Customer.currentBalance 需要）。salesOrderId 可空（无单退款/费用场景），
     * 但 salesOrderId 与 customerId 至少其一非空。
     *
     * <p>与采购 create() 完全隔离：不查 PO，不做 G2 入库前置，不继承 settlementType。
     */
    PaymentRequest createSalesPayment(
            String factoryId,
            String salesOrderId,
            String customerId,
            BigDecimal amount,
            String paymentMethod,
            Long userId,
            String remark);

    /** 提交初审（PENDING → FINANCE_REVIEW）。factoryId 用于多租户隔离校验。 */
    PaymentRequest submit(String factoryId, String requestId, Long userId);

    /** 财务初审通过（FINANCE_REVIEW → APPROVED）。factoryId 用于多租户隔离校验。 */
    PaymentRequest financeApprove(String factoryId, String requestId, Long userId, String note);

    /** 拒绝申请（任意非终态 → REJECTED）。factoryId 用于多租户隔离校验。 */
    PaymentRequest reject(String factoryId, String requestId, Long userId, String reason);

    /**
     * 标记付款完成（APPROVED → PAID）。按 sourceType 分流（#29）：
     *
     * <p>PURCHASE 三写原子（⛔红线，SP6/D-9 原有，字节不变）：
     * 1. PaymentRequest.status = PAID
     * 2. 保存 ArApTransaction(AP_PAYMENT, SUPPLIER)
     * 3. Supplier.currentBalance -= amount
     *
     * <p>SALES 三写原子（⛔红线，#29 新增）：
     * 1. PaymentRequest.status = PAID
     * 2. 保存 ArApTransaction(AR_CREDIT_NOTE, CUSTOMER)
     * 3. Customer.currentBalance -= amount
     *
     * 全部在单一 @Transactional 中，任一失败全部回滚，禁止 fail-soft。
     */
    PaymentRequest markPaid(String factoryId, String requestId, Long userId, String evidence);

    /**
     * D-9 G1 出纳付款视图（替代裸实体 listApprovedForPayment）。
     * 返回带供应商名/PO单号/原料明细行的结构化 DTO，按 approvedAt ASC。
     * 批量加载 PO items（N+1 安全）。
     */
    List<PaymentRequestApprovedDTO> listApprovedForPaymentWithDetails(String factoryId);

    /**
     * 内部使用：查询某工厂已审批等待付款的裸实体（供其他 Service 使用）。
     * 前端调用请使用 {@link #listApprovedForPaymentWithDetails(String)}。
     */
    List<PaymentRequest> listApprovedForPayment(String factoryId);

    /**
     * 全量列表（web 管理后台），支持 status 过滤和关键词搜索。
     * status 为 null 时返回全部；keyword 模糊匹配供应商名或申请单号。
     *
     * @param factoryId 工厂 ID
     * @param status    可选，PaymentRequestStatus 字符串
     * @param keyword   可选，模糊搜索关键词
     */
    List<PaymentRequest> listByFactory(String factoryId, String status, String keyword);
}
