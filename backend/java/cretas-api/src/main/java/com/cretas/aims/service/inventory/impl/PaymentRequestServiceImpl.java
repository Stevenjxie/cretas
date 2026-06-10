package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PaymentRequestRepository;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.service.inventory.PaymentRequestService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 付款申请单 Service 实现（SP6 P0）
 *
 * <p>markPaid 红线规则：PaymentRequest.status=PAID + ArApTransaction(AP_PAYMENT) +
 * Supplier.currentBalance 三写必须在单一 {@code @Transactional} 中原子完成，
 * 任一失败全部回滚，⛔禁 fail-soft 吞异常，⛔禁 REQUIRES_NEW（会破坏原子性）。
 *
 * <p>C1 孪生陷阱：角色检查通过 {@code RequestContextHolder} 读取 JwtAuthInterceptor
 * 设置的 "role" request attribute，SecurityContextHolder 在此项目中永远为空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRequestServiceImpl implements PaymentRequestService {

    private final PaymentRequestRepository paymentRequestRepository;
    private final ArApTransactionRepository arApTransactionRepository;
    private final SupplierRepository supplierRepository;

    /** SP12 §5.4: 可选工作流引擎（无配置时降级运行，不抛 NPE）。 */
    @Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    // ─── 活跃状态（幂等性检查用） ────────────────────────────────────────────

    private static final List<PaymentRequestStatus> ACTIVE_STATUSES = List.of(
            PaymentRequestStatus.PENDING,
            PaymentRequestStatus.FINANCE_REVIEW,
            PaymentRequestStatus.APPROVED
    );

    private static final List<PaymentRequestStatus> TERMINAL_STATUSES = List.of(
            PaymentRequestStatus.PAID,
            PaymentRequestStatus.REJECTED,
            PaymentRequestStatus.CANCELLED
    );

    // ─── create ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentRequest create(String factoryId, String poId, String supplierId,
                                 BigDecimal amount, String paymentMethod, Long userId, String remark) {

        // 幂等性检查：同一 PO 已有 PENDING/FINANCE_REVIEW/APPROVED → 409
        List<PaymentRequest> active = paymentRequestRepository.findActiveByPurchaseOrderId(
                poId, ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            throw new BusinessException(409,
                    "采购订单 " + poId + " 已有活跃付款申请单（ID=" + active.get(0).getId() + "）");
        }

        PaymentRequest pr = new PaymentRequest();
        pr.setFactoryId(factoryId);
        pr.setPurchaseOrderId(poId);
        pr.setSupplierId(supplierId);
        pr.setAmount(amount);
        pr.setPaymentMethod(paymentMethod);
        pr.setCreatedBy(userId);
        pr.setRemark(remark);
        pr.setStatus(PaymentRequestStatus.PENDING);
        pr.setRequestNumber(generateRequestNumber(factoryId));

        PaymentRequest saved = paymentRequestRepository.save(pr);
        log.info("[SP6] 创建付款申请单 {} 金额={} 供应商={}", saved.getRequestNumber(), amount, supplierId);
        return saved;
    }

    // ─── submit ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentRequest submit(String requestId, Long userId) {
        PaymentRequest pr = findAndValidate(requestId);
        requireStatus(pr, PaymentRequestStatus.PENDING, "只有 PENDING 状态的申请单可以提交");

        pr.setStatus(PaymentRequestStatus.FINANCE_REVIEW);
        PaymentRequest saved = paymentRequestRepository.save(pr);
        log.info("[SP6] 付款申请单 {} 提交初审 by userId={}", pr.getRequestNumber(), userId);
        return saved;
    }

    // ─── financeApprove ───────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentRequest financeApprove(String requestId, Long userId, String note) {
        PaymentRequest pr = findAndValidate(requestId);
        requireStatus(pr, PaymentRequestStatus.FINANCE_REVIEW, "只有 FINANCE_REVIEW 状态的申请单可以财务审批");

        pr.setStatus(PaymentRequestStatus.APPROVED);
        pr.setFinanceReviewedBy(userId);
        pr.setFinanceReviewedAt(LocalDateTime.now());
        pr.setFinanceReviewNote(note);
        pr.setApprovedBy(userId);
        pr.setApprovedAt(LocalDateTime.now());
        PaymentRequest saved = paymentRequestRepository.save(pr);
        log.info("[SP6] 付款申请单 {} 财务审批通过 by userId={}", pr.getRequestNumber(), userId);
        return saved;
    }

    // ─── reject ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentRequest reject(String requestId, Long userId, String reason) {
        PaymentRequest pr = findAndValidate(requestId);
        if (TERMINAL_STATUSES.contains(pr.getStatus())) {
            throw new BusinessException("付款申请单已处于终态（" + pr.getStatus() + "），无法拒绝");
        }

        pr.setStatus(PaymentRequestStatus.REJECTED);
        pr.setRejectReason(reason);
        PaymentRequest saved = paymentRequestRepository.save(pr);
        log.info("[SP6] 付款申请单 {} 被拒绝 by userId={} reason={}", pr.getRequestNumber(), userId, reason);
        return saved;
    }

    // ─── markPaid 三写原子（⛔红线） ──────────────────────────────────────────

    /**
     * 三写原子事务（单一 @Transactional，⛔禁 fail-soft 吞异常，⛔禁 REQUIRES_NEW）：
     * 1. PaymentRequest.status = PAID + paidBy + paidAt
     * 2. 保存 ArApTransaction(AP_PAYMENT, SUPPLIER, amount, balanceAfter)
     * 3. Supplier.currentBalance -= amount
     *
     * <p>balanceAfter = supplier.currentBalance - amount（付款减少应付账款）。
     * ArApTransaction.balanceAfter NOT NULL，必须在 save 前计算设置。
     */
    @Override
    @Transactional
    public PaymentRequest markPaid(String requestId, Long userId, String evidence) {
        // Step 0: 找申请单
        PaymentRequest pr = findAndValidate(requestId);
        requireStatus(pr, PaymentRequestStatus.APPROVED, "只有 APPROVED 状态的申请单可以标记付款");

        // Step 0b: 找供应商（null-safe 保护，异常直接 doom 整个事务）
        Supplier supplier = supplierRepository.findById(pr.getSupplierId())
                .orElseThrow(() -> new BusinessException(
                        "供应商不存在: " + pr.getSupplierId() + "，无法完成付款"));

        // Step 1: PaymentRequest → PAID
        pr.setStatus(PaymentRequestStatus.PAID);
        pr.setPaidBy(userId);
        pr.setPaidAt(LocalDateTime.now());
        if (evidence != null && !evidence.isBlank()) {
            // 将付款凭证附在 remark（entity 无独立 evidence 字段）
            pr.setRemark((pr.getRemark() == null ? "" : pr.getRemark() + " | ") + "凭证: " + evidence);
        }

        // Step 2: 计算 balanceAfter 并保存 ArApTransaction
        BigDecimal amount = pr.getAmount();
        // null-safe: supplier.currentBalance 为 null 时视为 0（未初始化账户，付款后余额为负 amount）
        BigDecimal currentBalance = supplier.getCurrentBalance() != null
                ? supplier.getCurrentBalance()
                : BigDecimal.ZERO;
        BigDecimal newBalance = currentBalance.subtract(amount);

        ArApTransaction tx = new ArApTransaction();
        tx.setFactoryId(pr.getFactoryId());
        tx.setTransactionNumber(generateTxNumber());
        tx.setTransactionType(ArApTransactionType.AP_PAYMENT);
        tx.setCounterpartyType(CounterpartyType.SUPPLIER);
        tx.setCounterpartyId(pr.getSupplierId());
        tx.setPurchaseOrderId(pr.getPurchaseOrderId());
        tx.setAmount(amount);
        tx.setBalanceAfter(newBalance);                  // ⛔ NOT NULL 必须设置
        tx.setTransactionDate(LocalDate.now());
        tx.setOperatedBy(userId);
        tx.setApprovalStatus(ArApApprovalStatus.APPROVED);
        tx.setRemark("付款申请单 " + pr.getRequestNumber() + " 标记付款");

        ArApTransaction savedTx = arApTransactionRepository.save(tx);

        // Step 3: 关联 ArApTransaction ID 到 PaymentRequest
        pr.setArapTransactionId(savedTx.getId());
        PaymentRequest savedPr = paymentRequestRepository.save(pr);

        // Step 4: 扣减供应商余额（同事务，任一失败全部回滚）
        supplier.setCurrentBalance(newBalance);
        supplierRepository.save(supplier);

        log.info("[SP6] 付款申请单 {} 已标记付款 amount={} supplierBalance: {} → {} tx={}",
                pr.getRequestNumber(), amount, supplier.getCurrentBalance(), newBalance, savedTx.getId());
        return savedPr;
    }

    // ─── listApprovedForPayment ───────────────────────────────────────────────

    @Override
    public List<PaymentRequest> listApprovedForPayment(String factoryId) {
        return paymentRequestRepository.findByFactoryIdAndStatusOrderByApprovedAtAsc(
                factoryId, PaymentRequestStatus.APPROVED);
    }

    // ─── submitForApproval (SP12 §5.4) ───────────────────────────────────────

    /**
     * SP12 §5.4: 提交付款申请单至审批工作流。
     *
     * <p>前置条件：申请单处于 PENDING 状态且 {@code workflowInstanceId == null}。
     * 重复提交（已有 workflowInstanceId）→ 409。状态不对 → 400。
     *
     * @param requestId 申请单 ID
     * @param factoryId 工厂 ID
     * @param userId    提交人 userId
     * @return 工作流实例 ID（引擎不可用时返回 null）
     */
    @Override
    @Transactional
    public String submitForApproval(String requestId, String factoryId, Long userId) {
        PaymentRequest pr = paymentRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(404, "付款申请单不存在: " + requestId));

        // 幂等防重：已在工作流中则 409
        if (pr.getWorkflowInstanceId() != null) {
            throw new BusinessException(409, "付款申请单已提交工作流: " + pr.getWorkflowInstanceId());
        }

        // 前置状态检查：必须是 PENDING
        if (pr.getStatus() != PaymentRequestStatus.PENDING) {
            throw new BusinessException(400, "只有 PENDING 状态的申请单可以提交工作流（当前: " + pr.getStatus() + "）");
        }

        String instanceId = null;
        if (workflowEngine != null) {
            try {
                Map<String, Object> context = Map.of(
                        "requestId", requestId,
                        "factoryId", factoryId,
                        "amount", pr.getAmount().toPlainString(),
                        "supplierId", pr.getSupplierId() != null ? pr.getSupplierId() : ""
                );
                ApprovalWorkflowInstance instance = workflowEngine.startWorkflow(
                        factoryId, "PAYMENT_APPROVAL", requestId, context, userId);
                if (instance != null) {
                    instanceId = instance.getId();
                    pr.setWorkflowInstanceId(instanceId);
                }
            } catch (Exception e) {
                log.warn("启动付款审批工作流失败 (requestId={}), 降级处理: {}", requestId, e.getMessage());
            }
        }

        pr.setSubmittedBy(userId);
        paymentRequestRepository.save(pr);
        log.info("[SP12] 付款申请单 {} 已提交审批: instanceId={}", pr.getRequestNumber(), instanceId);
        return instanceId;
    }

    // ─── 私有辅助 ─────────────────────────────────────────────────────────────

    private PaymentRequest findAndValidate(String requestId) {
        return paymentRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException("付款申请单不存在: " + requestId));
    }

    private void requireStatus(PaymentRequest pr, PaymentRequestStatus expected, String message) {
        if (pr.getStatus() != expected) {
            throw new BusinessException(message + "（当前状态: " + pr.getStatus() + "）");
        }
    }

    private String generateRequestNumber(String factoryId) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long ts = System.currentTimeMillis() % 10000;
        return String.format("PR-%s-%s-%04d", factoryId, date, ts);
    }

    private String generateTxNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long ts = System.currentTimeMillis() % 10000;
        return String.format("AP-%s-%04d", date, ts);
    }
}
