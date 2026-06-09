package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.enums.ReceiveExceptionType;
import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseExceptionRepository;
import com.cretas.aims.service.inventory.PurchaseExceptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 采购异常单 Service 实现（SP6）
 *
 * <p>decideException(RETURN_OVER) 使用 REQUIRES_NEW 隔离退货单创建，
 * 防止 doomed-tx 传播回本事务（参见 feedback_failsoft_catch_cannot_save_doomed_tx）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseExceptionServiceImpl implements PurchaseExceptionService {

    private final PurchaseExceptionRepository exceptionRepository;

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
            Long createdBy) {

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

        result.add(exceptionRepository.save(pe));
        log.info("[SP6] 生成采购异常单 {} 类型={} 差异={} {}", pe.getExceptionNumber(), type, exceptionQty, unit);
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
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PurchaseException decideException(
            String exceptionId,
            String factoryId,
            ExceptionDecision decision,
            String notes,
            Long decisionBy) {

        PurchaseException pe = exceptionRepository.findById(exceptionId)
                .orElseThrow(() -> new BusinessException("采购异常单不存在: " + exceptionId));

        if (!factoryId.equals(pe.getFactoryId())) {
            throw new BusinessException(403, "无权操作此异常单");
        }
        if (!"PENDING".equals(pe.getStatus())) {
            throw new BusinessException("异常单已处理（status=" + pe.getStatus() + "），无法再次决策");
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
