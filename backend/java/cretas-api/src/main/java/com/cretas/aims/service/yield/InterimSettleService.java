package com.cretas.aims.service.yield;

import java.util.Map;

/**
 * G3 小结 (interim-settle) 编排 — BY_STOCK 永续生产模式专用。
 *
 * <p>每次点击「小结」对自上次小结以来的<b>增量</b>分批入库 + 实时扣减原料 (会话幂等):
 * <ul>
 *   <li><b>扣原料</b>: 未结 {@code material_consumptions} (interim_settled_at IS NULL) →
 *       逐行从来源 {@code MaterialBatch} 悲观锁扣 usedQuantity, 打戳。</li>
 *   <li><b>半成品入库 (SFI IN)</b>: 未结 {@code process_sheet_rows} 中本次小结内 <b>未被同小结下游消耗</b>的
 *       非成品终点道产出 → SemiFinishedInventory 运行余额行。同小结内被下游消耗的中间道产出 = 瞬态在制,
 *       不入库 (避免双重入库)。</li>
 *   <li><b>半成品出库 (SFI OUT)</b>: 未结道消耗了<b>前序小结已入库</b>的半成品 → SemiFinishedInventory 扣减。</li>
 *   <li><b>成品入库 (FG)</b>: 未结成品道 (finished=true) 产出 (优先 productWeight 成品重) → FinishedGoodsBatch。</li>
 * </ul>
 *
 * <p><b>不</b>关闭计划 (不 setStatus COMPLETED) —— 永续模式靠「停产」(Task 4) 才关。
 * 整体 {@code @Transactional}, factory-scoped, 锁序: MaterialBatch → SFI。
 *
 * <p>🔒 红线: 库存写 + 扣减 + 多租户。重复点击靠 material_consumptions / process_sheet_rows 双标记天然幂等。
 */
public interface InterimSettleService {

    /**
     * 对 BY_STOCK 计划执行一次小结。
     *
     * @param factoryId 工厂 ID (factory-scoped 🔒)
     * @param planId    生产计划 ID
     * @param userId    操作人 (posted_by, 可空)
     * @return 本次小结摘要 (sessionSeq / 扣减条数+量 / 半成品入出库 / 成品入库)
     * @throws com.cretas.aims.exception.BusinessException 404 计划不存在 / 400 非 BY_STOCK 计划
     */
    Map<String, Object> interimSettle(String factoryId, String planId, Long userId);
}
