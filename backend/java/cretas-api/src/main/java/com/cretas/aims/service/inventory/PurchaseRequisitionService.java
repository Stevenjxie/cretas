package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreatePurchaseRequisitionRequest;
import com.cretas.aims.entity.enums.PurchaseRequisitionStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseRequisition;

/**
 * 请购单 Service — Sprint 5 Track D (P-REQUISITION-1).
 *
 * <p>State machine:
 * <pre>
 *   submitRequisition: DRAFT → PENDING_APPROVAL
 *   approveRequisition: PENDING_APPROVAL → APPROVED
 *   rejectRequisition: PENDING_APPROVAL → REJECTED
 *   convertToPO: APPROVED → CONVERTED_TO_PO (creates PurchaseOrder, sets convertedPoId linkno)
 * </pre>
 *
 * <p>convertToPO 复用 {@link PurchaseService#createPurchaseOrder} — 不重写采购单创建逻辑.
 *
 * @since 2026-05-19
 */
public interface PurchaseRequisitionService {

    /**
     * 创建请购单草稿 (status=DRAFT).
     *
     * @param factoryId 工厂 ID (URL path)
     * @param requesterId 请购人 userId (from JWT)
     * @param request 请购明细
     * @return 已保存的请购单 (含生成的 id + requisitionNumber)
     */
    PurchaseRequisition createRequisition(String factoryId, Long requesterId, CreatePurchaseRequisitionRequest request);

    /**
     * 提交审批 (DRAFT → PENDING_APPROVAL).
     *
     * @throws com.cretas.aims.exception.BusinessException 400 if 非 DRAFT 状态
     * @throws com.cretas.aims.exception.BusinessException 403 if 跨工厂或非 requester
     */
    PurchaseRequisition submitRequisition(String factoryId, String requisitionId, Long currentUserId);

    /**
     * 审批通过 (PENDING_APPROVAL → APPROVED).
     *
     * @param approverId 审批人 userId (from JWT)
     * @throws com.cretas.aims.exception.BusinessException 400 if 非 PENDING_APPROVAL 状态
     */
    PurchaseRequisition approveRequisition(String factoryId, String requisitionId, Long approverId);

    /**
     * 审批驳回 (PENDING_APPROVAL → REJECTED).
     *
     * @param rejectionReason 驳回原因, 必填.
     */
    PurchaseRequisition rejectRequisition(String factoryId, String requisitionId, Long approverId, String rejectionReason);

    /**
     * 自动转 PO (APPROVED → CONVERTED_TO_PO).
     *
     * <p>读 requestedItems → 按 suggestedSupplierId 分组 (或全部用第一行的) → 调
     * {@link PurchaseService#createPurchaseOrder} 创建 PO → 把 PO.id 写回
     * requisition.convertedPoId (linkno).
     *
     * <p>MVP 实现: 假设一张请购单全部来自同一 supplierId (UI 限制).
     * Sprint 6 可扩多 supplier 拆多 PO.
     *
     * @param userId 转单操作人 (用于 PO.createdBy)
     * @param supplierId 供应商 ID (必填, 转单时 UI 让操作人选)
     * @return 新创建的 PurchaseOrder
     */
    PurchaseOrder convertToPO(String factoryId, String requisitionId, Long userId, String supplierId);

    /**
     * 按 id 查询.
     */
    PurchaseRequisition getById(String factoryId, String requisitionId);

    /**
     * 分页列表 — 可按 status / requester 过滤.
     */
    PageResponse<PurchaseRequisition> list(String factoryId, PurchaseRequisitionStatus status,
                                            Long requesterId, int page, int size);
}
