package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.FgReservationLedger;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.inventory.FgReservationLedgerRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 成品预留台账服务 —— 维护
 * <pre>batch.reserved_quantity == Σ(该批次 ACTIVE 台账行 reserved_qty)</pre>
 * 这一核心不变式。
 *
 * <p>写路径分工 (关键):
 * <ul>
 *   <li><b>{@link #reserve}</b> —— 预留 (财审): batch.reserved += qty <em>并</em> 建 ACTIVE 台账行。
 *       两者由本方法一并完成, 是 reserved 的<b>唯一新增入口</b>。</li>
 *   <li><b>{@link #syncReleaseOnShipment}</b> —— 发货 Pass2 <em>镜像</em>: 只削减台账行,
 *       <b>不动 batch.reserved</b> (调用方 {@code applyShipment} 已经在同一事务里把 reserved 减了)。
 *       仅负责让台账跟上, 维持不变式。</li>
 *   <li><b>{@link #releaseAllForOrder}</b> —— 取消/整单完成<em>清扫</em>: 台账行 <em>与</em>
 *       batch.reserved 一起削减。这是修"孤儿"的关键 (取消不释放 / Pass1 从别批发货导致预留批永不释放)。</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-07-06
 */
@Service
@RequiredArgsConstructor
public class FgReservationLedgerService {

    private static final Logger log = LoggerFactory.getLogger(FgReservationLedgerService.class);

    private final FgReservationLedgerRepository ledgerRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    /**
     * 预留 (财审触发)。batch.reserved += qty <b>并</b> 建 ACTIVE 台账行 —— 二者原子一致。
     *
     * @param batch 已加载的成品批次实体 (调用方在同一事务内持有, 直接 mutate 保证与 @Version 一致)
     */
    @Transactional
    public void reserve(String factoryId, String salesOrderId, String salesOrderItemId,
                        FinishedGoodsBatch batch, BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal current = batch.getReservedQuantity() != null
                ? batch.getReservedQuantity() : BigDecimal.ZERO;
        batch.setReservedQuantity(current.add(qty));
        finishedGoodsBatchRepository.save(batch);

        FgReservationLedger row = new FgReservationLedger();
        row.setFactoryId(factoryId);
        row.setSalesOrderId(salesOrderId);
        row.setSalesOrderItemId(salesOrderItemId);
        row.setFinishedGoodsBatchId(batch.getId());
        row.setProductTypeId(batch.getProductTypeId());
        row.setReservedQty(qty);
        row.setStatus(FgReservationLedger.Status.ACTIVE);
        ledgerRepository.save(row);

        log.debug("预留台账入账: SO={}, batch={}, qty={}", salesOrderId, batch.getBatchNumber(), qty);
    }

    /**
     * 发货 Pass2 镜像 —— <b>只削台账, 不动 batch.reserved</b> (applyShipment 已减)。
     *
     * <p>削减顺序: 先削本 SO 在该批的 ACTIVE 行 (正确归属); 若本 SO 在该批不足 {@code qty}
     * (罕见: 多 SO 共批 / 归属漂移), 再削该批其它 ACTIVE 行 —— 保证 Σ台账 与已减的 reserved 对齐,
     * 维持不变式。返回实际削减量 (通常 == qty)。
     */
    @Transactional
    public BigDecimal syncReleaseOnShipment(String salesOrderId, String batchId, BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal remaining = qty;
        // Pass A: 本 SO 的行 (悲观锁串行)
        remaining = reduceRows(ledgerRepository.lockActiveBySalesOrderAndBatch(salesOrderId, batchId), remaining);
        // Pass B: 若还有剩 (本 SO 在该批不足), 削该批其它 ACTIVE 行, 兜底维持不变式
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            remaining = reduceRows(ledgerRepository.lockActiveByBatch(batchId), remaining);
        }
        return qty.subtract(remaining);
    }

    /**
     * 整单清扫 (取消 / 完成) —— 释放某 SO 的<b>全部</b> ACTIVE 台账行, <b>并</b> 相应削减
     * 各自批次的 reserved。修孤儿的核心入口。
     *
     * @return 释放的总量 (跨批次求和)
     */
    @Transactional
    public BigDecimal releaseAllForOrder(String salesOrderId) {
        List<FgReservationLedger> rows = ledgerRepository.lockActiveBySalesOrderId(salesOrderId);
        BigDecimal released = BigDecimal.ZERO;
        for (FgReservationLedger row : rows) {
            BigDecimal q = row.getReservedQty() != null ? row.getReservedQty() : BigDecimal.ZERO;
            if (q.compareTo(BigDecimal.ZERO) > 0) {
                decrementBatchReserved(row.getFinishedGoodsBatchId(), q);
                released = released.add(q);
            }
            row.setStatus(FgReservationLedger.Status.RELEASED);
            row.setReservedQty(BigDecimal.ZERO);
            ledgerRepository.save(row);
        }
        if (released.compareTo(BigDecimal.ZERO) > 0) {
            log.info("整单预留清扫: SO={}, 释放行数={}, 释放总量={}", salesOrderId, rows.size(), released);
        }
        return released;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** 从给定 ACTIVE 行列表中削减 remaining, 削尽的行置 RELEASED。返回未削完的余量。 */
    private BigDecimal reduceRows(List<FgReservationLedger> rows, BigDecimal remaining) {
        for (FgReservationLedger row : rows) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal rowQty = row.getReservedQty() != null ? row.getReservedQty() : BigDecimal.ZERO;
            if (rowQty.compareTo(BigDecimal.ZERO) <= 0) {
                row.setStatus(FgReservationLedger.Status.RELEASED);
                ledgerRepository.save(row);
                continue;
            }
            BigDecimal take = rowQty.min(remaining);
            BigDecimal left = rowQty.subtract(take);
            row.setReservedQty(left);
            if (left.compareTo(BigDecimal.ZERO) <= 0) {
                row.setStatus(FgReservationLedger.Status.RELEASED);
            }
            ledgerRepository.save(row);
            remaining = remaining.subtract(take);
        }
        return remaining;
    }

    /** batch.reserved -= q (守 >= 0)。 */
    private void decrementBatchReserved(String batchId, BigDecimal q) {
        FinishedGoodsBatch batch = finishedGoodsBatchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            log.warn("预留清扫: 批次不存在, 跳过 reserved 削减: batchId={}", batchId);
            return;
        }
        BigDecimal current = batch.getReservedQuantity() != null
                ? batch.getReservedQuantity() : BigDecimal.ZERO;
        // 释放预留只会增大 available (不会 deplete), 故不改 status。
        batch.setReservedQuantity(current.subtract(q).max(BigDecimal.ZERO));
        finishedGoodsBatchRepository.save(batch);
    }
}
