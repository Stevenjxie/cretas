package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.TransferDiffDecision;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.TransferDiffRecord;

import java.util.List;

/**
 * 调拨差异找单服务
 *
 * <p>签收时 实收量 < 发货量 → 自动生成差异记录，追踪找单流程。
 *
 * @since 2026-06-11
 */
public interface TransferDiffService {

    /**
     * 签收后自动检测差异，有差异则生成 TransferDiffRecord。
     *
     * <p>无差异（全部 receivedQuantity = quantity）时不产生记录，返回空列表。
     * 已有差异记录则幂等跳过（同一 transferItemId 已有 PENDING 记录）。
     *
     * @param transfer  已完成签收（status=RECEIVED）的调拨单，items 必须已写入 receivedQuantity
     * @param receivedBy 签收操作人 userId
     * @return 生成的差异记录列表，无差异时空列表
     */
    List<TransferDiffRecord> detectAndGenerateDiffs(InternalTransfer transfer, Long receivedBy);

    /**
     * 对差异单做找单决策：找回/核销/转报损。
     *
     * <p>REQUIRES_NEW 隔离：若 TRANSFER_DAMAGE 分支触发报损创建失败，
     * 不 doom 差异单本身的状态变更。
     *
     * @param diffRecordId 差异单 ID
     * @param factoryId    操作方工厂（source 或 target 均可决策，校验）
     * @param decision     决策枚举
     * @param notes        备注（可 null）
     * @param decisionBy   决策人 userId
     * @return 更新后的差异单
     */
    TransferDiffRecord decideRecord(
            String diffRecordId,
            String factoryId,
            TransferDiffDecision decision,
            String notes,
            Long decisionBy);

    /**
     * 查询工厂的差异单列表（双向：source 或 target 均可查）。
     *
     * @param factoryId 当前工厂
     * @param status    可 null = 全部；PENDING / RESOLVED
     */
    List<TransferDiffRecord> listDiffs(String factoryId, String status);

    /**
     * 查询调拨单关联的差异单。
     */
    List<TransferDiffRecord> listDiffsByTransfer(String factoryId, String transferId);
}
