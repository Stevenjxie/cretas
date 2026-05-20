package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.QualityReturnOrder;
import com.cretas.aims.entity.enums.QualityReturnStatus;
import com.cretas.aims.entity.enums.QualityReturnTargetType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.QualityReturnOrderRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.annotation.DataScope;
import com.cretas.aims.service.QualityReturnOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Sprint4-H Q-RETURN-1: 质检退回单业务实现.
 *
 * <p>状态机:
 * <pre>
 *   createDraft()  → DRAFT
 *   confirm()      DRAFT      → CONFIRMED
 *   ship()         CONFIRMED  → SHIPPED
 *   cancelDraft()  DRAFT      → 软删
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityReturnOrderServiceImpl implements QualityReturnOrderService {

    private static final DateTimeFormatter DATE_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final QualityReturnOrderRepository returnOrderRepository;
    private final QualityInspectionRepository inspectionRepository;

    /** Sprint 6 W2-B: 数据权限解析器 (optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataScopeResolver dataScopeResolver;

    @Override
    @Transactional
    public QualityReturnOrder createDraft(String factoryId, String qualityInspectionId,
                                           QualityReturnTargetType targetType, String targetId,
                                           String targetName, String materialId,
                                           BigDecimal quantity, String unit, String reason,
                                           Long createdBy) {
        if (targetType == null) {
            throw new BusinessException(400, "退回目标类型 targetType 必填");
        }
        if (targetId == null || targetId.trim().isEmpty()) {
            throw new BusinessException(400, "接收方 ID targetId 必填");
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "退回数量 quantity 必须 > 0");
        }
        // 验证 inspection 存在且属于本工厂
        QualityInspection inspection = inspectionRepository.findById(qualityInspectionId)
                .orElseThrow(() -> new ResourceNotFoundException("质检记录不存在"));
        if (!factoryId.equals(inspection.getFactoryId())) {
            throw new ResourceNotFoundException("质检记录不属于当前工厂");
        }

        String returnNumber = generateReturnNumber(factoryId);

        QualityReturnOrder order = QualityReturnOrder.builder()
                .factoryId(factoryId)
                .returnNumber(returnNumber)
                .qualityInspectionId(qualityInspectionId)
                .targetType(targetType)
                .targetId(targetId)
                .targetName(targetName)
                .materialId(materialId)
                .quantity(quantity)
                .unit(unit)
                .reason(reason)
                .status(QualityReturnStatus.DRAFT)
                .createdBy(createdBy)
                .build();
        QualityReturnOrder saved = returnOrderRepository.save(order);
        log.info("创建质检退回单草稿: returnNumber={}, target={}/{}, qty={}",
                returnNumber, targetType, targetId, quantity);
        return saved;
    }

    @Override
    @Transactional
    public QualityReturnOrder updateDraft(String factoryId, String id,
                                           QualityReturnTargetType targetType, String targetId,
                                           String targetName, String materialId,
                                           BigDecimal quantity, String unit, String reason) {
        QualityReturnOrder order = get(factoryId, id);
        if (order.getStatus() != QualityReturnStatus.DRAFT) {
            throw new BusinessException(409, "仅草稿状态可编辑")
                    .withHint("请刷新列表查看最新状态");
        }
        if (targetType != null) order.setTargetType(targetType);
        if (targetId != null) order.setTargetId(targetId);
        if (targetName != null) order.setTargetName(targetName);
        if (materialId != null) order.setMaterialId(materialId);
        if (quantity != null) {
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(400, "退回数量必须 > 0");
            }
            order.setQuantity(quantity);
        }
        if (unit != null) order.setUnit(unit);
        if (reason != null) order.setReason(reason);
        return returnOrderRepository.save(order);
    }

    @Override
    @Transactional
    public QualityReturnOrder confirm(String factoryId, String id, Long operatorId) {
        QualityReturnOrder order = get(factoryId, id);
        if (order.getStatus() != QualityReturnStatus.DRAFT) {
            throw new BusinessException(409, "仅草稿状态可确认")
                    .withHint("当前状态: " + order.getStatus());
        }
        order.setStatus(QualityReturnStatus.CONFIRMED);
        order.setConfirmedAt(LocalDateTime.now());
        order.setConfirmedBy(operatorId);
        QualityReturnOrder saved = returnOrderRepository.save(order);
        log.info("确认退回单: returnNumber={}, confirmedBy={}", saved.getReturnNumber(), operatorId);
        return saved;
    }

    @Override
    @Transactional
    public QualityReturnOrder ship(String factoryId, String id,
                                    String shippingTrackingNo, Long operatorId) {
        QualityReturnOrder order = get(factoryId, id);
        if (order.getStatus() != QualityReturnStatus.CONFIRMED) {
            throw new BusinessException(409, "仅 CONFIRMED 状态可发出")
                    .withHint("当前状态: " + order.getStatus());
        }
        if (shippingTrackingNo == null || shippingTrackingNo.trim().isEmpty()) {
            throw new BusinessException(400, "物流单号必填");
        }
        order.setStatus(QualityReturnStatus.SHIPPED);
        order.setShippedAt(LocalDateTime.now());
        order.setShippedBy(operatorId);
        order.setShippingTrackingNo(shippingTrackingNo.trim());
        QualityReturnOrder saved = returnOrderRepository.save(order);
        log.info("发出退回单: returnNumber={}, tracking={}, shippedBy={}",
                saved.getReturnNumber(), shippingTrackingNo, operatorId);
        return saved;
    }

    @Override
    @Transactional
    public void cancelDraft(String factoryId, String id) {
        QualityReturnOrder order = get(factoryId, id);
        if (order.getStatus() != QualityReturnStatus.DRAFT) {
            throw new BusinessException(409, "仅草稿状态可撤销 (已确认/已发出请走业务流程)");
        }
        order.softDelete();
        returnOrderRepository.save(order);
        log.info("撤销退回单草稿: returnNumber={}", order.getReturnNumber());
    }

    @Override
    @Transactional(readOnly = true)
    public QualityReturnOrder get(String factoryId, String id) {
        return returnOrderRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("退回单不存在或不属于当前工厂"));
    }

    @Override
    @Transactional(readOnly = true)
    @DataScope("created_by")  // Sprint 6 W2-B — RBAC 第 2 维 (数据权限) sweep
    public PageResponse<QualityReturnOrder> list(String factoryId,
                                                  QualityReturnStatus status,
                                                  QualityReturnTargetType targetType,
                                                  String qualityInspectionId,
                                                  String targetId,
                                                  LocalDateTime fromDate,
                                                  LocalDateTime toDate,
                                                  int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page - 1),
                Math.max(1, Math.min(size, 200)),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        // Sprint 6 W2-B: 应用 DataScope.
        DataScopeContext dsCtx = DataScopeContext.current();
        Page<QualityReturnOrder> result;
        if (dsCtx != null && dsCtx.isFiltered() && dsCtx.getUserId() != null) {
            com.cretas.aims.entity.enums.DataScope scope = dsCtx.getScope();
            java.util.List<Long> chain;
            if (scope == com.cretas.aims.entity.enums.DataScope.SELF) {
                chain = java.util.List.of(dsCtx.getUserId());
            } else if (scope == com.cretas.aims.entity.enums.DataScope.SELF_AND_BELOW
                    || scope == com.cretas.aims.entity.enums.DataScope.DEPT_AND_BELOW) {
                chain = dataScopeResolver != null
                        ? dataScopeResolver.resolveCreatedByChain(dsCtx)
                        : java.util.List.of(dsCtx.getUserId());
                if (chain == null || chain.isEmpty()) chain = java.util.List.of(dsCtx.getUserId());
            } else {
                chain = null;  // CUSTOM defer
            }
            if (chain != null) {
                log.debug("DataScope {} for quality returns: chain size={}", scope, chain.size());
                result = returnOrderRepository.findByFiltersAndCreatedByIn(
                        factoryId, status, targetType, qualityInspectionId, targetId,
                        fromDate, toDate, chain, pageable);
                return PageResponse.of(result.getContent(), result.getNumber() + 1,
                        result.getSize(), result.getTotalElements());
            }
        }

        result = returnOrderRepository.findByFilters(
                factoryId, status, targetType, qualityInspectionId, targetId,
                fromDate, toDate, pageable);
        return PageResponse.of(result.getContent(), result.getNumber() + 1,
                result.getSize(), result.getTotalElements());
    }

    /**
     * 生成 returnNumber: QR-YYYYMMDD-NNN.
     * 当日序号通过 repository.countByFactoryAndDay +1 得到.
     */
    private String generateReturnNumber(String factoryId) {
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDay = today.plusDays(1).atStartOfDay();
        long count = returnOrderRepository.countByFactoryAndDay(factoryId, dayStart, nextDay);
        return String.format("QR-%s-%03d", today.format(DATE_KEY), count + 1);
    }
}
