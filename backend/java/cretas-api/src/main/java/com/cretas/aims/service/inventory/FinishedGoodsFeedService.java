package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.processentry.FinishedGoodsStockItem;

import java.math.BigDecimal;
import java.util.List;

/**
 * ①c 成品作投料来源 (07-01 客户: 「选批次时看到库里所有成品和半成品」)。
 *
 * <p>逐道录入把常驻<b>成品库存 (FinishedGoodsBatch)</b> 作为可选投料来源, 与半成品(SFI) 投料平行。
 * 本服务提供 (a) 可投料成品下拉列表 (产品族过滤) (b) 小结时严格扣减 (禁止降级) (c) 成本传导读取。
 *
 * <p>🔴 与 SFI 投料 ({@code WipInventoryService.consumeClerkSemiStrict}/{@code getSemiUnitCost}) 严格对齐:
 * 缺失/不足即抛, 成本诚实 null。
 */
public interface FinishedGoodsFeedService {

    /**
     * 列出该工厂 <b>可投料</b> 的成品批次 (AVAILABLE 且可用量 &gt; 0), 供逐道 FG 投料下拉。
     *
     * <p><b>产品族过滤 (防呆, 07-01 客户会议)</b>: {@code productTypeId} 非空 → 解析为产品族
     * (以原料为主自动识别, 见 {@code ProductFamilyResolver}), 仅返回<b>同族</b>成品 (猪蹄计划不显牛肉)。
     * <b>宁缺勿藏</b>: 仅排除"族已知且与计划族不同"的行; 族识别不出的候选放行; 计划族识别不出 → 全放行。
     * {@code productTypeId} 为 null/空 → 全量 (不按族过滤)。
     *
     * <p>成品是<b>终态</b> (无工序阶段概念), 故不设阶段过滤 (区别于 SFI 的 maxProcessOrder 防回锅)。
     *
     * @param factoryId     工厂 ID (factory-scoped 🔒)
     * @param productTypeId 当前计划产品类型 id → 内部解析成族键 (null/空 = 不按族过滤)
     * @return 可投料成品项 (含 品名 / 生产日期 / 可用量 / 单位 / 成本)
     */
    List<FinishedGoodsStockItem> listAvailableForFeed(String factoryId, String productTypeId);

    /**
     * 小结严格扣减成品批次 (FG 投料, 禁止降级)。镜像 {@code WipInventoryService.consumeClerkSemiStrict}。
     *
     * <ul>
     *   <li>批次不存在 (factory-scoped) → 抛 {@code BusinessException(409, FG_NOT_FOUND)}。</li>
     *   <li>扣减量超过可用量 (qty &gt; available) → 抛 {@code BusinessException(409, FG_INSUFFICIENT)} (拒绝超扣, 防 phantom)。</li>
     * </ul>
     * 成功时按 {@code shippedQuantity += qty} 减少可用量 (mirror {@code deductFinishedGoodsInventory} 物理出库口径),
     * 可用量 ≤ 0 → 状态置 DEPLETED, 返回<b>实际扣减量</b> (= qty, 不足即抛故永不少扣)。
     * 悲观行锁串行化并发扣减。必须在调用方 {@code @Transactional} 内执行。
     *
     * @param factoryId   工厂 ID (factory-scoped 🔒)
     * @param batchNumber 成品批次号 (= {@code FinishedGoodsBatch.batchNumber})
     * @param qty         本次投料扣减量 (≤0 → 返回 0, 不扣)
     * @return 实际扣减量 (= qty; 不足/缺失即抛)
     */
    BigDecimal consumeForFeedStrict(String factoryId, String batchNumber, BigDecimal qty);

    /**
     * 读成品批次的单位成本 {@code unitCost} — 成本传导基准 (FG 投料下游道算本道产出成本时用)。
     *
     * <p>🔴 诚实 null: 批次缺失 或 {@code unitCost} 为 null (未接通成本的成品) → 返 <b>null</b>, 绝不伪造 ¥0。
     * 调用方 (小结成本计算) 见 null 即把整道产出成本判为未知 (诚实 null 传播)。只读, 不加锁。
     *
     * @return 该成品批次的 unitCost; 缺失或未知 → null
     */
    BigDecimal getFeedUnitCost(String factoryId, String batchNumber);
}
