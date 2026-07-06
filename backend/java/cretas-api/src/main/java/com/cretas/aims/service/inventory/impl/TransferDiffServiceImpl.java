package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.TransferDiffDecision;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.entity.inventory.TransferDiffRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.TransferDiffRecordRepository;
import com.cretas.aims.service.inventory.TransferDiffService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 调拨差异找单服务实现
 *
 * <p>复用 PurchaseExceptionServiceImpl (SP6) 同构模式：
 * - generateExceptionsForReceive → detectAndGenerateDiffs
 * - decideException → decideRecord (REQUIRES_NEW 隔离)
 *
 * <p>注意：decideRecord 用 REQUIRES_NEW，防止 TRANSFER_DAMAGE 分支外部
 * 调用失败 doom 本事务（参见 feedback_failsoft_catch_cannot_save_doomed_tx）。
 *
 * @since 2026-06-11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferDiffServiceImpl implements TransferDiffService {

    /** 可代为决策的主管角色（预留，当前差异单无责任人绑定，任意工厂成员均可决策） */
    private static final Set<String> SUPERVISOR_ROLES = Set.of(
            "factory_super_admin", "warehouse_manager", "procurement_manager");

    private final TransferDiffRecordRepository diffRepository;

    // ─── detectAndGenerateDiffs ──────────────────────────────────────────

    @Override
    @Transactional
    public List<TransferDiffRecord> detectAndGenerateDiffs(InternalTransfer transfer, Long receivedBy) {
        List<TransferDiffRecord> result = new ArrayList<>();

        if (transfer == null || transfer.getItems() == null || transfer.getItems().isEmpty()) {
            return result;
        }

        for (InternalTransferItem item : transfer.getItems()) {
            BigDecimal shipped = item.getQuantity();
            BigDecimal received = item.getReceivedQuantity();

            // null-safe: receivedQuantity 未填时视为与发货量一致（无差异）
            if (shipped == null || received == null) continue;

            int cmp = received.compareTo(shipped);

            // 恰好相等 — 无差异
            if (cmp == 0) continue;

            if (cmp > 0) {
                // 🔒 库存完整性修复 (2026-07-06): 多收方向。receiveTransfer 已在签收入口对
                // receivedQuantity 做 shipped × (1 + tolerance) 硬上限校验
                // (见 TransferServiceImpl#validateReceivedNotExceedingShipped)，理论上不可能
                // 出现明显超出容忍范围的多收到达这里；能到这里的只会是容忍范围内的称重误差
                // (默认 ≤2%)，或历史上未经该守卫写入的数据。
                //
                // 不生成 TransferDiffRecord: 现有 TransferDiffDecision
                // (FOUND_BACK=找回货物 / WRITE_OFF_LOSS=核销损耗 / TRANSFER_DAMAGE=转报损)
                // 三个决策全部是"少收"语义，对"多收"场景没有一个说得通（多收不需要"找回"，也没有
                // "损耗"或"报损"）。引入专门的多收决策类型属于独立 feature 设计范畴（新决策枚举 +
                // 前端交互 + 业务口径确认），不在本次幽灵库存 hotfix 的 scope 内。这里保留防御性
                // 日志，让多收方向至少可审计、不被静默吞掉，而不是勉强塞进不匹配的决策工作流。
                log.warn("[TDIFF] 调拨 {} item={} 实收({}) 大于发运({}) — 差值 {}{}，"
                                + "未生成差异单(现有决策枚举语义仅适用少收方向，见 TransferDiffDecision)",
                        transfer.getTransferNumber(), item.getItemName(), received, shipped,
                        received.subtract(shipped).stripTrailingZeros().toPlainString(),
                        item.getUnit() != null ? " " + item.getUnit() : "");
                continue;
            }

            BigDecimal diffQty = shipped.subtract(received); // > 0

            // 幂等：同一 transferItemId 已有 PENDING 记录则跳过
            List<TransferDiffRecord> existing = diffRepository.findByTransferIdOrderByCreatedAtDesc(transfer.getId());
            boolean alreadyExists = existing.stream().anyMatch(d ->
                    item.getId() != null && item.getId().equals(d.getTransferItemId())
                            && "PENDING".equals(d.getStatus()));
            if (alreadyExists) {
                log.info("[TDIFF] 调拨差异已存在(幂等跳过): transferId={}, itemId={}", transfer.getId(), item.getId());
                continue;
            }

            TransferDiffRecord rec = new TransferDiffRecord();
            rec.setSourceFactoryId(transfer.getSourceFactoryId());
            rec.setTargetFactoryId(transfer.getTargetFactoryId());
            rec.setDiffNumber(generateDiffNumber(transfer.getSourceFactoryId()));
            rec.setTransferId(transfer.getId());
            rec.setTransferNumber(transfer.getTransferNumber());
            rec.setTransferItemId(item.getId());
            rec.setItemName(item.getItemName());
            rec.setMaterialTypeId(item.getMaterialTypeId());
            rec.setProductTypeId(item.getProductTypeId());
            rec.setShippedQuantity(shipped);
            rec.setReceivedQuantity(received);
            rec.setDiffQuantity(diffQty);
            rec.setUnit(item.getUnit());
            rec.setStatus("PENDING");
            rec.setCreatedBy(receivedBy);

            TransferDiffRecord saved = diffRepository.save(rec);
            log.info("[TDIFF] 生成调拨差异单 {} 调拨单={} item={} 发货={} 实收={} 差异={}{}",
                    saved.getDiffNumber(), transfer.getTransferNumber(),
                    item.getItemName(), shipped, received, diffQty,
                    item.getUnit() != null ? " " + item.getUnit() : "");
            result.add(saved);
        }

        return result;
    }

    // ─── decideRecord ────────────────────────────────────────────────────

    /**
     * REQUIRES_NEW：若未来 TRANSFER_DAMAGE 分支触发报损创建失败，
     * 不 doom 本差异单的状态变更事务。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransferDiffRecord decideRecord(
            String diffRecordId,
            String factoryId,
            TransferDiffDecision decision,
            String notes,
            Long decisionBy) {

        TransferDiffRecord rec = diffRepository.findById(diffRecordId)
                .orElseThrow(() -> new BusinessException("调拨差异单不存在: " + diffRecordId));

        // 工厂隔离：调出方或调入方均可决策
        if (!factoryId.equals(rec.getSourceFactoryId()) && !factoryId.equals(rec.getTargetFactoryId())) {
            throw new BusinessException(403, "无权操作此调拨差异单（当前工厂: " + factoryId + "）");
        }

        if (!"PENDING".equals(rec.getStatus())) {
            throw new BusinessException(409, "调拨差异单已处理（status=" + rec.getStatus() + "），无法再次决策")
                    .withHint("请刷新查看最新状态");
        }

        rec.setDecision(decision);
        rec.setDecisionBy(decisionBy);
        rec.setDecisionAt(LocalDateTime.now());
        rec.setDecisionNotes(notes);
        rec.setStatus("RESOLVED");

        TransferDiffRecord saved = diffRepository.save(rec);
        log.info("[TDIFF] 差异单 {} 决策={} by userId={}", rec.getDiffNumber(), decision, decisionBy);
        return saved;
    }

    // ─── listDiffs ───────────────────────────────────────────────────────

    @Override
    public List<TransferDiffRecord> listDiffs(String factoryId, String status) {
        return diffRepository.findByEitherFactoryAndStatus(factoryId,
                (status == null || status.isBlank()) ? null : status);
    }

    @Override
    public List<TransferDiffRecord> listDiffsByTransfer(String factoryId, String transferId) {
        // 先验 factoryId 权限（该 transfer 必须是本工厂的 source 或 target）
        List<TransferDiffRecord> records = diffRepository.findByTransferIdOrderByCreatedAtDesc(transferId);
        // 过滤：只返回本工厂可见的（source 或 target）
        return records.stream()
                .filter(r -> factoryId.equals(r.getSourceFactoryId()) || factoryId.equals(r.getTargetFactoryId()))
                .toList();
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private String generateDiffNumber(String factoryId) {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = diffRepository.countPendingBySourceFactory(factoryId) + 1;
        return String.format("TDIFF-%s-%s-%04d", factoryId, date, seq);
    }
}
