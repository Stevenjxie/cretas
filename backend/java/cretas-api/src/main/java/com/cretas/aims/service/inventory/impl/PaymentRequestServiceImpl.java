package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.PaymentRequestApprovedDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.enums.PaymentSourceType;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.SettlementType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PaymentRequestRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>D-9 G6（已被六扇门 #30 取代）：曾删除独立的 {@code submitForApproval()} 死代码并让
 * 付款链走硬编码状态机。#30 重新接入 WorkflowEngine，但接入点改在 {@code financeApprove}
 * （有 Controller 端点 {@code PUT /{requestId}/finance-approve} 暴露，非 SP12 的孤立死代码），
 * 并补齐 SP12 缺失的「终态推进 + PR 状态翻译」，使工作流真正驱动 PR 状态。
 *
 * <p>D-9 G7：{@code create()} 继承 PO.settlementType → PR.settlementType。
 *
 * <p><b>六扇门 #30 可配置审批流接入（替代钉钉）</b>：{@code financeApprove()} 现在优先走
 * Canvas-configured {@link WorkflowEngineService}（moduleCode={@code PURCHASE_PAYMENT}），
 * 镜像 {@code PurchaseServiceImpl.approveOrder} 已验证的接入模式：
 * <ul>
 *   <li>无 workflow 引擎 bean / factory 无 active PURCHASE_PAYMENT workflow → 走 legacy 硬编码
 *       状态机（直接 PR → APPROVED），保持 F001 等未配置工厂向后兼容。</li>
 *   <li>有 active workflow → start / resume 工作流，按终态翻译 PR 状态：
 *       <ul>
 *         <li>{@code InstanceStatus.APPROVED} → {@code APPROVED}（解锁 markPaid）</li>
 *         <li>{@code InstanceStatus.RUNNING} → 保留 {@code FINANCE_REVIEW}（多级审批中，
 *             等待下一审批人；记录 workflowInstanceId）</li>
 *         <li>{@code InstanceStatus.REJECTED} → {@code REJECTED}</li>
 *         <li>{@code CANCELLED / TIMEOUT} → 回 {@code FINANCE_REVIEW} 等人工重审</li>
 *       </ul></li>
 * </ul>
 *
 * <p><b>⛔ 资金红线不变</b>：{@code markPaid} 仍要求 PR 处于 {@code APPROVED}。工作流 RUNNING
 * 时 PR 停在 FINANCE_REVIEW → 无法付款；工作流 REJECTED → PR REJECTED → 无法付款。
 * 「审批通过才能付款」语义在 legacy 与 workflow 两条路径下都成立。
 *
 * <p>Phase 1 hotfix 模式（2026-05-18 PurchaseService 踩过）：用 {@code hasActiveWorkflow}
 * 预检避免 {@code startWorkflow} 的 orElseThrow 触发 Spring "rollback-only" 事务陷阱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRequestServiceImpl implements PaymentRequestService {

    /** 六扇门 #30：付款审批工作流 moduleCode（DecisionTypeMetadataRegistry → PURCHASE_PAYMENT_APPROVAL）。 */
    private static final String WORKFLOW_MODULE_CODE = "PURCHASE_PAYMENT";

    private final PaymentRequestRepository paymentRequestRepository;
    private final ArApTransactionRepository arApTransactionRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final CustomerRepository customerRepository;

    /**
     * 六扇门 #30：可选工作流引擎。
     * {@code required = false} → 无配置工厂降级走硬编码状态机，不抛 NPE。
     */
    @Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    /**
     * 🔒🔒 资金段 GL 桥 (finance audit BUG 2, 2026-07-06)：现金流水事件发布器。
     *
     * <p>#1227 建的现金 GL 桥 ({@code CashMovementVoucherListener} 监听
     * {@link com.cretas.aims.event.CashMovementRecordedEvent}) 原本只挂在
     * {@code ArApServiceImpl.recordApPayment}。#1262 把所有付款 UI 导流到
     * {@code markPaidPurchase} —— 该路径写 AP_PAYMENT 子账但不发现金事件，导致每笔采购付款
     * 都不记 借2202应付/贷1002现金 的 GL 凭证 (资产负债表现金虚高 + 应付虚高、现金流量表漏付款流出)。
     * 本字段让 {@code markPaidPurchase} 补发 CASH_PAYMENT 事件，镜像 recordApPayment 口径。
     *
     * <p>{@code required = false} → 测试 / 未装配时为 null，静默跳过 (与 recordApPayment 一致，
     * 现金事件发布失败绝不影响付款主流程)。
     */
    @Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

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

        // D-BUG4 (2026-06-21 transcript-e2e R1): controller 用 raw Map getBigDecimal(body,"amount"),
        // 缺失/畸形时返回 null。之前直接 pr.setAmount(null) → DB NOT NULL 约束 → 500。
        // 与 createSalesPayment 一致, 在 service 层显式守卫给友好 422 (单一真源, 也覆盖 AI tool 直调)。
        if (supplierId == null || supplierId.isBlank()) {
            throw new BusinessException(422, "付款申请必须指定供应商（supplierId）。")
                    .withHint("请选择供应商后再提交付款申请");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(422, "付款金额必须大于 0。")
                    .withHint("请填写有效的付款金额");
        }

        // 幂等性检查：同一 PO 已有 PENDING/FINANCE_REVIEW/APPROVED → 409
        List<PaymentRequest> active = paymentRequestRepository.findActiveByPurchaseOrderId(
                poId, ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            throw new BusinessException(409,
                    "采购订单 " + poId + " 已有活跃付款申请单（ID=" + active.get(0).getId() + "）");
        }

        // D-9 G7 + G2: 查 PO，继承 settlementType 并做入库前置检查
        // 跨租户校验: 用 findByIdAndFactoryId 加载 PO (防止读取/继承别厂 PO 的结算方式/状态)。
        // 别厂 PO → null → 走下方降级分支 (不读别厂数据)。
        SettlementType inheritedSettlementType = null;
        PurchaseOrder po = purchaseOrderRepository.findByIdAndFactoryId(poId, factoryId).orElse(null);
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
        pr.setSourceType(PaymentSourceType.PURCHASE);   // #29: 采购方向，显式标注
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

    // ─── createSalesPayment (#29 销售方向 outbound 付款) ──────────────────────────

    /**
     * #29 创建销售方向付款申请（对客户 outbound 退款/返利/销售费用）。
     *
     * <p>XOR 约束：sourceType=SALES，只填 salesOrderId/customerId，绝不带 PO/supplier。
     * customerId 必填（markPaid 调整 Customer.currentBalance 需要）。salesOrderId 可空（无单退款/费用）。
     * 幂等：同一 salesOrderId（非空）若已有活跃 SALES 申请 → 409。
     *
     * <p>⛔ 与采购 create() 完全隔离：不查 PO、不做 G2 入库前置、不继承 settlementType。
     */
    @Override
    @Transactional
    public PaymentRequest createSalesPayment(String factoryId, String salesOrderId, String customerId,
                                             BigDecimal amount, String paymentMethod, Long userId, String remark) {

        // XOR 校验：客户必填（balance 调整必需）
        if (customerId == null || customerId.isBlank()) {
            throw new BusinessException(422,
                    "销售方向付款申请必须指定客户（customerId），以便记账冲减客户应收余额。");
        }
        // 金额校验（与采购一致由 @NotNull 列约束兜底，这里给友好 hint）
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(422, "付款金额必须大于 0。");
        }

        // 幂等性检查：同一销售订单已有活跃 SALES 付款申请 → 409（salesOrderId 为空时跳过，无单退款不幂等去重）
        if (salesOrderId != null && !salesOrderId.isBlank()) {
            List<PaymentRequest> active = paymentRequestRepository.findActiveSalesByOrderId(
                    salesOrderId, ACTIVE_STATUSES);
            if (!active.isEmpty()) {
                throw new BusinessException(409,
                        "销售订单 " + salesOrderId + " 已有活跃销售付款申请单（ID=" + active.get(0).getId()
                                + "，状态=" + active.get(0).getStatus() + "）");
            }
        }

        PaymentRequest pr = new PaymentRequest();
        pr.setFactoryId(factoryId);
        pr.setSourceType(PaymentSourceType.SALES);      // #29: 销售方向
        pr.setSalesOrderId((salesOrderId != null && salesOrderId.isBlank()) ? null : salesOrderId);
        pr.setCustomerId(customerId);
        // ⛔ purchaseOrderId / supplierId / settlementType 一律留 null（XOR）
        pr.setAmount(amount);
        pr.setPaymentMethod(paymentMethod);
        pr.setCreatedBy(userId);
        pr.setRemark(remark);
        pr.setStatus(PaymentRequestStatus.PENDING);
        pr.setRequestNumber(generateRequestNumber(factoryId));

        PaymentRequest saved = paymentRequestRepository.save(pr);
        log.info("[#29] 创建销售付款申请单 {} 金额={} 客户={} 销售订单={}",
                saved.getRequestNumber(), amount, customerId, salesOrderId);
        return saved;
    }

    // ─── submit ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentRequest submit(String factoryId, String requestId, Long userId) {
        PaymentRequest pr = findAndValidate(requestId, factoryId);
        requireStatus(pr, PaymentRequestStatus.PENDING, "只有 PENDING 状态的申请单可以提交");

        pr.setStatus(PaymentRequestStatus.FINANCE_REVIEW);
        PaymentRequest saved = paymentRequestRepository.save(pr);
        log.info("[SP6] 付款申请单 {} 提交初审 by userId={}", pr.getRequestNumber(), userId);
        return saved;
    }

    // ─── financeApprove ───────────────────────────────────────────────────────

    /**
     * 财务审批 — 六扇门 #30 优先走 Canvas-configured workflow，否则 legacy 硬编码状态机。
     *
     * <p>分支策略（镜像 {@code PurchaseServiceImpl.approveOrder}）:
     * <ol>
     *   <li>{@code workflowEngine} bean 不可用 → legacy（PR → APPROVED）</li>
     *   <li>factory 无 active PURCHASE_PAYMENT workflow → legacy</li>
     *   <li>有 active workflow → start / resume，按终态翻译 PR 状态</li>
     * </ol>
     */
    @Override
    @Transactional
    public PaymentRequest financeApprove(String factoryId, String requestId, Long userId, String note) {
        PaymentRequest pr = findAndValidate(requestId, factoryId);
        requireStatus(pr, PaymentRequestStatus.FINANCE_REVIEW, "只有 FINANCE_REVIEW 状态的申请单可以财务审批");

        // 六扇门 #30 — 引擎不可用直接 legacy
        if (workflowEngine == null) {
            return legacyFinanceApprove(pr, userId, note);
        }

        // Phase 1 hotfix 模式 — 预检 active workflow 存在，避免 startWorkflow 的 orElseThrow
        // 触发 Spring "Transaction marked rollback-only" 陷阱（PurchaseService 2026-05-18 踩过）。
        if (!workflowEngine.hasActiveWorkflow(pr.getFactoryId(), WORKFLOW_MODULE_CODE)) {
            log.info("[#30] factory={} 无 active PURCHASE_PAYMENT 审批工作流，走 legacy 硬编码状态机",
                    pr.getFactoryId());
            return legacyFinanceApprove(pr, userId, note);
        }

        // 构建 workflow 评估上下文（edges 上 SpEL 读 #context.xxx）
        Map<String, Object> context = new HashMap<>();
        BigDecimal amount = pr.getAmount() != null ? pr.getAmount() : BigDecimal.ZERO;
        context.put("amount", amount);
        context.put("requestId", pr.getId());
        context.put("supplierId", pr.getSupplierId() != null ? pr.getSupplierId() : "");
        context.put("purchaseOrderId", pr.getPurchaseOrderId() != null ? pr.getPurchaseOrderId() : "");
        context.put("settlementType", pr.getSettlementType() != null ? pr.getSettlementType().name() : "");
        context.put("decision", "APPROVE");

        // 已有 RUNNING 实例（多级审批中，resume）还是首次启动
        Optional<ApprovalWorkflowInstance> existing = workflowEngine.getCurrentInstance(
                pr.getFactoryId(), WORKFLOW_MODULE_CODE, pr.getId());

        ApprovalWorkflowInstance instance;
        if (existing.isPresent() && existing.get().getStatus() == InstanceStatus.RUNNING) {
            instance = workflowEngine.transitionNode(
                    existing.get().getId(), userId, "finance_manager",
                    HistoryAction.APPROVE, note != null ? note : "付款申请财务审批");
        } else {
            // 预检已确认 hasActiveWorkflow=true，此处不应抛。若 race condition（workflow 刚被 disable）
            // 仍 IllegalArgumentException，让异常上抛 → controller 4xx → 用户重试（届时 legacy）。
            instance = workflowEngine.startWorkflow(
                    pr.getFactoryId(), WORKFLOW_MODULE_CODE, pr.getId(), context, userId);
        }

        // 记录财务初审痕迹（无论 workflow 是否终态，本次审批人/意见都留痕）
        pr.setWorkflowInstanceId(instance.getId());
        pr.setFinanceReviewedBy(userId);
        pr.setFinanceReviewedAt(LocalDateTime.now());
        pr.setFinanceReviewNote(note);
        pr.setSubmittedBy(userId);

        // 按 workflow 终态翻译 PR 状态
        PaymentRequestStatus translated = translateInstanceStatus(instance.getStatus());
        pr.setStatus(translated);

        if (translated == PaymentRequestStatus.APPROVED) {
            // 工作流终态 APPROVED → 解锁付款，记录最终批准痕迹
            pr.setApprovedBy(userId);
            pr.setApprovedAt(LocalDateTime.now());
        } else if (translated == PaymentRequestStatus.REJECTED) {
            pr.setRejectReason(note != null && !note.isBlank() ? note : "工作流审批拒绝");
        }

        PaymentRequest saved = paymentRequestRepository.save(pr);
        log.info("[#30] 付款申请单 {} 工作流审批: instanceId={}, instanceStatus={}, prStatus={}, by userId={}",
                pr.getRequestNumber(), instance.getId(), instance.getStatus(), translated, userId);
        return saved;
    }

    /**
     * Legacy 硬编码财务审批 — 无 workflow 配置时调用，保持 F001 等工厂向后兼容。
     * 直接 FINANCE_REVIEW → APPROVED（单级财务审批）。
     */
    private PaymentRequest legacyFinanceApprove(PaymentRequest pr, Long userId, String note) {
        pr.setStatus(PaymentRequestStatus.APPROVED);
        pr.setFinanceReviewedBy(userId);
        pr.setFinanceReviewedAt(LocalDateTime.now());
        pr.setFinanceReviewNote(note);
        pr.setApprovedBy(userId);
        pr.setApprovedAt(LocalDateTime.now());
        PaymentRequest saved = paymentRequestRepository.save(pr);
        log.info("[SP6] [legacy] 付款申请单 {} 财务审批通过 by userId={}", pr.getRequestNumber(), userId);
        return saved;
    }

    /**
     * 六扇门 #30 — workflow 实例终态 → PaymentRequest 状态映射。
     *
     * <ul>
     *   <li>{@code APPROVED} → {@code APPROVED}（解锁 markPaid 红线）</li>
     *   <li>{@code RUNNING} → {@code FINANCE_REVIEW}（多级审批中，停在可继续审批的状态，
     *       不解锁付款）</li>
     *   <li>{@code REJECTED} → {@code REJECTED}</li>
     *   <li>{@code CANCELLED / TIMEOUT} → {@code FINANCE_REVIEW}（实例结束，回可重审状态）</li>
     * </ul>
     */
    private PaymentRequestStatus translateInstanceStatus(InstanceStatus status) {
        if (status == null) return PaymentRequestStatus.FINANCE_REVIEW;
        return switch (status) {
            case APPROVED -> PaymentRequestStatus.APPROVED;
            case RUNNING -> PaymentRequestStatus.FINANCE_REVIEW;
            case REJECTED -> PaymentRequestStatus.REJECTED;
            case CANCELLED, TIMEOUT -> PaymentRequestStatus.FINANCE_REVIEW;
        };
    }

    // ─── reject ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentRequest reject(String factoryId, String requestId, Long userId, String reason) {
        PaymentRequest pr = findAndValidate(requestId, factoryId);
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
     * #29 markPaid 按 sourceType 分流（单一 @Transactional，⛔禁 fail-soft，⛔禁 REQUIRES_NEW）：
     *
     * <ul>
     *   <li>PURCHASE（SP6/D-9 原有，字节不变）：AP_PAYMENT + SUPPLIER + Supplier.currentBalance -= amount。</li>
     *   <li>SALES（#29 新增）：AR_CREDIT_NOTE + CUSTOMER + Customer.currentBalance -= amount。</li>
     * </ul>
     *
     * <p>sourceType=null（理论上不会，列 NOT NULL + 迁移回填 PURCHASE）按 PURCHASE 兜底，保证老数据安全。
     */
    @Override
    @Transactional
    public PaymentRequest markPaid(String factoryId, String requestId, Long userId, String evidence) {
        // Step 0: 找申请单
        PaymentRequest pr = findAndValidate(requestId, factoryId);
        requireStatus(pr, PaymentRequestStatus.APPROVED, "只有 APPROVED 状态的申请单可以标记付款");

        // #29: 销售方向单独分流；其余（含 null 老数据）走原采购路径
        if (pr.getSourceType() == PaymentSourceType.SALES) {
            return markPaidSales(pr, userId, evidence);
        }
        return markPaidPurchase(pr, userId, evidence);
    }

    /**
     * 采购方向三写原子（SP6/D-9 原有逻辑，⛔字节不变）：
     * 1. PaymentRequest.status = PAID + paidBy + paidAt
     * 2. 保存 ArApTransaction(AP_PAYMENT, SUPPLIER, amount, balanceAfter)
     * 3. Supplier.currentBalance -= amount
     *
     * <p>balanceAfter = supplier.currentBalance - amount（付款减少应付账款）。
     * ArApTransaction.balanceAfter NOT NULL，必须在 save 前计算设置。
     */
    private PaymentRequest markPaidPurchase(PaymentRequest pr, Long userId, String evidence) {
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

        // Step 5: 🔒🔒 资金段 GL (finance audit BUG 2)：发布现金流水事件 → AFTER_COMMIT 监听器
        // fail-soft 生成 付款凭证 (借 2202 应付账款 [供应商辅助核算] / 贷 1002 银行存款)。
        // #1262 将所有付款 UI 导流到此方法后遗漏本步，导致采购付款不记 GL。amount 用正数
        // (pr.getAmount() 恒正)，镜像 ArApServiceImpl.recordApPayment 的 publishCashMovement 口径。
        // 幂等键 = savedTx.getId() (本次 AP_PAYMENT 子账 id，每次付款唯一)。
        publishCashPaymentMovement(savedTx, pr.getSupplierId(), supplier.getName(),
                amount, pr.getPaymentMethod(), userId);

        log.info("[SP6] 付款申请单 {} 已标记付款 amount={} supplierBalance → {} tx={}",
                pr.getRequestNumber(), amount, newBalance, savedTx.getId());
        return savedPr;
    }

    /**
     * 🔒🔒 资金段 GL 桥 (finance audit BUG 2)：发布采购付款现金流水事件。
     *
     * <p>publisher 为 null (测试 / 未装配) 时静默跳过；发布失败仅告警不影响付款主流程
     * (镜像 {@code ArApServiceImpl.publishCashMovement} 的 fail-soft 语义)。事件由
     * {@code CashMovementVoucherListener} 以 {@code @TransactionalEventListener(AFTER_COMMIT)}
     * 监听，生成 借 2202 应付账款 / 贷 1002 银行存款 (或 1001 库存现金，按 paymentMethod) 凭证。
     *
     * @param paymentMethodRaw PaymentRequest.paymentMethod 是自由文本 String，尝试解析为
     *                         {@link PaymentMethod} 枚举 (决定现金科目 1001/1002)，解析失败传 null
     *                         → 监听器默认 1002 银行存款 (非现金付款的安全默认)。
     */
    private void publishCashPaymentMovement(ArApTransaction savedTx, String supplierId,
                                            String supplierName, BigDecimal amount,
                                            String paymentMethodRaw, Long operatedBy) {
        if (applicationEventPublisher == null) {
            return;
        }
        try {
            PaymentMethod method = parsePaymentMethod(paymentMethodRaw);
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.CashMovementRecordedEvent(
                    this, savedTx.getFactoryId(), savedTx.getId(),
                    com.cretas.aims.entity.enums.VoucherType.CASH_PAYMENT,
                    supplierId, supplierName,
                    amount != null ? amount.abs() : BigDecimal.ZERO, method,
                    savedTx.getTransactionDate() != null ? savedTx.getTransactionDate() : LocalDate.now(),
                    operatedBy));
        } catch (Exception e) {
            log.error("发布 CashMovementRecordedEvent 失败 (不影响付款): txnId={}: {}",
                    savedTx.getId(), e.getMessage(), e);
        }
    }

    /** 自由文本 paymentMethod → {@link PaymentMethod} 枚举；无法匹配返 null (监听器按非现金 → 1002)。 */
    private PaymentMethod parsePaymentMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PaymentMethod.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * #29 销售方向三写原子（对客户 outbound 付款，⛔禁 fail-soft，⛔禁 REQUIRES_NEW）：
     * 1. PaymentRequest.status = PAID + paidBy + paidAt
     * 2. 保存 ArApTransaction(AR_CREDIT_NOTE, CUSTOMER, amount, balanceAfter)
     * 3. Customer.currentBalance -= amount
     *
     * <p>语义：对客户付出退款/返利/销售费用 → 冲减客户应收余额（AR_CREDIT_NOTE）。
     * balanceAfter = customer.currentBalance - amount。null-safe 同采购路径。
     */
    private PaymentRequest markPaidSales(PaymentRequest pr, Long userId, String evidence) {
        // Step 0b: 找客户（null-safe 保护，异常直接 doom 整个事务）
        Customer customer = customerRepository.findById(pr.getCustomerId())
                .orElseThrow(() -> new BusinessException(
                        "客户不存在: " + pr.getCustomerId() + "，无法完成付款"));

        // Step 1: PaymentRequest → PAID
        pr.setStatus(PaymentRequestStatus.PAID);
        pr.setPaidBy(userId);
        pr.setPaidAt(LocalDateTime.now());
        if (evidence != null && !evidence.isBlank()) {
            pr.setRemark((pr.getRemark() == null ? "" : pr.getRemark() + " | ") + "凭证: " + evidence);
        }

        // Step 2: 计算 balanceAfter 并保存 ArApTransaction（AR_CREDIT_NOTE 冲减客户应收）
        BigDecimal amount = pr.getAmount();
        BigDecimal currentBalance = customer.getCurrentBalance() != null
                ? customer.getCurrentBalance()
                : BigDecimal.ZERO;
        BigDecimal newBalance = currentBalance.subtract(amount);

        ArApTransaction tx = new ArApTransaction();
        tx.setFactoryId(pr.getFactoryId());
        tx.setTransactionNumber(generateTxNumber());
        tx.setTransactionType(ArApTransactionType.AR_CREDIT_NOTE);
        tx.setCounterpartyType(CounterpartyType.CUSTOMER);
        tx.setCounterpartyId(pr.getCustomerId());
        tx.setCounterpartyName(customer.getName());
        tx.setSalesOrderId(pr.getSalesOrderId());        // 可空（无单退款/费用）
        tx.setAmount(amount);
        tx.setBalanceAfter(newBalance);                  // ⛔ NOT NULL 必须设置
        tx.setTransactionDate(LocalDate.now());
        tx.setOperatedBy(userId);
        tx.setApprovalStatus(ArApApprovalStatus.APPROVED);
        tx.setRemark("销售付款申请单 " + pr.getRequestNumber() + " 标记付款（客户 outbound）");

        ArApTransaction savedTx = arApTransactionRepository.save(tx);

        // Step 3: 关联 ArApTransaction ID 到 PaymentRequest
        pr.setArapTransactionId(savedTx.getId());
        PaymentRequest savedPr = paymentRequestRepository.save(pr);

        // Step 4: 扣减客户余额（同事务，任一失败全部回滚）
        customer.setCurrentBalance(newBalance);
        customerRepository.save(customer);

        // 🔒🔒 资金段 GL (finance audit BUG 2)：销售方向 (对客户退款/返利/销售费用) 的现金流水 GL
        // 【暂不发事件 — 待财务/业务定夺】。原因：CashMovementVoucherListener 只有 CASH_RECEIPT
        // (借现金/贷1122应收) 与 CASH_PAYMENT (借2202应付/贷1002现金) 两个分支，二者都不匹配"对客户
        // 付现金"的贷方现金 + 借方需按业务性质区分 (退款→借2203预收 / 返利→借6001主营收入红冲或6601销售费用 /
        // 费用→借6601)。当前数据模型不区分这三种意图，照抄收款镜像会记错科目污染 GL (🔒🔒)。故保持
        // status-quo (不发事件，无 GL，与 #1262 前一致)，不猜测方向。修法建议见 PR 说明。
        log.info("[#29] 销售付款申请单 {} 已标记付款 amount={} customerBalance → {} tx={}",
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
                    // 出纳付款收款方银行信息: 权威来源是供应商(收款方)主数据, 不是付款单本身.
                    // 旧代码读 pr.getBankName()/getBankAccount() (付款单从不存 bank → 永远 null,
                    // 出纳不知打款到哪, 2026-06-12 Codex Gate2 实测). 改优先取 supplier, 付款单有覆盖值时兜底.
                    .bankName(supplier != null && supplier.getBankName() != null
                            ? supplier.getBankName() : pr.getBankName())
                    .bankAccount(supplier != null && supplier.getBankAccount() != null
                            ? supplier.getBankAccount() : pr.getBankAccount())
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

    private PaymentRequest findAndValidate(String requestId, String factoryId) {
        PaymentRequest pr = paymentRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException("付款申请单不存在: " + requestId));
        // 多租户隔离 (审计 round4, 财务红线): 付款申请单必须属于路径 factoryId, 否则 403。
        // 此前 findById(requestId) 不校验归属 → F006 用户可 提交/审批/驳回/标记付款 别家工厂的
        // 付款申请单 (requestId 可枚举)。同 disposal #1009 / work-session #1010 类。
        if (pr.getFactoryId() == null || !pr.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该付款申请单");
        }
        return pr;
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
