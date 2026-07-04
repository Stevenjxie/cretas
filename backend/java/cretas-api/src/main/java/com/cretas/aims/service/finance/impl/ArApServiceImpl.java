package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.ArApService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ArApServiceImpl implements ArApService {

    private static final Logger log = LoggerFactory.getLogger(ArApServiceImpl.class);

    private final ArApTransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;

    /** R20 audit Q1: PO status validation for AP invariant.
     *  R21 audit S2: required=true (was =false fail-open hole). Repo is always present. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.repository.inventory.PurchaseOrderRepository purchaseOrderRepository;

    /** R21: SO repo for AR invariant (R20 audit C1 — recordReceivable was unprotected). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.repository.inventory.SalesOrderRepository salesOrderRepository;

    private void validatePayableStatus(String purchaseOrderId, String factoryId) {
        com.cretas.aims.entity.inventory.PurchaseOrder po = purchaseOrderRepository.findById(purchaseOrderId)
                .filter(p -> factoryId.equals(p.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("采购订单不存在: " + purchaseOrderId));
        if (!isPayableStatusValid(po)) {
            throw new com.cretas.aims.exception.BusinessException(409,
                "采购订单状态不允许应付挂账 (当前: " + (po.getStatus() != null ? po.getStatus().name() : "null") +
                "). 仅财务审核通过及之后状态可挂账.")
                .withHint("先完成财务审核流程后再录入应付")
                .withHintTarget("采购订单");
        }
    }

    /** Non-throwing counterpart of {@link #validatePayableStatus} for the idempotent auto-post path. */
    private boolean isPayableStatusValid(com.cretas.aims.entity.inventory.PurchaseOrder po) {
        return po != null && po.getStatus() != null
                && com.cretas.aims.domain.OrderUsageWhitelists.PO_INVOICEABLE.contains(po.getStatus());
    }

    /** R21 audit C1: recordReceivable was unprotected. Mirror of validatePayableStatus. */
    private void validateReceivableStatus(String salesOrderId, String factoryId) {
        com.cretas.aims.entity.inventory.SalesOrder so = salesOrderRepository.findById(salesOrderId)
                .filter(s -> factoryId.equals(s.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在: " + salesOrderId));
        if (so.getStatus() == null
                || !com.cretas.aims.domain.OrderUsageWhitelists.SO_INVOICEABLE.contains(so.getStatus())) {
            throw new com.cretas.aims.exception.BusinessException(409,
                "销售订单状态不允许应收挂账 (当前: " + (so.getStatus() != null ? so.getStatus().name() : "null") +
                "). 仅财务审核通过及之后状态可挂账.")
                .withHint("先完成财务审核流程后再录入应收")
                .withHintTarget("销售订单");
        }
    }

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /** Spring ApplicationEventPublisher for PaymentReceivedEvent */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    public ArApServiceImpl(ArApTransactionRepository transactionRepository,
                           CustomerRepository customerRepository,
                           SupplierRepository supplierRepository) {
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
    }

    // ==================== 挂账 ====================

    @Override
    @Transactional
    public ArApTransaction recordReceivable(String factoryId, String customerId,
                                             String salesOrderId, BigDecimal amount,
                                             LocalDate dueDate, Long operatedBy, String remark) {
        // Canvas V2: DB-driven validation
        if (validationRuleEvaluator != null) {
            try {
                boolean existingAr = transactionRepository.existsByFactoryIdAndSalesOrderIdAndTransactionType(
                        factoryId, salesOrderId, ArApTransactionType.AR_INVOICE);
                validationRuleEvaluator.validate(factoryId, "finance_ar", "CREATE",
                        Map.of("amount", amount, "existingArForSO", existingAr));
            } catch (BusinessException e) { throw e; }
            catch (Exception e) { log.warn("Canvas validation non-blocking: {}", e.getMessage()); }
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "应收金额必须大于0")
                    .withHint("请输入大于 0 的金额").withHintTarget("amount");
        }

        Customer customer = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("客户不存在"));

        // 检查是否已挂账
        if (salesOrderId != null && transactionRepository.existsByFactoryIdAndSalesOrderIdAndTransactionType(
                factoryId, salesOrderId, ArApTransactionType.AR_INVOICE)) {
            throw new BusinessException(409, "该销售订单已生成应收记录")
                    .withHint("请勿重复挂账, 如需调整请使用收款或调整流程").withHintTarget("salesOrderId");
        }

        // R21 audit C1: enforce SO status invariant (mirror of R18 InvoiceServiceImpl).
        // Without this, direct API POST with DRAFT/CANCELLED salesOrderId silently writes
        // an AR_INVOICE transaction. R18 fixed the parallel InvoiceRecord write path; this
        // closes the ArApTransaction-side gap.
        if (salesOrderId != null) {
            validateReceivableStatus(salesOrderId, factoryId);
        }

        // 更新客户余额
        BigDecimal newBalance = (customer.getCurrentBalance() != null ? customer.getCurrentBalance() : BigDecimal.ZERO)
                .add(amount);
        customer.setCurrentBalance(newBalance);
        customerRepository.save(customer);

        // 创建交易记录
        ArApTransaction transaction = buildTransaction(
                factoryId, ArApTransactionType.AR_INVOICE,
                CounterpartyType.CUSTOMER, customerId, customer.getName(),
                amount, newBalance, dueDate, operatedBy, remark);
        transaction.setSalesOrderId(salesOrderId);

        log.info("应收挂账: factoryId={}, customerId={}, amount={}, balance={}",
                factoryId, customerId, amount, newBalance);
        return transactionRepository.save(transaction);
    }

    @Override
    @Transactional
    public ArApTransaction recordPayable(String factoryId, String supplierId,
                                          String purchaseOrderId, BigDecimal amount,
                                          LocalDate dueDate, Long operatedBy, String remark) {
        if (amount == null) {
            throw new BusinessException(400, "缺少金额，需补录单价/金额")
                    .withHint("请先补录送货单总金额、行金额或单价后再确认挂账")
                    .withHintTarget("amount");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "应付金额必须大于0")
                    .withHint("请输入大于 0 的金额").withHintTarget("amount");
        }

        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("供应商不存在"));

        if (purchaseOrderId != null && transactionRepository.existsByFactoryIdAndPurchaseOrderIdAndTransactionType(
                factoryId, purchaseOrderId, ArApTransactionType.AP_INVOICE)) {
            throw new BusinessException(409, "该采购订单已生成应付记录")
                    .withHint("请勿重复挂账, 如需调整请使用付款或调整流程").withHintTarget("purchaseOrderId");
        }

        // R20 audit Q1: enforce business invariant — payable can only be recorded against
        // a post-finance-approved PO. Mirror of R18's invoice fix (which only covered AR).
        // Without this, direct API POST with DRAFT/CANCELLED purchaseOrderId would bypass.
        if (purchaseOrderId != null) {
            validatePayableStatus(purchaseOrderId, factoryId);
        }

        return persistPayable(factoryId, supplier, supplierId, purchaseOrderId, null, null, amount, dueDate, operatedBy, remark);
    }

    /**
     * 🔒 doomed-tx 修复 (2026-07-02): 采购入库 confirmReceive 对同一 PO 分批入库时的自动挂账。
     *
     * <p>Bug: {@code confirmReceive} 在同一 {@code @Transactional} 内调 {@code recordPayable}，
     * 后者对已挂账的 PO 抛 {@code BusinessException}(409)。因 recordPayable 自身 {@code @Transactional}
     * 已 join 当前事务，抛异常把事务标记 rollback-only —— confirmReceive 的 try/catch 捕获异常也无法挽救，
     * 外层 commit 抛 {@code UnexpectedRollbackException}，被 doomed-tx 兜底网转成误导性通用 409，导致
     * 第 2 次及以后的分批入库<b>永久无法确认</b>（虽然数据一致，干净回滚）。
     *
     * <p>财务语义 (🔴🔒 2026-07-03 修复): 自动挂账用的是<b>本次入库单的实收金额</b>
     * ({@code PurchaseReceiveRecord.totalAmount} = Σ 实收数量 × 单价)，<b>非</b> PO 计划总额。
     * 幂等键 = {@code (sourceType, sourceId)} = 每张入库单 —— 同一入库单重复确认返回既有记录 (防双击)，
     * 不同入库单 (分批) 各挂各的实收值，累计 = 实收总额 (超收/少收据实反映)。旧实现按 PO 计划总额 +
     * 按 PO 幂等 → 分批只挂首笔全额、超收漏挂差额。故本方法幂等跳过，不抛异常。
     * 见 {@link ArApService#recordPayableIfAbsent} javadoc。
     */
    @Override
    @Transactional
    public ArApTransaction recordPayableIfAbsent(String factoryId, String supplierId,
                                                 String purchaseOrderId, String sourceType, String sourceId,
                                                 BigDecimal amount,
                                                 LocalDate dueDate, Long operatedBy, String remark) {
        // 所有跳过条件都是 READ / 校验，绝不写库、绝不抛 —— 保证共享事务不被 doom。

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            // 诚实不挂: 入库单无单价 / 实收金额为 0 时不伪造应付。
            log.warn("自动应付挂账跳过(金额缺失或非正): factoryId={}, purchaseOrderId={}, sourceId={}, amount={}",
                    factoryId, purchaseOrderId, sourceId, amount);
            return null;
        }

        // 幂等: 该入库单 (sourceType, sourceId) 已挂账 → 返回既有记录, 不重复挂账 (防双击/重复确认)。
        if (sourceType != null && sourceId != null) {
            Optional<ArApTransaction> existing =
                    transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                            factoryId, sourceType, sourceId, ArApTransactionType.AP_INVOICE);
            if (existing.isPresent()) {
                log.info("自动应付挂账幂等命中(该入库单已挂账, 跳过): factoryId={}, sourceType={}, sourceId={}, transactionId={}",
                        factoryId, sourceType, sourceId, existing.get().getId());
                return existing.get();
            }
        }

        Optional<Supplier> supplierOpt = supplierRepository.findByIdAndFactoryId(supplierId, factoryId);
        if (supplierOpt.isEmpty()) {
            log.warn("自动应付挂账跳过(供应商不存在): factoryId={}, supplierId={}, purchaseOrderId={}",
                    factoryId, supplierId, purchaseOrderId);
            return null;
        }

        // PO 状态不允许挂账 → 跳过(不抛), 收货入库不能被应付侧前置条件阻塞。
        if (purchaseOrderId != null) {
            com.cretas.aims.entity.inventory.PurchaseOrder po = purchaseOrderRepository.findById(purchaseOrderId)
                    .filter(p -> factoryId.equals(p.getFactoryId()))
                    .orElse(null);
            if (!isPayableStatusValid(po)) {
                log.warn("自动应付挂账跳过(采购订单状态不允许挂账): factoryId={}, purchaseOrderId={}, status={}",
                        factoryId, purchaseOrderId, po != null && po.getStatus() != null ? po.getStatus().name() : "null");
                return null;
            }
        }

        return persistPayable(factoryId, supplierOpt.get(), supplierId, purchaseOrderId,
                sourceType, sourceId, amount, dueDate, operatedBy, remark);
    }

    /** 应付挂账写库尾段: 更新供应商余额 + 建交易记录 + 持久化。recordPayable / recordPayableIfAbsent 共用。 */
    private ArApTransaction persistPayable(String factoryId, Supplier supplier, String supplierId,
                                           String purchaseOrderId, String sourceType, String sourceId,
                                           BigDecimal amount,
                                           LocalDate dueDate, Long operatedBy, String remark) {
        BigDecimal newBalance = (supplier.getCurrentBalance() != null ? supplier.getCurrentBalance() : BigDecimal.ZERO)
                .add(amount);
        supplier.setCurrentBalance(newBalance);
        supplierRepository.save(supplier);

        ArApTransaction transaction = buildTransaction(
                factoryId, ArApTransactionType.AP_INVOICE,
                CounterpartyType.SUPPLIER, supplierId, supplier.getName(),
                amount, newBalance, dueDate, operatedBy, remark);
        transaction.setPurchaseOrderId(purchaseOrderId);
        // per-receive 幂等键 (可空: recordPayable 手工挂账无 source, 走 PO 级 409 防重复)。
        if (sourceType != null) transaction.setSourceType(sourceType);
        if (sourceId != null) transaction.setSourceId(sourceId);

        log.info("应付挂账: factoryId={}, supplierId={}, sourceId={}, amount={}, balance={}",
                factoryId, supplierId, sourceId, amount, newBalance);
        return transactionRepository.save(transaction);
    }

    // ==================== 收付款 ====================

    @Override
    @Transactional
    public ArApTransaction recordPayableFromSource(String factoryId, String supplierId,
                                                   String sourceType, String sourceId,
                                                   BigDecimal amount, LocalDate dueDate,
                                                   Long operatedBy, String remark) {
        if (amount == null) {
            throw new BusinessException(400, "缺少金额，需补录单价/金额")
                    .withHint("请先补录送货单总金额、行金额或单价后再确认挂账")
                    .withHintTarget("amount");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "应付金额必须大于0")
                    .withHint("请补录大于 0 的送货单金额")
                    .withHintTarget("amount");
        }
        if (sourceType == null || sourceType.isBlank() || sourceId == null || sourceId.isBlank()) {
            throw new BusinessException(400, "应付挂账来源不能为空")
                    .withHint("请传入 sourceType/sourceId 以便追溯和防重复")
                    .withHintTarget("sourceId");
        }

        Optional<ArApTransaction> existing =
                transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                        factoryId, sourceType, sourceId, ArApTransactionType.AP_INVOICE);
        if (existing.isPresent()) {
            log.info("应付挂账幂等命中: factoryId={}, sourceType={}, sourceId={}, transactionId={}",
                    factoryId, sourceType, sourceId, existing.get().getId());
            return existing.get();
        }

        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("供应商不存在"));

        BigDecimal newBalance = (supplier.getCurrentBalance() != null ? supplier.getCurrentBalance() : BigDecimal.ZERO)
                .add(amount);
        supplier.setCurrentBalance(newBalance);
        supplierRepository.save(supplier);

        ArApTransaction transaction = buildTransaction(
                factoryId, ArApTransactionType.AP_INVOICE,
                CounterpartyType.SUPPLIER, supplierId, supplier.getName(),
                amount, newBalance, dueDate, operatedBy, remark);
        transaction.setSourceType(sourceType);
        transaction.setSourceId(sourceId);

        ArApTransaction saved = transactionRepository.save(transaction);
        log.info("应付挂账: factoryId={}, supplierId={}, sourceType={}, sourceId={}, amount={}, balance={}",
                factoryId, supplierId, sourceType, sourceId, amount, newBalance);
        return saved;
    }

    /** 期初建账修正: 红冲交易的来源标记 (sourceType), 与被红冲的原应付ID (sourceId) 组成幂等键。 */
    private static final String OPENING_AP_CORRECTION_SOURCE_TYPE = "OPENING_AP_CORRECTION";

    @Override
    @Transactional
    public ArApTransaction reverseOpeningPayable(String factoryId, String apTransactionId,
                                                 String reason, Long operatedBy) {
        if (apTransactionId == null || apTransactionId.isBlank()) {
            throw new BusinessException(400, "待红冲的应付交易ID不能为空").withHintTarget("apTransactionId");
        }

        // 幂等: 该应付已被红冲过 → 返回既有红冲交易, 不重复冲减余额。
        Optional<ArApTransaction> existingReversal =
                transactionRepository.findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                        factoryId, OPENING_AP_CORRECTION_SOURCE_TYPE, apTransactionId, ArApTransactionType.AP_CREDIT_NOTE);
        if (existingReversal.isPresent()) {
            log.info("期初应付红冲幂等命中: factoryId={}, apTransactionId={}, reversalId={}",
                    factoryId, apTransactionId, existingReversal.get().getId());
            return existingReversal.get();
        }

        ArApTransaction original = transactionRepository.findById(apTransactionId)
                .filter(t -> factoryId.equals(t.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("应付交易不存在或不属于当前工厂: " + apTransactionId));

        if (original.getTransactionType() != ArApTransactionType.AP_INVOICE) {
            throw new BusinessException(400,
                    "只能红冲应付挂账 (AP_INVOICE), 当前交易类型: " + original.getTransactionType())
                    .withHint("请确认传入的是采购入库建账产生的应付挂账交易");
        }
        if (original.getCounterpartyType() != CounterpartyType.SUPPLIER) {
            throw new BusinessException(400, "该应付交易对手不是供应商, 无法红冲")
                    .withHint("期初应付修正仅适用于供应商应付挂账");
        }

        BigDecimal originalAmount = original.getAmount() != null ? original.getAmount() : BigDecimal.ZERO;
        if (originalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "该应付挂账金额非正, 无需红冲: " + originalAmount);
        }

        Supplier supplier = supplierRepository.findByIdAndFactoryId(original.getCounterpartyId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("供应商不存在: " + original.getCounterpartyId()));

        // 红冲: 供应商应付余额减回原额 (可能变负, 允许 —— 反映"本不该挂的应付"被冲销)。
        BigDecimal newBalance = (supplier.getCurrentBalance() != null ? supplier.getCurrentBalance() : BigDecimal.ZERO)
                .subtract(originalAmount);
        supplier.setCurrentBalance(newBalance);
        supplierRepository.save(supplier);

        String remark = (reason != null && !reason.isBlank() ? reason : "期初建账修正: 红冲误挂应付")
                + " (红冲原应付 " + original.getTransactionNumber() + "/" + apTransactionId + ")";
        ArApTransaction reversal = buildTransaction(
                factoryId, ArApTransactionType.AP_CREDIT_NOTE,
                CounterpartyType.SUPPLIER, original.getCounterpartyId(), supplier.getName(),
                originalAmount.negate(), newBalance, null, operatedBy, remark);
        reversal.setPurchaseOrderId(original.getPurchaseOrderId());
        reversal.setSourceType(OPENING_AP_CORRECTION_SOURCE_TYPE);
        reversal.setSourceId(apTransactionId);

        ArApTransaction saved = transactionRepository.save(reversal);
        log.info("期初应付红冲: factoryId={}, apTransactionId={}, supplierId={}, amount=-{}, balance={}, reversalId={}",
                factoryId, apTransactionId, original.getCounterpartyId(), originalAmount, newBalance, saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public ArApTransaction recordArPayment(String factoryId, String customerId,
                                            String salesOrderId, BigDecimal amount,
                                            PaymentMethod method, String paymentReference,
                                            Long operatedBy, String remark) {
        // Round 8-α Fix: Canvas validation rules were bypassed on recordArPayment (see R8-α Gap #4).
        // ValidationRuleEvaluator was injected but only used in recordReceivable. Now also fires
        // on payment with {amount, customerId, method} context so rules like "收款 > 100万 require
        // approval" can block here as well as on invoice creation.
        if (validationRuleEvaluator != null) {
            try {
                validationRuleEvaluator.validate(factoryId, "finance_ar", "PAYMENT",
                        Map.of("amount", amount, "customerId", customerId,
                                "method", method != null ? method.name() : "UNKNOWN"));
            } catch (BusinessException e) { throw e; }
            catch (Exception e) { log.warn("Canvas validation non-blocking: {}", e.getMessage()); }
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "收款金额必须大于0")
                    .withHint("请输入大于 0 的金额").withHintTarget("amount");
        }

        // 幂等检查：同一 paymentReference 不能重复收款
        if (paymentReference != null && !paymentReference.isBlank()
                && transactionRepository.existsByFactoryIdAndPaymentReference(factoryId, paymentReference)) {
            throw new BusinessException(409, "收款单号已存在，请勿重复提交: " + paymentReference)
                    .withHint("请使用其他收款单号, 或刷新查看已有记录").withHintTarget("paymentReference");
        }

        // Issue #739: SO-bound 快速收款 idempotency. 防御双击 / 短时间内重复点击 "收款".
        // 5 分钟内同一 SO + 同一金额视为 dupe (业务上极少 5min 内两次同额收款).
        // amount 在表中存负数 (冲减应收), 所以这里 negate 进 query.
        if (salesOrderId != null && !salesOrderId.isBlank()) {
            java.time.LocalDateTime since = java.time.LocalDateTime.now().minusMinutes(5);
            List<ArApTransaction> recent = transactionRepository.findRecentArPaymentsForSO(
                    factoryId, salesOrderId, amount.negate(), since);
            if (!recent.isEmpty()) {
                ArApTransaction prev = recent.get(0);
                log.info("AR 收款幂等命中: factoryId={}, salesOrderId={}, amount={}, prevId={}, prevAt={}",
                        factoryId, salesOrderId, amount, prev.getId(), prev.getCreatedAt());
                throw new BusinessException(409,
                        "5 分钟内已有相同金额(¥" + amount + ")的收款记录")
                        .withHint("请刷新查看现有收款. 如确实需要再次收款, 请填写不同的收款单号 (paymentReference)")
                        .withHintTarget("amount");
            }
        }

        Customer customer = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("客户不存在"));

        // 超额防护：收款金额不能超过客户应收余额
        BigDecimal currentBalance = customer.getCurrentBalance() != null ? customer.getCurrentBalance() : BigDecimal.ZERO;
        if (currentBalance.compareTo(BigDecimal.ZERO) > 0 && amount.compareTo(currentBalance) > 0) {
            throw new BusinessException(409, "收款金额(" + amount + ")超过客户应收余额(" + currentBalance + ")")
                    .withHint("请减少收款金额, 或先核对客户应收余额").withHintTarget("amount");
        }

        // 付款冲减余额（amount为正数，存为负数表示减少应收）
        BigDecimal negAmount = amount.negate();
        BigDecimal newBalance = (customer.getCurrentBalance() != null ? customer.getCurrentBalance() : BigDecimal.ZERO)
                .add(negAmount);
        customer.setCurrentBalance(newBalance);
        customerRepository.save(customer);

        ArApTransaction transaction = buildTransaction(
                factoryId, ArApTransactionType.AR_PAYMENT,
                CounterpartyType.CUSTOMER, customerId, customer.getName(),
                negAmount, newBalance, null, operatedBy, remark);
        transaction.setPaymentMethod(method);
        transaction.setPaymentReference(paymentReference);
        // Issue #317 fix: persist salesOrderId so SO 收款记录 tab finds the row +
        // SO.paidAmount can be derived. null is allowed (on-account prepayment).
        transaction.setSalesOrderId(salesOrderId);

        log.info("应收收款: factoryId={}, customerId={}, salesOrderId={}, amount={}, balance={}",
                factoryId, customerId, salesOrderId, amount, newBalance);
        ArApTransaction saved = transactionRepository.save(transaction);

        // Issue #317 fix: sync SO.paidAmount + settlementFlag so SO.paymentStatus
        // (derived from paidAmount vs totalAmount) reflects the receipt. Skipped
        // when salesOrderId is null (on-account customer prepayment, no SO scope).
        if (salesOrderId != null && !salesOrderId.isBlank()) {
            com.cretas.aims.entity.inventory.SalesOrder so =
                    salesOrderRepository.findById(salesOrderId)
                            .filter(s -> factoryId.equals(s.getFactoryId()))
                            .orElse(null);
            if (so != null) {
                BigDecimal totalPaid = transactionRepository
                        .sumArPaymentsBySalesOrderId(factoryId, salesOrderId);
                so.setPaidAmount(totalPaid != null ? totalPaid : BigDecimal.ZERO);
                boolean settled = so.getTotalAmount() != null
                        && so.getPaidAmount().compareTo(so.getTotalAmount()) >= 0;
                so.setSettlementFlag(settled);
                salesOrderRepository.save(so);
                log.info("销售订单收款状态已更新: salesOrderId={}, totalPaid={}, settled={}",
                        salesOrderId, so.getPaidAmount(), settled);
            } else {
                log.warn("销售订单不存在或不属于该工厂, 跳过 paidAmount 更新: salesOrderId={}, factoryId={}",
                        salesOrderId, factoryId);
            }
        }

        // Publish PaymentReceivedEvent so Canvas trigger chains (factory_trigger_chains
        // with eventType='PaymentReceivedEvent') can react to successful AR payments.
        // Discovered via audit: event had zero publishers despite TriggerChainExecutor listening.
        if (applicationEventPublisher != null) {
            try {
                applicationEventPublisher.publishEvent(
                        new com.cretas.aims.event.PaymentReceivedEvent(
                                this, factoryId, customerId, amount,
                                method != null ? method.name() : null));
                log.info("已发布 PaymentReceivedEvent: factoryId={}, customerId={}, amount={}",
                        factoryId, customerId, amount);
            } catch (Exception e) {
                log.error("发布 PaymentReceivedEvent 失败: {}", e.getMessage(), e);
            }
        }

        // 资金段 GL (finance audit Bug 5): 发布现金流水事件 → AFTER_COMMIT 监听器 fail-soft
        // 生成 收款凭证 (借 1002 银行存款 / 贷 1122 应收账款, 客户辅助核算)。amount 用正数 (子账存负数)。
        publishCashMovement(saved, com.cretas.aims.entity.enums.VoucherType.CASH_RECEIPT,
                customerId, customer.getName(), amount, method, operatedBy);
        return saved;
    }

    /**
     * 资金段 GL (finance audit Bug 5): 发布 {@link com.cretas.aims.event.CashMovementRecordedEvent}。
     * publisher 为 null (测试 / 未装配) 时静默跳过; 发布失败仅告警不影响收付款主流程。
     */
    private void publishCashMovement(ArApTransaction txn, com.cretas.aims.entity.enums.VoucherType type,
                                     String counterpartyId, String counterpartyName, BigDecimal amount,
                                     PaymentMethod method, Long operatedBy) {
        if (applicationEventPublisher == null) {
            return;
        }
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.CashMovementRecordedEvent(
                    this, txn.getFactoryId(), txn.getId(), type,
                    counterpartyId, counterpartyName,
                    amount != null ? amount.abs() : BigDecimal.ZERO, method,
                    txn.getTransactionDate() != null ? txn.getTransactionDate() : LocalDate.now(),
                    operatedBy));
        } catch (Exception e) {
            log.error("发布 CashMovementRecordedEvent 失败 (不影响收付款): txnId={}: {}",
                    txn.getId(), e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public ArApTransaction recordApPayment(String factoryId, String supplierId,
                                            BigDecimal amount, PaymentMethod method,
                                            String paymentReference, Long operatedBy, String remark) {
        // Round 8-α Fix: Canvas validation rules on finance_ap PAYMENT now fire.
        if (validationRuleEvaluator != null) {
            try {
                validationRuleEvaluator.validate(factoryId, "finance_ap", "PAYMENT",
                        Map.of("amount", amount, "supplierId", supplierId,
                                "method", method != null ? method.name() : "UNKNOWN"));
            } catch (BusinessException e) { throw e; }
            catch (Exception e) { log.warn("Canvas validation non-blocking: {}", e.getMessage()); }
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "付款金额必须大于0")
                    .withHint("请输入大于 0 的金额").withHintTarget("amount");
        }

        // 幂等检查：同一 paymentReference 不能重复付款
        if (paymentReference != null && !paymentReference.isBlank()
                && transactionRepository.existsByFactoryIdAndPaymentReference(factoryId, paymentReference)) {
            throw new BusinessException(409, "付款单号已存在，请勿重复提交: " + paymentReference)
                    .withHint("请使用其他付款单号, 或刷新查看已有记录").withHintTarget("paymentReference");
        }

        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("供应商不存在"));

        BigDecimal negAmount = amount.negate();
        BigDecimal newBalance = (supplier.getCurrentBalance() != null ? supplier.getCurrentBalance() : BigDecimal.ZERO)
                .add(negAmount);
        supplier.setCurrentBalance(newBalance);
        supplierRepository.save(supplier);

        ArApTransaction transaction = buildTransaction(
                factoryId, ArApTransactionType.AP_PAYMENT,
                CounterpartyType.SUPPLIER, supplierId, supplier.getName(),
                negAmount, newBalance, null, operatedBy, remark);
        transaction.setPaymentMethod(method);
        transaction.setPaymentReference(paymentReference);

        log.info("应付付款: factoryId={}, supplierId={}, amount={}, balance={}",
                factoryId, supplierId, amount, newBalance);
        ArApTransaction saved = transactionRepository.save(transaction);

        // 资金段 GL (finance audit Bug 5): 发布现金流水事件 → AFTER_COMMIT 监听器 fail-soft
        // 生成 付款凭证 (借 2202 应付账款 / 贷 1002 银行存款, 供应商辅助核算)。amount 用正数 (子账存负数)。
        publishCashMovement(saved, com.cretas.aims.entity.enums.VoucherType.CASH_PAYMENT,
                supplierId, supplier.getName(), amount, method, operatedBy);
        return saved;
    }

    // ==================== 手工调整 ====================

    @Override
    @Transactional
    public ArApTransaction recordAdjustment(String factoryId, CounterpartyType counterpartyType,
                                             String counterpartyId, BigDecimal amount,
                                             Long operatedBy, String remark) {
        return recordAdjustment(factoryId, counterpartyType, counterpartyId, amount,
                operatedBy, remark, null, null);
    }

    @Override
    @Transactional
    public ArApTransaction recordAdjustment(String factoryId, CounterpartyType counterpartyType,
                                             String counterpartyId, BigDecimal amount,
                                             Long operatedBy, String remark,
                                             String sourceType, String sourceId) {
        // R23 audit C2: Pre-R23 this immediately mutated balance and saved APPROVED — bypass
        // of dual-control. R23: insert PENDING + DO NOT mutate balance. Customer/supplier
        // balance changes only on approveAdjustment() by a 2nd user with elevated permission.
        String counterpartyName;
        BigDecimal currentBalance;

        if (counterpartyType == CounterpartyType.CUSTOMER) {
            Customer customer = customerRepository.findByIdAndFactoryId(counterpartyId, factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("客户不存在"));
            counterpartyName = customer.getName();
            currentBalance = customer.getCurrentBalance() != null ? customer.getCurrentBalance() : BigDecimal.ZERO;
        } else {
            Supplier supplier = supplierRepository.findByIdAndFactoryId(counterpartyId, factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("供应商不存在"));
            counterpartyName = supplier.getName();
            currentBalance = supplier.getCurrentBalance() != null ? supplier.getCurrentBalance() : BigDecimal.ZERO;
        }

        ArApTransactionType type = counterpartyType == CounterpartyType.CUSTOMER
                ? ArApTransactionType.AR_ADJUSTMENT : ArApTransactionType.AP_ADJUSTMENT;

        // balance_after captures CURRENT (un-mutated) balance — semantically "snapshot at submit time".
        // On approve, we recompute and update balance_after to reflect the post-application value.
        ArApTransaction transaction = buildTransaction(
                factoryId, type, counterpartyType, counterpartyId, counterpartyName,
                amount, currentBalance, null, operatedBy, remark);
        transaction.setApprovalStatus(com.cretas.aims.entity.enums.ArApApprovalStatus.PENDING);
        // #3(a): link to originating document so a rejected parent (退货单) can cascade-cancel it.
        transaction.setSourceType(sourceType);
        transaction.setSourceId(sourceId);

        log.info("手工调整 (PENDING): factoryId={}, type={}, counterpartyId={}, amount={}, currentBalance={}, operatedBy={}, source={}/{}",
                factoryId, counterpartyType, counterpartyId, amount, currentBalance, operatedBy, sourceType, sourceId);
        return transactionRepository.save(transaction);
    }

    @Override
    @Transactional
    public int cancelPendingAdjustmentsBySource(String factoryId, String sourceType, String sourceId,
                                                Long operatedBy, String reason) {
        if (sourceType == null || sourceType.isBlank() || sourceId == null || sourceId.isBlank()) {
            return 0; // honest no-op — nothing to look up.
        }
        List<ArApTransaction> pending = transactionRepository
                .findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                        factoryId, sourceType, sourceId,
                        com.cretas.aims.entity.enums.ArApApprovalStatus.PENDING);
        int cancelled = 0;
        for (ArApTransaction txn : pending) {
            // Only adjustment types carry PENDING; guard anyway (belt & suspenders).
            if (txn.getTransactionType() != ArApTransactionType.AR_ADJUSTMENT
                    && txn.getTransactionType() != ArApTransactionType.AP_ADJUSTMENT) {
                continue;
            }
            // System cascade cancel — NO 4-eyes (not a manual dual-control reject). Balance untouched.
            // No CANCELLED enum value exists (DB CHECK) → reuse REJECTED, which by definition never
            // mutates balance and persists as historical evidence.
            String existingRemark = txn.getRemark() != null ? txn.getRemark() : "";
            String suffix = " [CANCELLED (来源单据驳回) by " + operatedBy + ": "
                    + (reason != null && !reason.isBlank() ? reason : "(无原因)") + "]";
            String newRemark = existingRemark + suffix;
            if (newRemark.length() > 500) {
                newRemark = newRemark.substring(0, 500);
            }
            txn.setRemark(newRemark);
            txn.setApprovalStatus(com.cretas.aims.entity.enums.ArApApprovalStatus.REJECTED);
            txn.setApprovedBy(operatedBy);
            txn.setApprovedAt(java.time.LocalDateTime.now());
            transactionRepository.save(txn);
            cancelled++;
        }
        if (cancelled > 0) {
            log.info("级联撤销挂起调整: factoryId={}, source={}/{}, cancelled={}, by={}",
                    factoryId, sourceType, sourceId, cancelled, operatedBy);
        }
        return cancelled;
    }

    @Override
    @Transactional
    public ArApTransaction approveAdjustment(String factoryId, String transactionId, Long approvedBy) {
        ArApTransaction txn = transactionRepository.findById(transactionId)
                .filter(t -> factoryId.equals(t.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("调整记录不存在: " + transactionId));

        if (txn.getApprovalStatus() != com.cretas.aims.entity.enums.ArApApprovalStatus.PENDING) {
            // R25 follow-up (reviewer #15 Critical-1, qa-prompt v2.4 Rule 15.b sweep):
            // emit actionHint so FE interceptor differentiates from vanilla optimistic-lock
            // 409 (no actionHint → suppressed). Pre-fix: silent failure on double-approve.
            throw new BusinessException(409, "只能审批 PENDING 状态的调整, 当前: " + txn.getApprovalStatus())
                    .withHint("请刷新调整列表查看最新状态")
                    .withHintTarget("调整记录");
        }
        if (txn.getTransactionType() != ArApTransactionType.AR_ADJUSTMENT
                && txn.getTransactionType() != ArApTransactionType.AP_ADJUSTMENT) {
            throw new BusinessException(409, "非调整类型交易不可审批: " + txn.getTransactionType())
                    .withHint("仅 AR/AP 调整类型交易需要审批, 请刷新交易列表");
        }
        // 4-eyes principle: approver must differ from submitter
        if (txn.getOperatedBy() != null && txn.getOperatedBy().equals(approvedBy)) {
            throw new BusinessException(403, "审批人不能与提交人相同 (4 眼原则)")
                    .withHint("请使用其他账号审批")
                    .withHintTarget("审批人");
        }

        BigDecimal amount = txn.getAmount();
        BigDecimal newBalance;

        if (txn.getCounterpartyType() == CounterpartyType.CUSTOMER) {
            Customer customer = customerRepository.findByIdAndFactoryId(txn.getCounterpartyId(), factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("客户不存在: " + txn.getCounterpartyId()));
            newBalance = (customer.getCurrentBalance() != null ? customer.getCurrentBalance() : BigDecimal.ZERO)
                    .add(amount);
            customer.setCurrentBalance(newBalance);
            customerRepository.save(customer);
        } else {
            Supplier supplier = supplierRepository.findByIdAndFactoryId(txn.getCounterpartyId(), factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("供应商不存在: " + txn.getCounterpartyId()));
            newBalance = (supplier.getCurrentBalance() != null ? supplier.getCurrentBalance() : BigDecimal.ZERO)
                    .add(amount);
            supplier.setCurrentBalance(newBalance);
            supplierRepository.save(supplier);
        }

        txn.setBalanceAfter(newBalance);
        txn.setApprovalStatus(com.cretas.aims.entity.enums.ArApApprovalStatus.APPROVED);
        txn.setApprovedBy(approvedBy);
        txn.setApprovedAt(java.time.LocalDateTime.now());
        log.info("调整审批通过: txnId={}, factoryId={}, approvedBy={}, newBalance={}",
                transactionId, factoryId, approvedBy, newBalance);
        return transactionRepository.save(txn);
    }

    @Override
    @Transactional
    public ArApTransaction rejectAdjustment(String factoryId, String transactionId, Long approvedBy, String reason) {
        ArApTransaction txn = transactionRepository.findById(transactionId)
                .filter(t -> factoryId.equals(t.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("调整记录不存在: " + transactionId));

        if (txn.getApprovalStatus() != com.cretas.aims.entity.enums.ArApApprovalStatus.PENDING) {
            // R25 follow-up (reviewer #15 Critical-1)
            throw new BusinessException(409, "只能驳回 PENDING 状态的调整, 当前: " + txn.getApprovalStatus())
                    .withHint("请刷新调整列表查看最新状态")
                    .withHintTarget("调整记录");
        }
        if (txn.getOperatedBy() != null && txn.getOperatedBy().equals(approvedBy)) {
            throw new BusinessException(403, "审批人不能与提交人相同 (4 眼原则)")
                    .withHint("请使用其他账号驳回")
                    .withHintTarget("审批人");
        }

        // 余额不动. 在 remark 末尾追加驳回原因, 历史可追溯.
        String existingRemark = txn.getRemark() != null ? txn.getRemark() : "";
        String suffix = " [REJECTED by " + approvedBy + ": "
                + (reason != null && !reason.isBlank() ? reason : "(无原因)") + "]";
        // 防止 remark 超长 (DB column length=500)
        String newRemark = existingRemark + suffix;
        if (newRemark.length() > 500) {
            newRemark = newRemark.substring(0, 500);
        }
        txn.setRemark(newRemark);
        txn.setApprovalStatus(com.cretas.aims.entity.enums.ArApApprovalStatus.REJECTED);
        txn.setApprovedBy(approvedBy);
        txn.setApprovedAt(java.time.LocalDateTime.now());
        log.info("调整审批驳回: txnId={}, factoryId={}, rejectedBy={}, reason={}",
                transactionId, factoryId, approvedBy, reason);
        return transactionRepository.save(txn);
    }

    // ==================== 查询 ====================

    @Override
    public PageResponse<ArApTransaction> getTransactions(String factoryId,
                                                          CounterpartyType counterpartyType,
                                                          String counterpartyId,
                                                          int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "transactionDate"));
        Page<ArApTransaction> result;

        if (counterpartyId != null) {
            result = transactionRepository.findByFactoryIdAndCounterpartyTypeAndCounterpartyIdOrderByTransactionDateDesc(
                    factoryId, counterpartyType, counterpartyId, pageRequest);
        } else if (counterpartyType != null) {
            result = transactionRepository.findByFactoryIdAndCounterpartyTypeOrderByTransactionDateDesc(
                    factoryId, counterpartyType, pageRequest);
        } else {
            result = transactionRepository.findByFactoryIdOrderByTransactionDateDesc(factoryId, pageRequest);
        }

        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    /**
     * R28 P2 (R23 P5 deferred): list PENDING adjustments. Inline query over the
     * existing transactionRepository's standard JPA findAll Specification — keeps
     * the API surface narrow without adding a one-off named query.
     */
    @Override
    public PageResponse<ArApTransaction> getPendingAdjustments(String factoryId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ArApTransaction> result = transactionRepository.findPendingAdjustments(factoryId, pageRequest);
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    /**
     * R29 P1: filtered version. counterpartyType pushed to DB (typed @Query);
     * amount + date filters applied in-Java post-fetch (avoids PG parameter-type-
     * inference issue with `:p IS NULL` checks on dynamically-null params).
     * Trade-off: amount/date filters require fetching full PENDING result first
     * — acceptable since adjustment queues are typically small (tens to hundreds).
     */
    @Override
    public PageResponse<ArApTransaction> getPendingAdjustments(
            String factoryId, CounterpartyType counterpartyType,
            BigDecimal minAmount, BigDecimal maxAmount,
            java.time.LocalDate fromDate, java.time.LocalDate toDate,
            int page, int size) {
        boolean noFilters = counterpartyType == null && minAmount == null && maxAmount == null
                && fromDate == null && toDate == null;
        if (noFilters) return getPendingAdjustments(factoryId, page, size);

        // Fetch wider result, then apply Java-level filters
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        PageRequest fullPage = PageRequest.of(0, 1000, sort); // upper bound; pending queues are bounded
        Page<ArApTransaction> raw = counterpartyType != null
                ? transactionRepository.findPendingAdjustmentsByType(factoryId, counterpartyType, fullPage)
                : transactionRepository.findPendingAdjustments(factoryId, fullPage);

        java.time.LocalDateTime fromDateTime = fromDate != null ? fromDate.atStartOfDay() : null;
        java.time.LocalDateTime toDateTime = toDate != null ? toDate.plusDays(1).atStartOfDay() : null;
        java.util.List<ArApTransaction> filtered = raw.getContent().stream()
                .filter(t -> minAmount == null || t.getAmount() != null && t.getAmount().abs().compareTo(minAmount) >= 0)
                .filter(t -> maxAmount == null || t.getAmount() != null && t.getAmount().abs().compareTo(maxAmount) <= 0)
                .filter(t -> fromDateTime == null || t.getCreatedAt() != null && !t.getCreatedAt().isBefore(fromDateTime))
                .filter(t -> toDateTime == null || t.getCreatedAt() != null && t.getCreatedAt().isBefore(toDateTime))
                .collect(java.util.stream.Collectors.toList());

        // In-memory paging
        int total = filtered.size();
        int from = Math.min((page - 1) * size, total);
        int to = Math.min(from + size, total);
        java.util.List<ArApTransaction> pageContent = filtered.subList(from, to);
        return PageResponse.of(pageContent, page, size, (long) total);
    }

    /**
     * R29 P2 batch approve. Per-id error capture — single failure doesn't abort
     * the rest. Each approveAdjustment is its own @Transactional so failures are
     * isolated.
     */
    @Override
    public java.util.Map<String, Object> batchApproveAdjustments(
            String factoryId, java.util.List<String> transactionIds, Long approvedBy) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            throw new BusinessException(400, "批量审批 ID 列表不能为空")
                    .withHint("请选择至少 1 条交易").withHintTarget("ids");
        }
        if (transactionIds.size() > 100) {
            throw new BusinessException(400, "批量审批一次不超过 100 条")
                    .withHint("请分批操作, 每次最多 100 条").withHintTarget("ids");
        }
        int approvedCount = 0;
        java.util.List<java.util.Map<String, String>> failures = new java.util.ArrayList<>();
        for (String id : transactionIds) {
            try {
                approveAdjustment(factoryId, id, approvedBy);
                approvedCount++;
            } catch (RuntimeException e) {
                failures.add(java.util.Map.of("id", id, "reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("requested", transactionIds.size());
        result.put("approvedCount", approvedCount);
        result.put("failedCount", failures.size());
        result.put("failures", failures);
        return result;
    }

    /**
     * R29 P2 batch reject. Same shape.
     */
    @Override
    public java.util.Map<String, Object> batchRejectAdjustments(
            String factoryId, java.util.List<String> transactionIds, Long approvedBy, String reason) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            throw new BusinessException(400, "批量驳回 ID 列表不能为空")
                    .withHint("请选择至少 1 条交易").withHintTarget("ids");
        }
        if (transactionIds.size() > 100) {
            throw new BusinessException(400, "批量驳回一次不超过 100 条")
                    .withHint("请分批操作, 每次最多 100 条").withHintTarget("ids");
        }
        int rejectedCount = 0;
        java.util.List<java.util.Map<String, String>> failures = new java.util.ArrayList<>();
        for (String id : transactionIds) {
            try {
                rejectAdjustment(factoryId, id, approvedBy, reason);
                rejectedCount++;
            } catch (RuntimeException e) {
                failures.add(java.util.Map.of("id", id, "reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("requested", transactionIds.size());
        result.put("rejectedCount", rejectedCount);
        result.put("failedCount", failures.size());
        result.put("failures", failures);
        return result;
    }

    @Override
    public Map<String, Object> getStatement(String factoryId, CounterpartyType counterpartyType,
                                             String counterpartyId,
                                             LocalDate startDate, LocalDate endDate) {
        Map<String, Object> statement = new LinkedHashMap<>();

        // 期初余额：startDate之前最后一笔交易的balanceAfter
        List<BigDecimal> openingBalances = transactionRepository.findOpeningBalance(
                factoryId, counterpartyType, counterpartyId, startDate);
        BigDecimal openingBalance = openingBalances.isEmpty() ? BigDecimal.ZERO : openingBalances.get(0);

        // 本期交易明细
        List<ArApTransaction> transactions = transactionRepository.findByCounterpartyAndDateRange(
                factoryId, counterpartyType, counterpartyId, startDate, endDate);

        // 本期发生额（借方/贷方）
        BigDecimal periodDebit = BigDecimal.ZERO;  // 挂账增加
        BigDecimal periodCredit = BigDecimal.ZERO;  // 付款减少
        for (ArApTransaction t : transactions) {
            if (t.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                periodDebit = periodDebit.add(t.getAmount());
            } else {
                periodCredit = periodCredit.add(t.getAmount().abs());
            }
        }

        BigDecimal closingBalance = openingBalance.add(periodDebit).subtract(periodCredit);

        statement.put("counterpartyId", counterpartyId);
        statement.put("counterpartyType", counterpartyType.name());
        statement.put("startDate", startDate.toString());
        statement.put("endDate", endDate.toString());
        statement.put("openingBalance", openingBalance);
        statement.put("periodDebit", periodDebit);
        statement.put("periodCredit", periodCredit);
        statement.put("closingBalance", closingBalance);
        statement.put("transactions", transactions);
        statement.put("transactionCount", transactions.size());

        return statement;
    }

    @Override
    public List<Map<String, Object>> getAgingAnalysis(String factoryId, CounterpartyType counterpartyType) {
        // 获取所有挂账交易（未完全冲销的）
        List<ArApTransaction> invoices;
        if (counterpartyType == CounterpartyType.CUSTOMER) {
            invoices = transactionRepository.findOverdueReceivables(factoryId, LocalDate.of(9999, 12, 31));
        } else {
            invoices = transactionRepository.findOverduePayables(factoryId, LocalDate.of(9999, 12, 31));
        }

        // 按交易对手分组，计算账龄桶
        Map<String, Map<String, Object>> agingMap = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();

        for (ArApTransaction invoice : invoices) {
            String cpId = invoice.getCounterpartyId();
            agingMap.computeIfAbsent(cpId, k -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("counterpartyId", cpId);
                row.put("counterpartyName", invoice.getCounterpartyName());
                row.put("current", BigDecimal.ZERO);      // 未到期
                row.put("days1_30", BigDecimal.ZERO);
                row.put("days31_60", BigDecimal.ZERO);
                row.put("days61_90", BigDecimal.ZERO);
                row.put("days91_120", BigDecimal.ZERO);
                row.put("days120plus", BigDecimal.ZERO);
                row.put("total", BigDecimal.ZERO);
                return row;
            });

            Map<String, Object> row = agingMap.get(cpId);
            BigDecimal amt = invoice.getAmount();
            String bucket = getBucket(invoice.getDueDate(), today);
            row.put(bucket, ((BigDecimal) row.get(bucket)).add(amt));
            row.put("total", ((BigDecimal) row.get("total")).add(amt));
        }

        return new ArrayList<>(agingMap.values());
    }

    @Override
    public Map<String, Object> getFinanceOverview(String factoryId) {
        Map<String, Object> overview = new LinkedHashMap<>();

        BigDecimal totalReceivable = transactionRepository.sumReceivables(factoryId);
        BigDecimal totalPayable = transactionRepository.sumPayables(factoryId);

        List<ArApTransaction> overdueAR = transactionRepository.findOverdueReceivables(factoryId, LocalDate.now());
        List<ArApTransaction> overdueAP = transactionRepository.findOverduePayables(factoryId, LocalDate.now());

        BigDecimal overdueARAmount = overdueAR.stream()
                .map(ArApTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overdueAPAmount = overdueAP.stream()
                .map(ArApTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        overview.put("totalReceivable", totalReceivable);
        overview.put("totalPayable", totalPayable);
        overview.put("netPosition", totalReceivable.subtract(totalPayable.abs()));
        overview.put("overdueReceivable", overdueARAmount);
        overview.put("overduePayable", overdueAPAmount);
        overview.put("overdueARCount", overdueAR.size());
        overview.put("overdueAPCount", overdueAP.size());

        // 按类型统计
        List<Object[]> stats = transactionRepository.statisticsByType(factoryId);
        List<Map<String, Object>> typeStats = new ArrayList<>();
        for (Object[] row : stats) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("type", ((ArApTransactionType) row[0]).getDisplayName());
            stat.put("count", row[1]);
            stat.put("amount", row[2]);
            typeStats.add(stat);
        }
        overview.put("transactionsByType", typeStats);

        return overview;
    }

    @Override
    public boolean checkCreditLimit(String factoryId, String customerId, BigDecimal additionalAmount) {
        Customer customer = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("客户不存在"));

        if (customer.getCreditLimit() == null) {
            return true; // 无信用额度限制
        }

        BigDecimal currentBalance = customer.getCurrentBalance() != null ? customer.getCurrentBalance() : BigDecimal.ZERO;
        BigDecimal afterBalance = currentBalance.add(additionalAmount);
        return afterBalance.compareTo(customer.getCreditLimit()) <= 0;
    }

    // ==================== 内部方法 ====================

    private ArApTransaction buildTransaction(String factoryId, ArApTransactionType type,
                                              CounterpartyType counterpartyType, String counterpartyId,
                                              String counterpartyName, BigDecimal amount,
                                              BigDecimal balanceAfter, LocalDate dueDate,
                                              Long operatedBy, String remark) {
        ArApTransaction transaction = new ArApTransaction();
        transaction.setFactoryId(factoryId);
        transaction.setTransactionNumber(generateTransactionNumber(type));
        transaction.setTransactionType(type);
        transaction.setCounterpartyType(counterpartyType);
        transaction.setCounterpartyId(counterpartyId);
        transaction.setCounterpartyName(counterpartyName);
        transaction.setAmount(amount);
        transaction.setBalanceAfter(balanceAfter);
        transaction.setTransactionDate(LocalDate.now());
        transaction.setDueDate(dueDate);
        transaction.setOperatedBy(operatedBy);
        transaction.setRemark(remark);
        return transaction;
    }

    private String generateTransactionNumber(ArApTransactionType type) {
        String prefix = type.isAR() ? "AR" : "AP";
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long ts = System.currentTimeMillis() % 10000;
        return String.format("%s-%s-%04d", prefix, dateStr, ts);
    }

    private String getBucket(LocalDate dueDate, LocalDate today) {
        if (dueDate == null || !dueDate.isBefore(today)) {
            return "current";
        }
        long daysOverdue = ChronoUnit.DAYS.between(dueDate, today);
        if (daysOverdue <= 30) return "days1_30";
        if (daysOverdue <= 60) return "days31_60";
        if (daysOverdue <= 90) return "days61_90";
        if (daysOverdue <= 120) return "days91_120";
        return "days120plus";
    }
}
