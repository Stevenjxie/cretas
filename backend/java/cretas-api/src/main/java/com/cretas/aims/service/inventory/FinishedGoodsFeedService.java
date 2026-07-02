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
     * <p>🔴 <b>扣减口径 = 减 {@code producedQuantity} (对齐成品报损 SCRAP 惯例, 绝不动 shippedQuantity)</b>:
     * production-feed 是<b>生产领用</b>, 不是<b>销售发货</b>。若加 {@code shippedQuantity} 会被
     * {@code VoucherExportServiceImpl} 误当"成品发出"按<b>售价</b>计入数量金额账/收发存 → 污染发货/销售/COGS 口径
     * (与 {@code DisposalRecordService} 成品报损同类问题, 那里也刻意减 producedQuantity)。故本方法减
     * {@code producedQuantity} 并写 {@link com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog}
     * ({@code referenceType="PRODUCTION_FEED"}) 留痕。可用量 = produced − shipped − reserved, 减 produced
     * 即正确降 available, 且不虚增发货。
     *
     * <ul>
     *   <li>批次不存在 (factory-scoped) → 抛 {@code BusinessException(409, FG_NOT_FOUND)}。</li>
     *   <li><b>计数单位 (盒/个/件/只) + kg 投料</b>: 依据 {@code ProductType.gramsPerUnit} 把 kg 折算为盒数扣减
     *       (盒 = qty × 1000 / gramsPerUnit, scale=2 HALF_UP)。🔴 <b>缺每盒克重</b> → 抛
     *       {@code BusinessException(409, FG_NO_GRAMS_PER_UNIT)} (诚实 null, 禁止臆造 1盒=1kg)。</li>
     *   <li><b>其它单位不一致</b> (如 托 vs kg, 无 gramsPerUnit 折算依据) → 抛
     *       {@code BusinessException(409, FG_UNIT_MISMATCH)} (禁止降级: 不误扣)。</li>
     *   <li>扣减量超过可用量 (折算后 &gt; available) → 抛 {@code BusinessException(409, FG_INSUFFICIENT)} (拒绝超扣, 防 phantom)。</li>
     * </ul>
     * 成功时 {@code producedQuantity -= deductQty} (available 随之下降), 可用量 ≤ 0 → DEPLETED,
     * 返回<b>实际扣减量</b> (成品原生单位: kg 源=qty; 盒源=折算盒数; 不足即抛故永不少扣)。撤销小结 {@link #restoreForFeed}
     * 据此返回值精确还回 (口径一致)。悲观行锁串行化并发扣减。必须在调用方 {@code @Transactional} 内执行。
     *
     * @param factoryId   工厂 ID (factory-scoped 🔒)
     * @param batchNumber 成品批次号 (= {@code FinishedGoodsBatch.batchNumber})
     * @param qty         本次投料量 (kg; ≤0 → 返回 0, 不扣)
     * @param feedUnit    投料计量单位 (逐道投料量为 kg → 传 "kg"); 计数单位成品经 gramsPerUnit 折算, 其它单位不一致 loud-fail
     * @return 实际扣减量 (成品原生单位: kg 源=qty, 盒源=折算盒数; 不足/缺失/盒装缺克重/单位不一致即抛)
     */
    BigDecimal consumeForFeedStrict(String factoryId, String batchNumber, BigDecimal qty, String feedUnit);

    /**
     * 盒⇄kg 成本口径 — 把 kg 投料量折算为成品来源的<b>原生单位</b>数量 (计数单位 盒/个/件/只 → 盒数; kg 源 → 原值),
     * 供小结成本核算 (成本 = 折算后数量 × 每单位 {@link #getFeedUnitCost})。与 {@link #consumeForFeedStrict}
     * 的扣减折算<b>同源同口径</b> (盒 = feedKg × 1000 / gramsPerUnit, scale=2 HALF_UP), 保证扣减量与计价量一致。
     *
     * <p>🔴 诚实 null: 批次缺失 / 计数单位来源缺 {@code ProductType.gramsPerUnit} → 返 {@code null}
     * (调用方判本道产出成本未知, 禁止臆造)。只读不锁。
     *
     * @param factoryId   工厂 ID (factory-scoped 🔒)
     * @param batchNumber 成品批次号
     * @param feedKg      本道投料量 (kg; ≤0 → 返回 0)
     * @return 折算后的原生单位数量 (kg 源=feedKg; 盒源=盒数); 缺失/盒装缺克重 → null
     */
    BigDecimal resolveFeedQtyInSourceUnit(String factoryId, String batchNumber, BigDecimal feedKg);

    /**
     * 撤销小结 — 逆 {@code createFinishedGoodsForInterim} (FG 成品入库 un-create)。
     *
     * <p>把某次小结创建/入库的成品量从批次冲销: {@code producedQuantity -= qty}, 写反向调整日志
     * ({@code referenceType=INTERIM_SETTLE_REVERSAL})。冲销至 0 → 状态置 REVERSED (批次作废)。
     *
     * <p>🔴 <b>已发货/预留/领用守卫 (禁止降级)</b>: 若该成品入库后已被发货/预留/生产领用
     * (available_after = produced − qty − shipped − reserved &lt; 0) → <b>抛 {@code BusinessException(409,
     * FG_DOWNSTREAM_CONSUMED)}</b> (整事务回滚)。批次不存在 (已撤销/删除) → 抛 {@code FG_NOT_FOUND}。
     * 悲观行锁串行化。必须在调用方 {@code @Transactional} 内。
     *
     * @param factoryId   工厂 ID (factory-scoped 🔒)
     * @param batchNumber 成品批次号 (= 小结创建的 {@code FinishedGoodsBatch.batchNumber})
     * @param qty         本次冲销量 (= 小结入库量; ≤0 → no-op)
     * @param operatorId  操作人 (调整日志 audit, 可空)
     * @throws com.cretas.aims.exception.BusinessException 409 FG_NOT_FOUND / FG_DOWNSTREAM_CONSUMED
     */
    void reverseInterimCreate(String factoryId, String batchNumber, BigDecimal qty, Long operatorId);

    /**
     * 撤销小结 — 逆 {@link #consumeForFeedStrict} (FG 投料 restore, 还回领用)。
     *
     * <p>把某次小结投料扣减的成品量还回批次: {@code producedQuantity += qty}, 写反向调整日志
     * ({@code referenceType=INTERIM_SETTLE_REVERSAL})。DEPLETED → available&gt;0 时恢复 AVAILABLE。
     * 还量是增加库存 (无 phantom 风险); 批次缺失 → 抛 {@code FG_NOT_FOUND} (被撤销的领用其批次应存在)。
     * 悲观行锁。必须在调用方 {@code @Transactional} 内。
     *
     * @param factoryId   工厂 ID (factory-scoped 🔒)
     * @param batchNumber 成品批次号 (= 投料时同一批号)
     * @param qty         本次还回量 (≤0 → no-op)
     * @param operatorId  操作人 (调整日志 audit, 可空)
     * @throws com.cretas.aims.exception.BusinessException 409 FG_NOT_FOUND
     */
    void restoreForFeed(String factoryId, String batchNumber, BigDecimal qty, Long operatorId);

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
