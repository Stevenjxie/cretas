package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.PaymentRequestApprovedDTO;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.SettlementType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PaymentRequestRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.PaymentRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 付款申请单 Service 实现（SP6 P0）
 *
 * <p>markPaid 红线规则：PaymentRequest.status=PAID + ArApTransaction(AP_PAYMENT) +
 * Supplier.currentBalance 三写必须在单一 {@code @Transactional} 中原子完成，
 * 任一失败全部回滚，⛔禁 fail-soft 吞异常，⛔禁 REQUIRES_NEW（会破坏原子性）。
 *
 * <p>C1 孪生陷阱：角色检查通过 {@code RequestContextHolder} 读取 JwtAuthInterceptor
 * 设置的 "role" request attribute，SecurityContextHolder 在此项目中永远为空。
 *
 * <p>D-9 G2 全入库前置：非预付类（{@link SettlementType#PREPAID}）结算方式的 PO 要求
 * status=COMPLETED 才可创建付款申请（防止货未到先付款）。PREPAID 按定义先付后货，豁免。
 * PO 查不到时跳过检查（降级，不阻塞创建）。
 *
 * <p>D-9 G6：{@code submitForApproval()} 已删除（死代码，无 Controller 端点暴露，
 * X-4 确认付款申请链走硬编码状态机而非 WorkflowEngine）。
 *
 * <p>D-9 G7：{@code create()} 继承 PO.settlementType → PR.settlementType。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRequestServiceImpl implements PaymentRequestService {

    private final PaymentRequestRepository paymentRequestRepository;
    private final ArApTransactionRepository arApTransactionRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;

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

    /**
     * 豁免全入库检查的结算方式集合（D-9 G2）。
     * PREPAID = 预付，按定义先付款后到货，不要求入库完成。
     */
    private static final java.util.Set<SettlementType> PREPAID_EXEMPT = java.util.Set.of(
            SettlementType.PREPAID
    );

    // ─── create ───────────────────────────────────────────────────────────────

    /**
     * D-9 G2 全入库前置检查 + D-9 G7 settlementType 继承。
     *
     * <p>G2 规则：
     * <ol>
     *   <li>查 PO（找不到 → 降级跳过，不阻塞）。</li>
     *   <li>若 PO.settlementType 属于 PREPAID_EXEMPT → 豁免，直接通过。</li>
     *   <li>否则：PO.status 必须是 COMPLETED（全量入库），不满足 → 拒绝，返回具体 hint。</li>
     * </ol>
     *
     * <p>G7 规则：将 PO.settlementType 写入 PR.settlementType（nullable，老数据降级 null）。
     */
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

        // D-9 G7 + G2: 查 PO，继承 settlementType 并做入库前置检查
        SettlementType inheritedSettlementType = null;
        PurchaseOrder po = purchaseOrderRepository.findById(poId).orElse(null);
        if (po != null) {
            // G7: 继承结算方式
            inheritedSettlementType = po.getSettlementType();

            // G2: 全入库前置检查（PREPAID 豁免）
            SettlementType st = po.getSettlementType();
            boolean isExempt = st != null && PREPAID_EXEMPT.contains(st);
            if (!isExempt && po.getStatus() != PurchaseOrderStatus.COMPLETED) {
                // 防呆 4 位一体：具体状态 + 下一步
                String stDisplay = st != null ? st.getDisplayName() : "未设置";
                String hint = "采购订单 " + po.getOrderNumber() + " 结算方式为【" + stDisplay
                        + "】，须在货物全量入库后（订单状态：已完成）才能提付款申请。"
                        + "当前状态：" + po.getStatus().getDisplayName()
                        + "。请确认到货并完成全量入库后，再发起付款申请。";
                throw new BusinessException(422, hint);
            }
        } else {
            log.warn("[D9-G2] 付款申请创建：找不到 PO id={}，跳过入库前置检查（降级）", poId);
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
        // G7: 继承结算方式
        pr.setSettlementType(inheritedSettlementType);

        PaymentRequest saved = paymentRequestRepository.save(pr);
        log.info("[SP6] 创建付款申请单 {} 金额={} 供应商={} 结算方式={}",
                saved.getRequestNumber(), amount, supplierId, inheritedSettlementType);
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

        log.info("[SP6] 付款申请单 {} 已标记付款 amount={} supplierBalance → {} tx={}",
                pr.getRequestNumber(), amount, newBalance, savedTx.getId());
        return savedPr;
    }

    // ─── listApprovedForPaymentWithDetails (D-9 G1) ──────────────────────────

    /**
     * D-9 G1 出纳付款视图。
     *
     * <p>实现步骤（batch 安全，无 N+1）：
     * <ol>
     *   <li>查所有 APPROVED 的 PaymentRequest（按 approvedAt ASC）。</li>
     *   <li>批量查关联 PO（{@code purchaseOrderRepository.findAllById}）。</li>
     *   <li>批量查关联 Supplier（{@code supplierRepository.findAllById}）。</li>
     *   <li>批量查所有 PO items（{@code purchaseOrderItemRepository.findByPurchaseOrderIdIn}）。</li>
     *   <li>在内存中组装 DTO（3 次 DB 查询，O(n) 合并）。</li>
     * </ol>
     */
    @Override
    public List<PaymentRequestApprovedDTO> listApprovedForPaymentWithDetails(String factoryId) {
        List<PaymentRequest> prs = paymentRequestRepository
                .findByFactoryIdAndStatusOrderByApprovedAtAsc(factoryId, PaymentRequestStatus.APPROVED);
        if (prs.isEmpty()) {
            return Collections.emptyList();
        }

        // Batch load POs
        List<String> poIds = prs.stream().map(PaymentRequest::getPurchaseOrderId)
                .filter(id -> id != null).distinct().collect(Collectors.toList());
        Map<String, PurchaseOrder> poMap = purchaseOrderRepository.findAllById(poIds)
                .stream().collect(Collectors.toMap(PurchaseOrder::getId, po -> po));

        // Batch load Suppliers
        List<String> supplierIds = prs.stream().map(PaymentRequest::getSupplierId)
                .filter(id -> id != null).distinct().collect(Collectors.toList());
        Map<String, Supplier> supplierMap = supplierRepository.findAllById(supplierIds)
                .stream().collect(Collectors.toMap(Supplier::getId, s -> s));

        // Batch load PO items
        Map<String, List<PurchaseOrderItem>> itemsByPoId = poIds.isEmpty()
                ? Collections.emptyMap()
                : purchaseOrderItemRepository.findByPurchaseOrderIdIn(poIds)
                        .stream().collect(Collectors.groupingBy(PurchaseOrderItem::getPurchaseOrderId));

        return prs.stream().map(pr -> {
            PurchaseOrder po = poMap.get(pr.getPurchaseOrderId());
            Supplier supplier = supplierMap.get(pr.getSupplierId());
            List<PurchaseOrderItem> items = itemsByPoId.getOrDefault(pr.getPurchaseOrderId(),
                    Collections.emptyList());

            List<PaymentRequestApprovedDTO.ItemLine> itemLines = items.stream()
                    .map(item -> {
                        BigDecimal qty = item.getQuantity();
                        BigDecimal price = item.getUnitPrice();
                        BigDecimal lineAmt = (qty != null && price != null)
                                ? qty.multiply(price) : null;
                        return PaymentRequestApprovedDTO.ItemLine.builder()
                                .itemId(item.getId())
                                .materialName(item.getMaterialName())
                                .quantity(qty)
                                .unit(item.getUnit())
                                .unitPrice(price)
                                .lineAmount(lineAmt)
                                .specification(item.getSpecification())
                                .build();
                    })
                    .collect(Collectors.toList());

            SettlementType st = pr.getSettlementType();
            return PaymentRequestApprovedDTO.builder()
                    .id(pr.getId())
                    .requestNumber(pr.getRequestNumber())
                    .purchaseOrderId(pr.getPurchaseOrderId())
                    .purchaseOrderNumber(po != null ? po.getOrderNumber() : null)
                    .supplierId(pr.getSupplierId())
                    .supplierName(supplier != null ? supplier.getName() : null)
                    .amount(pr.getAmount())
                    .settlementType(st)
                    .settlementTypeDisplayName(st != null ? st.getDisplayName() : null)
                    .paymentMethod(pr.getPaymentMethod())
                    .bankName(pr.getBankName())
                    .bankAccount(pr.getBankAccount())
                    .approvedAt(pr.getApprovedAt())
                    .approvedBy(pr.getApprovedBy())
                    .remark(pr.getRemark())
                    .status(pr.getStatus())
                    .createdBy(pr.getCreatedBy())
                    .createdAt(pr.getCreatedAt())
                    .items(itemLines)
                    .build();
        }).collect(Collectors.toList());
    }

    // ─── listApprovedForPayment (内部使用) ────────────────────────────────────

    @Override
    public List<PaymentRequest> listApprovedForPayment(String factoryId) {
        return paymentRequestRepository.findByFactoryIdAndStatusOrderByApprovedAtAsc(
                factoryId, PaymentRequestStatus.APPROVED);
    }

    // ─── listByFactory (web 管理后台全量列表) ─────────────────────────────────

    /**
     * SP6 Tier1 #36 — 全量付款申请列表，供 web-admin list.vue 使用。
     * status 为 null 时返回全部；keyword 模糊匹配 requestNumber 或 supplierId。
     */
    @Override
    public List<PaymentRequest> listByFactory(String factoryId, String status, String keyword) {
        List<PaymentRequest> list;
        if (status != null && !status.isBlank()) {
            PaymentRequestStatus s = PaymentRequestStatus.valueOf(status.toUpperCase());
            list = paymentRequestRepository.findByFactoryIdAndStatus(factoryId, s);
        } else {
            list = paymentRequestRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.toLowerCase();
            list = list.stream()
                    .filter(pr -> (pr.getRequestNumber() != null
                                    && pr.getRequestNumber().toLowerCase().contains(kw))
                            || (pr.getSupplierId() != null
                                    && pr.getSupplierId().toLowerCase().contains(kw)))
                    .collect(Collectors.toList());
        }
        return list;
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
