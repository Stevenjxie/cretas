package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface TransferService {

    InternalTransfer createTransfer(String factoryId, CreateTransferRequest request, Long userId);

    /**
     * Factory-scoped lookup — only returns the transfer if factoryId is its source or target.
     * Prevents cross-tenant data leakage when a user knows a transferId.
     */
    InternalTransfer getTransferById(String factoryId, String transferId);

    /** 双向视角：调出或调入都能查到 */
    PageResponse<InternalTransfer> getTransfers(String factoryId, int page, int size);

    /** 状态机流转 — 全部强制 factoryId 隔离 (写路径漏洞比读路径更严重 — Apr 7 audit) */
    InternalTransfer requestTransfer(String factoryId, String transferId, Long userId);

    InternalTransfer approveTransfer(String factoryId, String transferId, Long userId);

    InternalTransfer rejectTransfer(String factoryId, String transferId, Long userId, String reason);

    /**
     * 统一 OA 审批中心的调拨领域动作适配器。
     * 业务详情页及 AI 工具不得直接调用 approveTransfer/rejectTransfer。
     */
    InternalTransfer applyWorkflowAction(String factoryId,
                                         String transferId,
                                         String instanceId,
                                         Long actorId,
                                         String actorRole,
                                         HistoryAction action,
                                         String notes);

    InternalTransfer shipTransfer(String factoryId, String transferId, Long userId);

    InternalTransfer receiveTransfer(String factoryId, String transferId, Long userId);

    /**
     * BUG-3 修复: 签收时可传入各 item 实收量 (itemId → actualQuantity)。
     * null = 向后兼容, 全部回退为发货量。
     */
    InternalTransfer receiveTransfer(String factoryId, String transferId, Long userId,
                                     Map<Long, BigDecimal> itemActualQuantities);

    InternalTransfer confirmTransfer(String factoryId, String transferId, Long userId);

    InternalTransfer cancelTransfer(String factoryId, String transferId, Long userId, String reason);

    Map<String, Object> getTransferStatistics(String factoryId);

    // ==================== B1 两阶段批次选择 (PR #309 B1=C, 2026-05-11) ====================

    /**
     * B1: 列出某 transfer item 在 source warehouse 当前可用批次, 用于 SHIP 前选批次 dropdown.
     */
    List<Map<String, Object>> getAvailableBatchesForItem(String factoryId, String transferId, Long itemId);

    /**
     * B1: 更新 transfer item 的 sourceBatchId (用户在 SHIP 前选批次). null = 清除预选, 走 FEFO.
     * 仅 source factory + status=APPROVED 时允许.
     */
    InternalTransferItem updateItemSourceBatch(String factoryId, String transferId,
                                                Long itemId, String sourceBatchId);
}
