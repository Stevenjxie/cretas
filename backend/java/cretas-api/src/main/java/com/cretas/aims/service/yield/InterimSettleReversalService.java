package com.cretas.aims.service.yield;

import java.util.Map;

/**
 * 撤销小结 (interim-settle reversal) — {@link InterimSettleService} 的逆操作。BY_STOCK 存货生产专用。
 *
 * <p>把某一次小结 (session) 对库存 + 原料 + 成品的<b>全部动作精确逆转</b>, 使误结的逐道行恢复可编辑:
 * <ul>
 *   <li><b>SFI IN un-stock</b>: 逆 {@code postClerkOutput} — 冲销半成品入库净结余 (produced/accumulatedCost/unitCost),
 *       🔴 下游已消耗 (available&lt;0) → loud-fail 拒绝 (不产 phantom 负库存)。</li>
 *   <li><b>FG un-create</b>: 逆 {@code createFinishedGoodsForInterim} — 冲销成品入库量, 🔴 已发货/预留/领用 → loud-fail。</li>
 *   <li><b>SFI/FG OUT restore</b>: 逆 {@code consumeClerkSemi(Strict)} / {@code consumeForFeedStrict} — 还回被扣的上游半成品/成品。</li>
 *   <li><b>原料 restore</b>: 还回来源 MaterialBatch.usedQuantity + 清 material_consumptions.interim_settled_at。</li>
 *   <li><b>行 un-stamp</b>: 清 process_sheet_rows.interim_settled_at → 行恢复未结 (可再编辑/删除/重新小结)。</li>
 * </ul>
 *
 * <p><b>幂等</b>: 已撤销的小结 (硬删) 再撤 → 409 (双撤拒绝)。<b>原子</b>: 全程单 {@code @Transactional},
 * 任一步 (尤其下游守卫) 失败 → 整体回滚, 不留半撤状态。
 *
 * <p><b>本次小结路径无财务凭证</b> ({@link InterimSettleService#interimSettle} 不 post voucher) → 无红冲。
 * 若未来小结路径接入凭证, 需在此补 mirror 现有 红冲 pattern。
 *
 * <p>🔒 红线: 逆转真实库存 + 财务口径 + 多租户。
 */
public interface InterimSettleReversalService {

    /**
     * 撤销某次小结。
     *
     * @param factoryId  工厂 ID (factory-scoped 🔒)
     * @param planId     生产计划 ID
     * @param sessionSeq 目标小结序号 (null → 撤销最近一次未撤销的小结)
     * @param userId     操作人 (audit, 可空)
     * @return 撤销摘要 (reversedSessionSeq / 逆转的各类动作条数 / 还回原料条数 / 恢复行数)
     * @throws com.cretas.aims.exception.BusinessException
     *         404 计划不存在 / 400 非 SAFETY_STOCK / 409 小结不存在或已撤销 / 409 下游已消耗 (loud-fail) /
     *         409 该小结无 reversalDetail (撤销功能上线前创建, 无法自动撤销)
     */
    Map<String, Object> reverseInterimSettle(String factoryId, String planId, Integer sessionSeq, Long userId);
}
