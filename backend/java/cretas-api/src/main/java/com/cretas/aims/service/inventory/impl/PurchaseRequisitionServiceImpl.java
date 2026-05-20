package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.CreatePurchaseRequisitionRequest;
import com.cretas.aims.entity.enums.PurchaseRequisitionStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseRequisition;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.inventory.PurchaseRequisitionRepository;
import com.cretas.aims.service.inventory.PurchaseRequisitionService;
import com.cretas.aims.service.inventory.PurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 请购单 Service 实现 — Sprint 5 Track D.
 *
 * <p>convertToPO 通过组装 {@link CreatePurchaseOrderRequest} 复用
 * {@link PurchaseService#createPurchaseOrder} — 不重写采购单创建逻辑.
 *
 * @since 2026-05-19
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseRequisitionServiceImpl implements PurchaseRequisitionService {

    private final PurchaseRequisitionRepository requisitionRepository;
    private final PurchaseService purchaseService;

    @Override
    @Transactional
    public PurchaseRequisition createRequisition(String factoryId, Long requesterId,
                                                  CreatePurchaseRequisitionRequest request) {
        if (request.getRequestedItems() == null || request.getRequestedItems().isEmpty()) {
            throw new BusinessException(400, "请购行项目不能为空")
                    .withHint("请添加至少 1 行请购物料");
        }
        PurchaseRequisition pr = new PurchaseRequisition();
        pr.setFactoryId(factoryId);
        pr.setRequisitionNumber(generateRequisitionNumber(factoryId));
        pr.setRequesterId(requesterId);
        pr.setRequesterDeptId(request.getRequesterDeptId());
        pr.setRequestedItems(new ArrayList<>(request.getRequestedItems()));
        pr.setStatus(PurchaseRequisitionStatus.DRAFT);
        pr.setExpectedDate(request.getExpectedDate());
        pr.setReason(request.getReason());
        pr.setRemark(request.getRemark());
        pr = requisitionRepository.save(pr);
        log.info("创建请购单: factoryId={}, requisitionNumber={}, items={}",
                factoryId, pr.getRequisitionNumber(), pr.getRequestedItems().size());
        return pr;
    }

    @Override
    @Transactional
    public PurchaseRequisition submitRequisition(String factoryId, String requisitionId, Long currentUserId) {
        PurchaseRequisition pr = loadAndCheckFactory(factoryId, requisitionId);
        // 仅 requester 本人可提审 (MVP — 后续扩 delegate 场景)
        if (!pr.getRequesterId().equals(currentUserId)) {
            throw new BusinessException(403, "仅请购人本人可提交审批")
                    .withHint("当前用户非请购人, 无法提交此请购单审批");
        }
        transitionStatus(pr, PurchaseRequisitionStatus.PENDING_APPROVAL);
        return requisitionRepository.save(pr);
    }

    @Override
    @Transactional
    public PurchaseRequisition approveRequisition(String factoryId, String requisitionId, Long approverId) {
        PurchaseRequisition pr = loadAndCheckFactory(factoryId, requisitionId);
        transitionStatus(pr, PurchaseRequisitionStatus.APPROVED);
        pr.setApproverId(approverId);
        pr.setApprovedAt(LocalDateTime.now());
        log.info("审批通过请购单: id={}, approverId={}", requisitionId, approverId);
        return requisitionRepository.save(pr);
    }

    @Override
    @Transactional
    public PurchaseRequisition rejectRequisition(String factoryId, String requisitionId,
                                                  Long approverId, String rejectionReason) {
        if (rejectionReason == null || rejectionReason.isBlank()) {
            throw new BusinessException(400, "驳回原因不能为空")
                    .withHint("请填写驳回原因, 以便请购人了解后整改");
        }
        PurchaseRequisition pr = loadAndCheckFactory(factoryId, requisitionId);
        transitionStatus(pr, PurchaseRequisitionStatus.REJECTED);
        pr.setApproverId(approverId);
        pr.setApprovedAt(LocalDateTime.now());
        pr.setRejectionReason(rejectionReason);
        log.info("驳回请购单: id={}, approverId={}, reason={}", requisitionId, approverId, rejectionReason);
        return requisitionRepository.save(pr);
    }

    @Override
    @Transactional
    public PurchaseOrder convertToPO(String factoryId, String requisitionId, Long userId, String supplierId) {
        if (supplierId == null || supplierId.isBlank()) {
            throw new BusinessException(400, "供应商不能为空")
                    .withHint("请先选择供应商再确认转单");
        }
        PurchaseRequisition pr = loadAndCheckFactory(factoryId, requisitionId);
        // 幂等防重: 如果已转单, 直接返已有 PO (而非再次创建)
        if (pr.getStatus() == PurchaseRequisitionStatus.CONVERTED_TO_PO && pr.getConvertedPoId() != null) {
            log.info("请购单已转单, 返已有 PO: requisitionId={}, poId={}", requisitionId, pr.getConvertedPoId());
            return purchaseService.getPurchaseOrderById(factoryId, pr.getConvertedPoId());
        }
        transitionStatus(pr, PurchaseRequisitionStatus.CONVERTED_TO_PO);

        // 组 createPurchaseOrder 请求 — 复用 PurchaseService 逻辑
        CreatePurchaseOrderRequest poRequest = new CreatePurchaseOrderRequest();
        poRequest.setSupplierId(supplierId);
        poRequest.setOrderDate(LocalDate.now());
        poRequest.setExpectedDeliveryDate(pr.getExpectedDate());
        poRequest.setRemark("从请购单转 — " + pr.getRequisitionNumber()
                + (pr.getRemark() == null ? "" : " | " + pr.getRemark()));
        poRequest.setItems(mapItemsToPoItems(pr.getRequestedItems()));

        PurchaseOrder newPo = purchaseService.createPurchaseOrder(factoryId, poRequest, userId);

        pr.setConvertedPoId(newPo.getId());
        pr.setConvertedAt(LocalDateTime.now());
        requisitionRepository.save(pr);

        log.info("请购单转 PO 成功: requisitionId={}, poId={}, poNumber={}",
                requisitionId, newPo.getId(), newPo.getOrderNumber());
        return newPo;
    }

    @Override
    @Transactional
    public void deleteDraft(String factoryId, String requisitionId, Long currentUserId) {
        PurchaseRequisition pr = loadAndCheckFactory(factoryId, requisitionId);
        // 状态机约束: 仅 DRAFT 可删 (Bug #7 — 避免 "提交+驳回" workaround 产生虚假审批历史)
        if (pr.getStatus() != PurchaseRequisitionStatus.DRAFT) {
            throw new BusinessException(400,
                    "仅 DRAFT 状态可删除 (当前状态: " + pr.getStatus().getDisplayName() + ")")
                    .withHint("如需取消已提交的请购单, 请使用 reject (驳回) 操作");
        }
        // 仅 requester 本人可删 (MVP — 一致于 submitRequisition)
        if (!pr.getRequesterId().equals(currentUserId)) {
            throw new BusinessException(403, "仅请购人本人可删除草稿")
                    .withHint("当前用户非请购人, 无法删除此请购单");
        }
        // 软删除 via BaseEntity @SQLDelete (UPDATE deleted_at = NOW())
        requisitionRepository.delete(pr);
        log.info("删除请购单草稿: requisitionId={}, requisitionNumber={}, requesterId={}",
                requisitionId, pr.getRequisitionNumber(), currentUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseRequisition getById(String factoryId, String requisitionId) {
        return loadAndCheckFactory(factoryId, requisitionId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PurchaseRequisition> list(String factoryId, PurchaseRequisitionStatus status,
                                                   Long requesterId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PurchaseRequisition> result;
        if (status != null) {
            result = requisitionRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(factoryId, status, pageRequest);
        } else if (requesterId != null) {
            result = requisitionRepository.findByFactoryIdAndRequesterIdOrderByCreatedAtDesc(factoryId, requesterId, pageRequest);
        } else {
            result = requisitionRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageRequest);
        }
        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    // ==================== Private helpers ====================

    private PurchaseRequisition loadAndCheckFactory(String factoryId, String requisitionId) {
        PurchaseRequisition pr = requisitionRepository.findById(requisitionId)
                .orElseThrow(() -> new ResourceNotFoundException("请购单不存在: " + requisitionId));
        if (!pr.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权访问该请购单")
                    .withHint("当前请购单不属于该工厂, 无法访问");
        }
        return pr;
    }

    private void transitionStatus(PurchaseRequisition pr, PurchaseRequisitionStatus target) {
        try {
            pr.assertCanTransitionTo(target);
        } catch (IllegalStateException e) {
            throw new BusinessException(400, e.getMessage())
                    .withHint("请购单状态非法, 请刷新后重试");
        }
        pr.setStatus(target);
    }

    private String generateRequisitionNumber(String factoryId) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = requisitionRepository.countByFactoryIdAndDate(factoryId, LocalDate.now());
        return String.format("PR-%s-%03d", today, count + 1);
    }

    /**
     * 把 PR.requestedItems (JSONB Map) → PO items DTO list.
     * 防呆: 跳过 quantity null/0 行.
     */
    private List<CreatePurchaseOrderRequest.PurchaseOrderItemDTO> mapItemsToPoItems(
            List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(400, "请购单无可转 PO 的行项目")
                    .withHint("请购单 requestedItems 为空");
        }
        List<CreatePurchaseOrderRequest.PurchaseOrderItemDTO> result = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            String materialTypeId = stringOrNull(item.get("materialTypeId"));
            if (materialTypeId == null || materialTypeId.isBlank()) {
                throw new BusinessException(400, "请购行 materialTypeId 缺失")
                        .withHint("请检查请购单行项目数据完整性");
            }
            BigDecimal quantity = toBigDecimal(item.get("quantity"));
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) continue;
            String unit = stringOrNull(item.get("unit"));
            if (unit == null || unit.isBlank()) unit = "件";  // 默认单位兜底

            CreatePurchaseOrderRequest.PurchaseOrderItemDTO dto =
                    new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
            dto.setMaterialTypeId(materialTypeId);
            dto.setMaterialName(stringOrNull(item.get("materialName")));
            dto.setQuantity(quantity);
            dto.setUnit(unit);
            dto.setUnitPrice(toBigDecimal(item.get("unitPrice")));
            dto.setTaxRate(toBigDecimal(item.get("taxRate")));
            dto.setRemark(stringOrNull(item.get("remark")));
            dto.setSpecification(stringOrNull(item.get("specification")));
            result.add(dto);
        }
        if (result.isEmpty()) {
            throw new BusinessException(400, "请购单无有效行项目 (数量必须 > 0)")
                    .withHint("请检查请购单数量字段");
        }
        return result;
    }

    private static String stringOrNull(Object v) {
        return v == null ? null : v.toString();
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
