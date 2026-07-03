package com.cretas.aims.service.finance;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.finance.ArApTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface ArApService {

    // ==================== 挂账（应收/应付产生） ====================

    /** 销售出货 → 产生应收（AR_INVOICE） */
    ArApTransaction recordReceivable(String factoryId, String customerId,
                                      String salesOrderId, BigDecimal amount,
                                      LocalDate dueDate, Long operatedBy, String remark);

    /** 采购入库 → 产生应付（AP_INVOICE） */
    ArApTransaction recordPayable(String factoryId, String supplierId,
                                   String purchaseOrderId, BigDecimal amount,
                                   LocalDate dueDate, Long operatedBy, String remark);

    /**
     * 幂等、不抛异常的采购入库应付挂账 — 供与调用方共享事务的自动化调用方使用
     * （如 confirmReceive 对同一 PO 多次分批入库的自动挂账）。
     *
     * <p>与 {@link #recordPayable} 不同，本方法对"预期的跳过条件"<b>绝不抛异常</b>：
     * 在共享 {@code @Transactional} 内抛异常会把事务标记为 rollback-only，调用方外层 commit
     * 会抛 {@code UnexpectedRollbackException}（doomed-tx，客户看到误导性 409）。因此本方法：
     * <ul>
     *   <li>当该 PO 已有 AP_INVOICE 时返回<b>已存在的记录</b>（幂等 — 一个 PO 一条应付，
     *       首次入库时挂账，后续分批入库跳过，不重复挂账，金额不双计）；</li>
     *   <li>当金额缺失/非正、供应商不存在、或采购订单状态不允许挂账时返回 {@code null}（跳过）
     *       —— 收货入库不能被应付侧的前置条件阻塞；</li>
     *   <li>否则创建并返回新的 AP_INVOICE（与 recordPayable 成功路径一致）。</li>
     * </ul>
     *
     * <p>手工挂账入口（ArApController /payable）仍用 {@link #recordPayable}，保留 409 防重复的
     * 防呆提示；本方法仅用于收货自动挂账这类"不得阻塞主流程"的共享事务场景。
     */
    ArApTransaction recordPayableIfAbsent(String factoryId, String supplierId,
                                          String purchaseOrderId, BigDecimal amount,
                                          LocalDate dueDate, Long operatedBy, String remark);

    /** Non-PO payable source, e.g. restaurant supplier delivery note. Idempotent by sourceType/sourceId. */
    ArApTransaction recordPayableFromSource(String factoryId, String supplierId,
                                            String sourceType, String sourceId,
                                            BigDecimal amount, LocalDate dueDate,
                                            Long operatedBy, String remark);

    /**
     * 红冲 (reverse) 一笔"误走采购入库建账"产生的幽灵应付挂账。
     *
     * <p>用于期初建账修正: 客户把期初存货当采购入库录入 → 每个物料挂了一笔供应商应付 (AP_INVOICE),
     * 但客户并不欠供应商钱。本方法对指定的 AP_INVOICE 建一笔<b>反向</b> AP_CREDIT_NOTE
     * (金额 = 原额取负), 把供应商应付余额减回去 —— 净额归零。会计侧的正确期初凭证
     * (借 1403 / 贷 4001) 由调用方 (OpeningInventoryService) 另行过账, 本方法只管应付台账。
     *
     * <p><b>幂等</b>: 以 {@code (sourceType=OPENING_AP_CORRECTION, sourceId=apTransactionId)} 为键 —— 同一笔应付
     * 重复调用返回<b>已有的红冲交易</b>, 不重复冲减余额。返回值的 {@code amount} 为负 (红冲额), 供调用方
     * 取绝对值补记期初凭证。
     *
     * <p>校验: 交易须存在、属于当前工厂、类型为 {@code AP_INVOICE} (供应商挂账); 否则抛业务异常。
     *
     * @return 红冲交易 (AP_CREDIT_NOTE); 若已红冲过则返回既有那笔 (幂等命中)
     */
    ArApTransaction reverseOpeningPayable(String factoryId, String apTransactionId,
                                          String reason, Long operatedBy);

    // ==================== 收付款（冲减应收/应付） ====================

    /**
     * 收到客户付款（AR_PAYMENT）
     *
     * Issue #317 fix: salesOrderId param added so 快速收款 from SO row persists
     * the link. Was orphan-receipt: SO.paidAmount stayed null, SO 收款记录 tab
     * '暂无数据', SO.paymentStatus stayed UNPAID. Pass null when payment is not
     * scoped to a SO (e.g. on-account customer prepayment).
     */
    ArApTransaction recordArPayment(String factoryId, String customerId,
                                     String salesOrderId, BigDecimal amount,
                                     PaymentMethod method, String paymentReference,
                                     Long operatedBy, String remark);

    /** 向供应商付款（AP_PAYMENT） */
    ArApTransaction recordApPayment(String factoryId, String supplierId,
                                     BigDecimal amount, PaymentMethod method,
                                     String paymentReference, Long operatedBy, String remark);

    // ==================== 手工调整（R23 audit C2: PENDING_APPROVAL workflow） ====================

    /**
     * R23 audit C2: creates AR_ADJUSTMENT/AP_ADJUSTMENT in PENDING state.
     * Customer/supplier balance is NOT mutated until approveAdjustment() runs.
     * Pre-R23 this immediately mutated balance — bypass of dual-control gate.
     */
    ArApTransaction recordAdjustment(String factoryId, CounterpartyType counterpartyType,
                                      String counterpartyId, BigDecimal amount,
                                      Long operatedBy, String remark);

    /**
     * R23 audit C2: approve a PENDING adjustment. Applies amount delta to counterparty
     * balance + flips approval_status to APPROVED. Requires elevated permission
     * (controller enforces 'finance:approve_adjustment'). Approver must differ from
     * operatedBy (4-eyes principle).
     */
    ArApTransaction approveAdjustment(String factoryId, String transactionId, Long approvedBy);

    /**
     * R23 audit C2: reject a PENDING adjustment. No balance change; row persists with
     * approval_status=REJECTED + approver + reject reason in remark (appended).
     */
    ArApTransaction rejectAdjustment(String factoryId, String transactionId, Long approvedBy, String reason);

    // ==================== 查询 ====================

    /** 分页查询交易记录 */
    PageResponse<ArApTransaction> getTransactions(String factoryId,
                                                   CounterpartyType counterpartyType,
                                                   String counterpartyId,
                                                   int page, int size);

    /**
     * R28 P2 (R23 P5 deferred): list PENDING adjustments awaiting approval.
     * Filters: factoryId + approval_status='PENDING' + transaction_type IN
     * (AR_ADJUSTMENT, AP_ADJUSTMENT). Sorted newest first (created_at DESC).
     */
    PageResponse<ArApTransaction> getPendingAdjustments(String factoryId, int page, int size);

    /**
     * R29 P1: list PENDING adjustments with filters. All filter params optional;
     * null skips the corresponding clause. counterpartyType filters CUSTOMER vs
     * SUPPLIER, amount range works on absolute amount, date range on createdAt.
     */
    PageResponse<ArApTransaction> getPendingAdjustments(
            String factoryId, CounterpartyType counterpartyType,
            BigDecimal minAmount, BigDecimal maxAmount,
            LocalDate fromDate, LocalDate toDate,
            int page, int size);

    /**
     * R29 P2 batch approve: process up to 100 PENDING adjustment ids, returning
     * per-id outcome. Atomic at the row level (each id is its own @Transactional
     * via approveAdjustment). 4-eyes still enforced — submitter ids are skipped
     * with reason "审批人不能与提交人相同 (4 眼原则)".
     */
    java.util.Map<String, Object> batchApproveAdjustments(
            String factoryId, java.util.List<String> transactionIds, Long approvedBy);

    /**
     * R29 P2 batch reject: same shape as batch approve. Reason applies to all.
     */
    java.util.Map<String, Object> batchRejectAdjustments(
            String factoryId, java.util.List<String> transactionIds,
            Long approvedBy, String reason);

    /** 对账单：指定期间的交易明细 + 期初/期末余额 */
    Map<String, Object> getStatement(String factoryId, CounterpartyType counterpartyType,
                                      String counterpartyId,
                                      LocalDate startDate, LocalDate endDate);

    /** 账龄分析（6桶） */
    List<Map<String, Object>> getAgingAnalysis(String factoryId, CounterpartyType counterpartyType);

    /** 财务概览统计 */
    Map<String, Object> getFinanceOverview(String factoryId);

    /** 信用检查：客户余额是否超过信用额度 */
    boolean checkCreditLimit(String factoryId, String customerId, BigDecimal additionalAmount);
}
