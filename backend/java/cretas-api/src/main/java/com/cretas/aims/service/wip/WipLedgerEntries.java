package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workprocess.WorkProcessTask;

import java.math.BigDecimal;

/**
 * 半成品流水行的构造器（纯函数，不碰仓储、不依赖调用顺序）。
 *
 * <h2>为什么单独抽出来</h2>
 *
 * <p>2026-08-17 F006 生产端到端走查实测：整条链走通后
 * {@code semi_finished_inventory_transactions} 里<b>只有 1 行 IN，一行 OUT 都没有</b>，
 * 而那一轮发生过<b>两次领用</b>。余额确实扣了，但流水查不到是谁领走的
 * ⇒ 半成品<b>只有进账没有出账</b>，对不了账、追不到去向。
 *
 * <p>这不是设计遗漏：{@link SemiFinishedInventoryTransaction.TxnType#OUT} 的语义
 * （「下道领用 / 二次加工，OUT: 负数量」）和
 * {@link SemiFinishedInventoryTransaction.SourceType#SECONDARY_CONSUME}
 * 早就写在实体里了，<b>只是没有任何地方写过 OUT 行</b>——机制在、契约在、没接上。
 *
 * <p>做成<b>纯函数</b>是有意的：余额快照由「扣减前状态 + 本次领用量」算出，
 * ⛔ 不读扣减后的实体字段。这样它不依赖「先改余额还是先记流水」的调用顺序，
 * 也就能被单独断言，不需要把整个 service 搭起来。
 */
public final class WipLedgerEntries {

    private WipLedgerEntries() {
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 构造一条「下道领用」的 OUT 流水行。
     *
     * @param sfi        被领用的半成品库存行（<b>扣减前</b>的状态）
     * @param input      本次领用量（正数）；{@code null} 或 {@code <= 0} 返回 {@code null}
     * @param report     触发领用的那条报工
     * @param task       领用方工序任务（下道）
     * @param operatorId 操作人
     * @return OUT 流水行；无需记账时返回 {@code null}
     */
    public static SemiFinishedInventoryTransaction consumptionRow(
            SemiFinishedInventory sfi, BigDecimal input,
            ProductionReport report, WorkProcessTask task, Long operatorId) {

        if (sfi == null || report == null || input == null
                || input.compareTo(BigDecimal.ZERO) <= 0) {
            return null;   // ⛔ 领 0 不凭空造流水
        }

        // 余额快照：由扣减前状态推出，与 consumeSourceWip 同口径。
        BigDecimal balanceAfter = nz(sfi.getProducedQuantity())
                .subtract(nz(sfi.getConsumedQuantity()).add(input))
                .add(nz(sfi.getAdjustmentQuantity()));

        // sourceRef 按实体 javadoc：「OUT → 下道 intermediateBatchNo 或 transferId」。
        // 这里领用方是工序任务，用 task id 定位得到「被谁领走」。
        String sourceRef = task == null || task.getId() == null
                ? sfi.getIntermediateBatchNo()
                : "TASK-" + task.getId();

        return SemiFinishedInventoryTransaction.builder()
                .factoryId(report.getFactoryId())
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                .sourceRef(sourceRef)
                .quantity(input.negate())          // 按 javadoc：OUT 为负数量
                .unitCostAtTxn(sfi.getUnitCost())  // 诚实 null 传播：没有均价就不编一个
                .balanceAfter(balanceAfter)
                .balanceCostAfter(sfi.getUnitCost())
                .reportId(report.getId())
                .operatorId(operatorId)
                .build();
    }

    /**
     * 构造一条「本道产出入库」的 IN 流水行。
     *
     * <p><b>为什么补这一条</b>：2026-08-18 查 F006 生产库实测，
     * {@code semi_finished_inventory_transactions} 全表 8 行里
     * <b>IN 一行都没有</b>（4 行 OUT + 4 行 REVERSE），
     * 而 {@code semi_finished_inventory} 的 4 行各自挂着 2.00 / 240.00 / 300.00 / 10.00 的
     * {@code produced_quantity}。⇒ 余额<b>凭空出现</b>，流水账解释不了它是怎么来的。
     *
     * <p>最刺眼的是 id=344 那行：{@code produced=10.00}，流水里有一条 {@code REVERSE -10}，
     * 而 {@code IN} 是 <b>0 行</b> —— <b>冲销了一笔从来没记过账的入库</b>。
     *
     * <p>后果不止对不上账：{@code ReportReversalServiceImpl.replayMovingAverage} 把
     * 「ΣIN + ΣREVERSE」当成净产出<b>回写</b>库存行，一旦净额 ≤ 0 就把
     * produced / available 一并清零并置 DEPLETED。流水账缺 IN 行时，
     * 这等于<b>拿「我没有证据」当「余额是 0」</b>，把真实库存抹掉。
     *
     * <p>⚠️ <b>余额口径与 {@link #consumptionRow} 相反</b>，与 {@link #reversalRow} 相同：
     * 本方法<b>直接读改完的</b> {@code availableQuantity}，要求调用方
     * <b>先把库存行累加完再调</b>。两种口径各自都对，混起来必错
     * （2026-08-17 就是把构造点放错边，生产上落过一条 {@code balance_after = -2.000000}）。
     *
     * @param sfi        <b>已经累加完</b>的半成品库存行
     * @param inQty      本次产出入库量（正数）；{@code null} 或 {@code <= 0} 返回 {@code null}
     * @param inUnitCost 本次产出的单位成本；🔴 诚实 null 传播 —— 算不出就传 {@code null}，⛔ 不要传 0
     * @param report     产生这笔入库的那条报工
     * @param sourceRef  定位串，按实体 javadoc：IN → {@code intermediateBatchNo}
     * @return IN 流水行；无需记账时返回 {@code null}
     */
    public static SemiFinishedInventoryTransaction productionRow(
            SemiFinishedInventory sfi, BigDecimal inQty, BigDecimal inUnitCost,
            ProductionReport report, String sourceRef, Long operatorId) {

        if (sfi == null || report == null || inQty == null
                || inQty.compareTo(BigDecimal.ZERO) <= 0) {
            return null;   // ⛔ 产出 0 不凭空造流水
        }

        return SemiFinishedInventoryTransaction.builder()
                .factoryId(report.getFactoryId())
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                .sourceRef(sourceRef)
                .quantity(inQty)                    // 按 javadoc：IN 为正数量
                .unitCostAtTxn(inUnitCost)          // 诚实 null 传播：没有成本就不编一个
                .balanceAfter(nz(sfi.getAvailableQuantity()))
                .balanceCostAfter(sfi.getUnitCost())
                .reportId(report.getId())
                .operatorId(operatorId)
                .build();
    }

    /**
     * 构造一条<b>冲销</b>流水行（驳回 / 修正一条报工时用）。
     *
     * <p>⚠️ 与 {@link #consumptionRow} 的余额口径<b>相反</b>：那一条由「扣减前」状态推出余额；
     * 这一条要求调用方<b>先把库存行改完再调</b>，直接读改完的 {@code availableQuantity}。
     * 两种口径各自都对，混起来必错 —— 2026-08-17 就是把构造点放错边，
     * 算出过 {@code balanceAfter = -2.000000}。所以这里<b>不自己推</b>，只读结果。
     *
     * @param sfi        <b>已经改完</b>的半成品库存行
     * @param signedQty  带符号的冲销量：退回产出为负、还回领用为正
     * @param report     被冲销的那条报工
     * @param sourceRef  定位串，如 {@code "REVERSE-REPORT-23814"}
     * @return 冲销流水行；{@code signedQty} 为 0 时返回 {@code null}（⛔ 不凭空造流水）
     */
    public static SemiFinishedInventoryTransaction reversalRow(
            SemiFinishedInventory sfi, BigDecimal signedQty,
            ProductionReport report, String sourceRef, Long operatorId) {

        if (sfi == null || report == null || signedQty == null
                || signedQty.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        return SemiFinishedInventoryTransaction.builder()
                .factoryId(report.getFactoryId())
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef(sourceRef)
                .quantity(signedQty)
                .unitCostAtTxn(sfi.getUnitCost())   // 诚实 null 传播
                .balanceAfter(nz(sfi.getAvailableQuantity()))
                .balanceCostAfter(sfi.getUnitCost())
                .reportId(report.getId())
                .operatorId(operatorId)
                .build();
    }
}
