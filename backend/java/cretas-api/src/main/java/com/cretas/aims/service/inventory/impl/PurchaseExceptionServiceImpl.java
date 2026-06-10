package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.enums.ReceiveExceptionType;
import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseExceptionRepository;
import com.cretas.aims.service.inventory.PurchaseExceptionService;
import com.cretas.aims.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
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

    /** 可选通知服务：未配置时静默跳过（不影响主流程） */
    @Autowired(required = false)
    private NotificationService notificationService;

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

        pe.setDecision(decision);
        pe.setDecisionBy(decisionBy);
        pe.setDecisionAt(LocalDateTime.now());
        pe.setDecisionNotes(notes);
        pe.setStatus("RESOLVED");

        PurchaseException saved = exceptionRepository.save(pe);
        log.info("[SP6] 异常单 {} 决策={} by userId={}", pe.getExceptionNumber(), decision, decisionBy);
        return saved;
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
