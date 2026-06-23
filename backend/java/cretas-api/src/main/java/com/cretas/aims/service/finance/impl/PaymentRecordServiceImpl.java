package com.cretas.aims.service.finance.impl;

import com.cretas.aims.entity.finance.PaymentRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.enums.PaymentRecordStatus;
import com.cretas.aims.event.SalesOrderSettledEvent;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.PaymentRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.finance.PaymentRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRecordServiceImpl implements PaymentRecordService {

    private final PaymentRecordRepository paymentRecordRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final CustomerRepository customerRepository;
    private final ArApService arApService;
    private final ApplicationEventPublisher eventPublisher;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    @Override
    @Transactional
    public PaymentRecord recordPayment(String factoryId, String salesOrderId, BigDecimal amount,
                                        PaymentMethod method, LocalDate paymentDate,
                                        String paymentReference, String receiptUrl,
                                        Long recordedBy, String remark) {
        // R23 audit C1: cross-tenant + status validation. Pre-R23 this called findById() with
        // NO factoryId filter and NO status check — F001 user could POST F002's salesOrderId,
        // leaking F002 customer data into F001 payment_records, AND any user could record
        // payment against DRAFT/CANCELLED SO. R21 fixed parallel paths (recordReceivable,
        // recordPayable) but missed this one. Mirror of ArApServiceImpl.validateReceivableStatus.
        SalesOrder so = salesOrderRepository.findById(salesOrderId)
                .filter(s -> factoryId.equals(s.getFactoryId()))
                .orElseThrow(() -> new com.cretas.aims.exception.ResourceNotFoundException(
                        "销售订单不存在: " + salesOrderId));
        if (so.getStatus() == null
                || !com.cretas.aims.domain.OrderUsageWhitelists.SO_INVOICEABLE.contains(so.getStatus())) {
            throw new com.cretas.aims.exception.BusinessException(409,
                    "销售订单状态不允许记录收款 (当前: " + (so.getStatus() != null ? so.getStatus().name() : "null") +
                    "). 仅财务审核通过及之后状态可收款.")
                    .withHint("先完成财务审核流程后再录入收款")
                    .withHintTarget("销售订单");
        }

        // 防呆 R4 (幂等防双击/重试): 60s 窗口内同 工厂/销售单/金额 的待核收款 → 409 + 已有单号。
        // 金额双计入 SO paidAmount 是资金完整性 bug (edge-case 审计 2026-06-24 抓: 双击建 2 条 PENDING)。
        // 窗口取 60s (非 5min): 双击/网络重试是亚秒级, 60s 足够拦截; 同时把"60s 内两笔真实等额分期"
        // 的误拦面缩到极小 (reviewer ISSUE-2)。窗口长度可按业务调整。
        // 注: 这是应用层瞬时去重, 与 TransferService R4 同范式; 极端并发 (同毫秒双请求) 仍可能各过一次,
        // 但不加 DB 唯一约束 — 那会永久禁止合法等额分期 (reviewer ISSUE-1 的修法与其 ISSUE-2 自相矛盾)。
        java.util.List<PaymentRecord> dupes = paymentRecordRepository.findRecentDuplicatePayments(
                factoryId, salesOrderId, amount, java.time.LocalDateTime.now().minusSeconds(60));
        if (!dupes.isEmpty()) {
            PaymentRecord existing = dupes.get(0);
            throw new com.cretas.aims.exception.BusinessException(409, String.format(
                    "1 分钟内已对该订单录入相同金额收款 (%s, ¥%s, 状态 %s), 如确为另一笔请稍候再录",
                    existing.getPaymentNumber(), existing.getAmount(), existing.getStatus()))
                    .withHint("如需查看已有收款记录请打开 " + existing.getPaymentNumber())
                    .withHintTarget(existing.getId());
        }

        PaymentRecord record = new PaymentRecord();
        record.setFactoryId(factoryId);
        record.setPaymentNumber(generatePaymentNumber());
        record.setSalesOrderId(salesOrderId);
        record.setCustomerId(so.getCustomerId());
        record.setCustomerName(so.getCustomerId() != null ?
                customerRepository.findById(so.getCustomerId()).map(c -> c.getName()).orElse(null) : null);
        record.setAmount(amount);
        record.setPaymentMethod(method);
        record.setPaymentDate(paymentDate != null ? paymentDate : LocalDate.now());
        record.setPaymentReference(paymentReference);
        record.setReceiptUrl(receiptUrl);
        record.setStatus(PaymentRecordStatus.PENDING);
        record.setRecordedBy(recordedBy);
        record.setRemark(remark);

        log.info("收款记录创建: orderId={}, amount={}", salesOrderId, amount);
        return paymentRecordRepository.save(record);
    }

    /**
     * Sprint 4 W2 H-EXP-1: 报销付款 (AP). 不 validate SalesOrder, 不查 customer,
     * salesOrderId/customerId/customerName 全 null. remark 加 EXPENSE_REIMBURSEMENT:<id>
     * 前缀作为追溯标记 (后续 verifyPayment 凭此判断走 AP 分支跳过 ArApTransaction).
     */
    @Override
    @Transactional
    public PaymentRecord recordExpenseReimbursement(String factoryId, String expenseRequestId,
                                                    BigDecimal amount, Long recordedBy, String remark) {
        PaymentRecord record = new PaymentRecord();
        record.setFactoryId(factoryId);
        record.setPaymentNumber(generatePaymentNumber());
        // SO/customer fields 留 null (AP, 不关联销售订单)
        record.setAmount(amount);
        record.setPaymentDate(LocalDate.now());
        record.setStatus(PaymentRecordStatus.PENDING);
        record.setRecordedBy(recordedBy);
        record.setRemark("EXPENSE_REIMBURSEMENT:" + expenseRequestId
                + (remark != null && !remark.isBlank() ? " | " + remark : ""));
        log.info("报销付款创建: expenseRequestId={}, amount={}, recordedBy={}",
                expenseRequestId, amount, recordedBy);
        return paymentRecordRepository.save(record);
    }

    /** EXPENSE_REIMBURSEMENT 标记判断 — verifyPayment 凭此跳过 SO/AR 分支. */
    private boolean isExpenseReimbursement(PaymentRecord record) {
        return record.getSalesOrderId() == null
                && record.getRemark() != null
                && record.getRemark().startsWith("EXPENSE_REIMBURSEMENT:");
    }

    @Override
    @Transactional
    public PaymentRecord verifyPayment(String factoryId, String paymentId, Long verifiedBy) {
        PaymentRecord record = getPayment(factoryId, paymentId);
        if (record.getStatus() != PaymentRecordStatus.PENDING) {
            throw new IllegalStateException("只能验证PENDING状态的收款记录");
        }
        if (validationRuleEvaluator != null && record.getFactoryId() != null) {
            try {
                validationRuleEvaluator.validate(record.getFactoryId(), "payment_record", "VERIFY",
                        java.util.Map.of("amount", record.getAmount() != null ? record.getAmount() : java.math.BigDecimal.ZERO));
            } catch (com.cretas.aims.exception.BusinessException e) { throw e; }
            catch (Exception e) { log.warn("Canvas validation non-blocking: {}", e.getMessage()); }
        }

        record.setStatus(PaymentRecordStatus.VERIFIED);
        record.setVerifiedBy(verifiedBy);
        record.setVerifiedAt(LocalDateTime.now());
        PaymentRecord saved = paymentRecordRepository.save(record);

        // Sprint 4 W2 H-EXP-1: 报销付款 (AP) 跳过 AR_PAYMENT + SO 更新分支.
        // remark 'EXPENSE_REIMBURSEMENT:<id>' 标记由 recordExpenseReimbursement 写入.
        // ExpenseRequest.status=PAID 由 finance 团队显式调 /mark-paid 端点设置.
        if (isExpenseReimbursement(saved)) {
            log.info("报销付款已验证 (跳过 AR/SO 联动): paymentId={}, amount={}",
                    paymentId, record.getAmount());
            return saved;
        }

        // 同步创建 ArApTransaction (AR_PAYMENT)
        // Issue #317 fix: thread salesOrderId so SO 收款记录 tab finds the row +
        // SO.paidAmount can be derived. Was orphan-receipt without this.
        arApService.recordArPayment(
                record.getFactoryId(),
                record.getCustomerId(),
                record.getSalesOrderId(),
                record.getAmount(),
                record.getPaymentMethod() != null ? record.getPaymentMethod() : PaymentMethod.BANK_TRANSFER,
                record.getPaymentReference(),
                verifiedBy,
                "收款确认: " + record.getPaymentNumber());

        // 更新销售订单收款状态
        updateSalesOrderPaymentStatus(record.getSalesOrderId(), record.getFactoryId());

        log.info("收款已验证: paymentId={}, amount={}", paymentId, record.getAmount());
        return saved;
    }

    @Override
    @Transactional
    public PaymentRecord rejectPayment(String factoryId, String paymentId, Long verifiedBy, String reason) {
        PaymentRecord record = getPayment(factoryId, paymentId);
        if (record.getStatus() != PaymentRecordStatus.PENDING) {
            throw new IllegalStateException("只能驳回PENDING状态的收款记录");
        }
        record.setStatus(PaymentRecordStatus.REJECTED);
        record.setVerifiedBy(verifiedBy);
        record.setVerifiedAt(LocalDateTime.now());
        record.setRemark(reason);
        return paymentRecordRepository.save(record);
    }

    @Override
    public Page<PaymentRecord> listPayments(String factoryId, PaymentRecordStatus status, Pageable pageable) {
        if (status != null) {
            return paymentRecordRepository.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, status, pageable);
        }
        return paymentRecordRepository.findByFactoryIdAndDeletedAtIsNull(factoryId, pageable);
    }

    @Override
    public PaymentRecord getPayment(String factoryId, String paymentId) {
        PaymentRecord record = paymentRecordRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("收款记录不存在: " + paymentId));
        // 跨租户校验: 收款记录须属于当前工厂 (防止越权查看/确认/驳回其它工厂的收款记录)
        if (record.getFactoryId() == null || !record.getFactoryId().equals(factoryId)) {
            throw new com.cretas.aims.exception.BusinessException(403,
                    "无权操作该收款记录 / 该收款记录不属于当前工厂")
                    .withHint("请确认收款记录 ID 是否属于本工厂");
        }
        return record;
    }

    @Override
    public java.util.List<PaymentRecord> listPaymentsBySalesOrder(String factoryId, String salesOrderId) {
        return paymentRecordRepository
                .findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, salesOrderId);
    }

    private void updateSalesOrderPaymentStatus(String salesOrderId, String factoryId) {
        if (salesOrderId == null) return;
        SalesOrder so = salesOrderRepository.findById(salesOrderId).orElse(null);
        if (so == null) return;

        BigDecimal totalPaid = paymentRecordRepository.sumVerifiedAmountBySalesOrderId(salesOrderId);
        so.setPaidAmount(totalPaid);

        boolean settled = so.getTotalAmount() != null && totalPaid.compareTo(so.getTotalAmount()) >= 0;
        so.setSettlementFlag(settled);
        salesOrderRepository.save(so);

        if (settled) {
            eventPublisher.publishEvent(new SalesOrderSettledEvent(
                    this, factoryId, salesOrderId, totalPaid));
            log.info("销售订单已结清: orderId={}, totalPaid={}", salesOrderId, totalPaid);
        }
    }

    private String generatePaymentNumber() {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = paymentRecordRepository.count() + 1;
        return String.format("PAY-%s-%04d", dateStr, count);
    }
}
