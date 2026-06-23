package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.enums.ReceiveExceptionType;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseExceptionRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.service.inventory.PurchaseExceptionService;
import com.cretas.aims.service.inventory.ReturnOrderService;
import com.cretas.aims.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 采购异常单 Service 实现（SP6）
 *
 * <p>decideException(RETURN_OVER) 使用 REQUIRES_NEW 隔离退货单创建，
 * 防止 doomed-tx 传播回本事务（参见 feedback_failsoft_catch_cannot_save_doomed_tx）。
 *
 * <p>D-6 责任绑定：generateExceptionsForReceive 从调用方传入 ownerUserId/ownerName
 * （通常来自 PurchaseOrder.createdBy + User.username）。
 * decideException 检查调用方是否为本人或主管角色，他人收到 403 + 责任人信息。
 * 注意：鉴权用 callerRole 参数（来自 request attribute "role"，JwtAuthInterceptor 设置），
 * 不用 SecurityContextHolder（项目已知坑：SecurityContext 永空）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseExceptionServiceImpl implements PurchaseExceptionService {

    /** 主管角色：factory_super_admin 和 procurement_manager 可代为决策 */
    private static final Set<String> SUPERVISOR_ROLES = Set.of(
            "factory_super_admin", "procurement_manager"
    );

    private final PurchaseExceptionRepository exceptionRepository;

    /** D-BUG3: RETURN_OVER 决策时创建采购退货单 (PURCHASE_RETURN ReturnOrder)。 */
    private final ReturnOrderService returnOrderService;

    /** D-BUG3: 查源采购单行项目, 取退货单价 (PurchaseException 无 unitPrice 字段)。 */
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;

    /** 可选通知服务：未配置时静默跳过（不影响主流程） */
    @Autowired(required = false)
    private NotificationService notificationService;

    /**
     * Self-injection (proxy) 用于触发 RETURN_OVER 退货单创建的 {@code REQUIRES_NEW} 事务边界。
     *
     * <p>退货单创建 ({@link #createReturnOrderForException}) 必须跑独立事务: 创建失败抛
     * {@code BusinessException} 只回滚退货单自身, 不 doom 父事务 (异常单 RESOLVED 状态仍持久化)。
     * 参考 {@code ReverseTransferService} R6 self-proxy 范式 (本仓库多次复发的 doomed-tx 防御)。
     *
     * <p>单测兼容: 单测直接 new 实例 ({@code self} 为 null), 通过 {@link #proxy()} fallback 到 this。
     */
    @Autowired
    @Lazy
    private PurchaseExceptionServiceImpl self;

    /** Self-proxy fallback: Spring 注入则用代理 (触发 REQUIRES_NEW), 单测下 self==null 退回 this。 */
    private PurchaseExceptionServiceImpl proxy() {
        return self != null ? self : this;
    }

    // ─── generateExceptionsForReceive ─────────────────────────────────────

    @Override
    @Transactional
    public List<PurchaseException> generateExceptionsForReceive(
            String factoryId,
            String receiveRecordId,
            String purchaseOrderId,
            String supplierId,
            String materialTypeId,
            String materialName,
            BigDecimal poQuantity,
            BigDecimal receivedQuantity,
            String unit,
            Long createdBy,
            Long ownerUserId,
            String ownerName) {

        List<PurchaseException> result = new ArrayList<>();

        if (poQuantity == null || receivedQuantity == null) {
            return result;
        }

        int cmp = receivedQuantity.compareTo(poQuantity);
        if (cmp == 0) {
            // 无异常
            return result;
        }

        ReceiveExceptionType type = cmp > 0 ? ReceiveExceptionType.OVER_RECEIVE : ReceiveExceptionType.UNDER_RECEIVE;
        BigDecimal exceptionQty = receivedQuantity.subtract(poQuantity).abs();

        PurchaseException pe = new PurchaseException();
        pe.setFactoryId(factoryId);
        pe.setReceiveRecordId(receiveRecordId);
        pe.setPurchaseOrderId(purchaseOrderId);
        pe.setSupplierId(supplierId);
        pe.setMaterialTypeId(materialTypeId);
        pe.setMaterialName(materialName);
        pe.setExceptionType(type);
        pe.setPoQuantity(poQuantity);
        pe.setReceivedQuantity(receivedQuantity);
        pe.setExceptionQty(exceptionQty);
        pe.setUnit(unit);
        pe.setStatus("PENDING");
        pe.setCreatedBy(createdBy);
        pe.setExceptionNumber(generateExceptionNumber(factoryId));
        // D-6 责任绑定：责任人=源采购单的创建人（ownerUserId/ownerName 由调用方从 PO 查出传入）
        pe.setOwnerUserId(ownerUserId);
        pe.setOwnerName(ownerName);

        PurchaseException saved = exceptionRepository.save(pe);
        log.info("[SP6] 生成采购异常单 {} 类型={} 差异={} {} 责任人={}({})",
                pe.getExceptionNumber(), type, exceptionQty, unit, ownerName, ownerUserId);

        // D-6 通知：生成异常单时给责任人发站内通知
        if (notificationService != null && ownerUserId != null) {
            try {
                notificationService.notifyUser(
                        factoryId,
                        ownerUserId,
                        "采购入库异常待处理",
                        String.format("异常单 %s：物料「%s」%s（差异 %s %s），请前往「采购管理 → 入库异常」处理。",
                                pe.getExceptionNumber(),
                                materialName != null ? materialName : "未知物料",
                                type == ReceiveExceptionType.OVER_RECEIVE ? "超收" : "少收",
                                exceptionQty.stripTrailingZeros().toPlainString(),
                                unit != null ? unit : "")
                );
            } catch (Exception notifyEx) {
                // 通知失败不影响主流程
                log.warn("[SP6] 发送异常通知失败（不影响入库）: exceptionId={}, ownerUserId={}, error={}",
                        saved.getId(), ownerUserId, notifyEx.getMessage());
            }
        }

        result.add(saved);
        return result;
    }

    // ─── decideException ───────────────────────────────────────────────────

    /**
     * REQUIRES_NEW：decideException 如果触发 RETURN_OVER 分支，
     * 退货单创建失败不得 doom 本事务（异常单本身状态变更仍持久化）。
     *
     * <p>注意：@Transactional(propagation=REQUIRES_NEW) 仅在真正调用
     * 子 @Service（另一 Spring bean）时才能在 REQUIRES_NEW 下正确隔离。
     * 此处 decideException 本身就是该入口，外部调用时 Spring 会开新事务。
     *
     * <p>D-6 鉴权：callerRole 来自 request attribute "role"（JwtAuthInterceptor 设置），
     * 不用 SecurityContextHolder（项目已知坑：SecurityContext 永空）。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PurchaseException decideException(
            String exceptionId,
            String factoryId,
            ExceptionDecision decision,
            String notes,
            Long decisionBy,
            String callerRole) {

        PurchaseException pe = exceptionRepository.findById(exceptionId)
                .orElseThrow(() -> new BusinessException("采购异常单不存在: " + exceptionId));

        if (!factoryId.equals(pe.getFactoryId())) {
            throw new BusinessException(403, "无权操作此异常单");
        }
        if (!"PENDING".equals(pe.getStatus())) {
            throw new BusinessException("异常单已处理（status=" + pe.getStatus() + "），无法再次决策");
        }

        // D-6 责任绑定鉴权：本人或主管角色才可决策（Fool-proof Rule 2 — 指明责任人是谁）
        boolean isSupervisor = callerRole != null && SUPERVISOR_ROLES.contains(callerRole);
        boolean isOwner = pe.getOwnerUserId() != null && pe.getOwnerUserId().equals(decisionBy);
        if (!isOwner && !isSupervisor) {
            String ownerInfo = pe.getOwnerName() != null
                    ? pe.getOwnerName() + "（userId=" + pe.getOwnerUserId() + "）"
                    : (pe.getOwnerUserId() != null ? "userId=" + pe.getOwnerUserId() : "未知");
            throw new BusinessException(403,
                    "仅责任采购人 " + ownerInfo + " 或主管可处理此异常单，当前用户无权操作");
        }

        // 决策类型与异常类型不兼容校验
        if (pe.getExceptionType() == ReceiveExceptionType.OVER_RECEIVE &&
                (decision == ExceptionDecision.ACCEPT_SHORT || decision == ExceptionDecision.REQUEST_RESUPPLY)) {
            throw new BusinessException("超收异常不能使用少收决策").withCode("EXCEPTION_DECISION_MISMATCH");
        }
        if (pe.getExceptionType() == ReceiveExceptionType.UNDER_RECEIVE &&
                (decision == ExceptionDecision.ACCEPT_OVER || decision == ExceptionDecision.RETURN_OVER)) {
            throw new BusinessException("少收异常不能使用超收决策").withCode("EXCEPTION_DECISION_MISMATCH");
        }

        // BLOCKER 3: RETURN_OVER 必须能创建采购退货单 (ReturnOrder.counterpartyId = supplierId, NOT NULL)。
        // 若异常单无供应商信息 (supplierId 空), 退货单创建会在 REQUIRES_NEW 内抛 → fail-soft catch 吞掉
        // → 异常单变 RESOLVED 但无退货单 → 用户看到"处理成功" (降级假成功, 违反禁降级)。
        // 在【翻 RESOLVED 之前】前置校验, 让操作员看到明确错误, 决策不提交 (校验在 catch 外, fail-loud)。
        if (decision == ExceptionDecision.RETURN_OVER
                && (pe.getSupplierId() == null || pe.getSupplierId().isBlank())) {
            throw new BusinessException(422, "无供应商信息, 无法创建采购退货单")
                    .withCode("RETURN_OVER_NO_SUPPLIER")
                    .withHint("该入库异常未关联供应商, 无法退回供应商。"
                            + "请改用「接受超收」决策, 或联系采购补全供应商信息后重试。");
        }

        pe.setDecision(decision);
        pe.setDecisionBy(decisionBy);
        pe.setDecisionAt(LocalDateTime.now());
        pe.setDecisionNotes(notes);
        pe.setStatus("RESOLVED");

        PurchaseException saved = exceptionRepository.save(pe);
        log.info("[SP6] 异常单 {} 决策={} by userId={}", pe.getExceptionNumber(), decision, decisionBy);

        // D-BUG3: RETURN_OVER 决策 → 真正创建采购退货单 (PURCHASE_RETURN ReturnOrder)。
        // 客户红线 (catalog 行2399-2413): "退货跟钱有关, 必须先财务审批" — 退货单初始 DRAFT,
        // 走 submit → approve → finance-approve → complete 链, 不自动审批。
        //
        // 幂等: 仅在 returnOrderId 尚未设置时创建 (重复决策已被上面的 PENDING 守卫拦截,
        // 此处双保险防御历史脏数据 / 并发)。
        // 事务隔离: 退货单创建走 self-proxy REQUIRES_NEW, 失败只回滚退货单, 异常单 RESOLVED 仍持久化。
        if (decision == ExceptionDecision.RETURN_OVER && saved.getReturnOrderId() == null) {
            try {
                ReturnOrder returnOrder = proxy().createReturnOrderForException(saved, decisionBy, notes);
                // 退货单创建成功 (独立事务已 commit) → 回写关联 ID 到异常单。
                saved.setReturnOrderId(returnOrder.getId());
                saved = exceptionRepository.save(saved);
                log.info("[SP6] 异常单 {} RETURN_OVER 已生成采购退货单 {} (退货单号 {}), 待财务审批",
                        saved.getExceptionNumber(), returnOrder.getId(), returnOrder.getReturnNumber());
            } catch (BusinessException be) {
                // MEDIUM: 收窄 fail-soft — 退货单创建的【业务规则拒绝】(deliberate, 如缺必填/校验失败)
                // 必须 fail-loud, 不能吞成"异常单 RESOLVED 但无退货单"的降级假成功 (违反禁降级)。
                // re-throw 会回滚本事务 (RESOLVED flip), 操作员看到明确错误, 不留半截状态。
                // (BLOCKER 3 的 supplierId 空校验已在 catch 外前置, 此处兜结构性/下游业务拒绝。)
                log.warn("[SP6] 异常单 {} RETURN_OVER 退货单创建被业务规则拒绝 (回滚决策, fail-loud): {}",
                        saved.getExceptionNumber(), be.getMessage());
                throw be;
            } catch (Exception e) {
                // 仅【真·非预期/瞬时基础设施错误】才 best-effort 吞 (异常单仍 RESOLVED, 可手动补建退货单),
                // 避免 doomed-tx 拖垮异常单决策。结构性编程错误已由上面 BusinessException 分支 fail-loud。
                log.error("[SP6] 异常单 {} RETURN_OVER 退货单创建遇非预期错误 (异常单仍标 RESOLVED, 可手动补建): {}",
                        saved.getExceptionNumber(), e.getMessage(), e);
            }
        }

        return saved;
    }

    // ─── createReturnOrderForException (RETURN_OVER 退货单创建) ─────────────────

    /**
     * D-BUG3: 为 RETURN_OVER 异常单创建一张采购退货单 (PURCHASE_RETURN ReturnOrder)。
     *
     * <p>映射: 退货单 counterparty=供应商 (exception.supplierId), sourceOrder=采购单
     * (exception.purchaseOrderId), 退货物料=异常单物料, 退货数量=超收差值 (exception.exceptionQty)。
     * 退货单价取自源采购单对应行项目 (PurchaseOrderItem.unitPrice), 用于退货金额 → AR/AP 冲减。
     *
     * <p>状态: 初始 DRAFT (ReturnOrderService.createReturnOrder 强制), 不自动审批 —— 客户红线
     * "退货必须先财务审批", 退货单后续走 submit → approve → finance-approve → complete 链。
     *
     * <p>{@code REQUIRES_NEW}: 独立事务, 创建失败只回滚退货单自身, 不污染异常单 RESOLVED 状态。
     *
     * @param exception  已 RESOLVED 的 RETURN_OVER 异常单
     * @param decisionBy 决策人 userId (退货单 createdBy)
     * @param notes      决策备注 (并入退货原因)
     * @return 新建的退货单 (DRAFT)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReturnOrder createReturnOrderForException(PurchaseException exception, Long decisionBy, String notes) {
        CreateReturnOrderRequest request = new CreateReturnOrderRequest();
        request.setReturnType(ReturnType.PURCHASE_RETURN.name());
        request.setCounterpartyId(exception.getSupplierId());
        request.setSourceOrderId(exception.getPurchaseOrderId());
        request.setReturnDate(LocalDate.now());
        // 有货退回供应商 (默认), AR/AP 冲减在 completeReturnOrder 触发 (实物退回后)。
        request.setWithGoods(Boolean.TRUE);
        String reason = "采购超收退货 (异常单 " + exception.getExceptionNumber() + ")";
        if (notes != null && !notes.isBlank()) {
            reason = reason + " — " + notes;
        }
        request.setReason(reason);

        // 退货行: 退超收差值。单价取自源采购单对应物料行 (PurchaseException 无 unitPrice 字段)。
        BigDecimal unitPrice = resolveUnitPrice(exception);
        CreateReturnOrderRequest.ReturnOrderItemDTO item = new CreateReturnOrderRequest.ReturnOrderItemDTO();
        item.setMaterialTypeId(exception.getMaterialTypeId());
        item.setItemName(exception.getMaterialName());
        item.setQuantity(exception.getExceptionQty());
        item.setUnitPrice(unitPrice);
        item.setReason(reason);
        request.setItems(List.of(item));

        // ReturnOrderService 会写 ReturnOrder.factoryId = 传入 factoryId (= 异常单 factoryId, 跨租户安全)。
        return returnOrderService.createReturnOrder(exception.getFactoryId(), request, decisionBy);
    }

    /**
     * 取源采购单中该物料行的未税单价 (退货金额口径)。
     * 找不到对应行 (历史脏数据 / PO 已删) → 返 null, 退货单价为空, lineAmount 按 0 计 (退货金额 0,
     * 财务审批时人工核对), 不阻断退货单创建。
     */
    private BigDecimal resolveUnitPrice(PurchaseException exception) {
        if (exception.getPurchaseOrderId() == null || exception.getMaterialTypeId() == null) {
            return null;
        }
        try {
            List<PurchaseOrderItem> poItems =
                    purchaseOrderItemRepository.findByPurchaseOrderId(exception.getPurchaseOrderId());
            return poItems.stream()
                    .filter(i -> exception.getMaterialTypeId().equals(i.getMaterialTypeId()))
                    .map(PurchaseOrderItem::getUnitPrice)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[SP6] 取采购单 {} 物料 {} 单价失败, 退货单价留空: {}",
                    exception.getPurchaseOrderId(), exception.getMaterialTypeId(), e.getMessage());
            return null;
        }
    }

    // ─── listExceptions ────────────────────────────────────────────────────

    @Override
    public List<PurchaseException> listExceptions(String factoryId, String status) {
        if (status == null || status.isBlank()) {
            return exceptionRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId);
        }
        return exceptionRepository.findByFactoryIdAndStatus(factoryId, status);
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private String generateExceptionNumber(String factoryId) {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = exceptionRepository.countPendingByFactoryId(factoryId) + 1;
        return String.format("EX-%s-%s-%04d", factoryId, date, count);
    }
}
