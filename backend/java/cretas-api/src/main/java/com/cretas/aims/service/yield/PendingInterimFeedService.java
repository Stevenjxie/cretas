package com.cretas.aims.service.yield;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 🔴🔒🔒 延迟扣减盲区 — 待小结 SFI 投料聚合 (sibling of #1216/#1219, 2026-07-05)。
 *
 * <p>逐工序报工写「待小结」产出/投料 ({@code process_sheet_rows.interim_settled_at IS NULL}),
 * 但常驻半成品库存(SFI) 投料只在「小结」({@code InterimSettleServiceImpl} §②A) 才严格扣减
 * ({@code consumeClerkSemiStrict})。在「报工 → 小结」窗口内, SFI 的 {@code availableQuantity} 是
 * <b>stale-high</b> (账面未扣, 货已投产)。
 *
 * <p>这与原料 {@code MaterialConsumption} 的延迟扣减 (#1216/#1219 已修) 同构, 但 SFI 投料
 * <b>不写 MaterialConsumption</b> — 只存在于 {@code row_payload.upstreamSources} JSON。故须解析
 * 待小结行的 payload, 聚合每个来源批次的待扣投料量, 供半成品盘点
 * ({@code SemiFinishedStocktakeServiceImpl}) 把快照账面校正为「货架实物」
 * ({@code availableQuantity − 待扣 SFI 投料}) —— 否则仓管诚实盘点触发假盘亏 + 假 6602/1405 凭证 +
 * 后续小结二次扣减 (库存虚低 + 财务双记), 这正是 #1216 为原料盘点修复的同一盲区。
 *
 * <p><b>只覆盖 strict 投料路径</b> (semiFinished 引用常驻 SFI, 扣减确定性): 折算与 strict 扣减同源
 * ({@code resolveSemiFeedQtyInSourceUnit}), 折算失败 (盒装缺每盒克重 / 批次缺失) → 诚实 null →
 * <b>跳过不减</b> (此时小结本身也会 loud-fail 无法扣减, 故不减 = 货实际不会离库, 口径一致)。
 * 计划自身前序小结半成品的 anchor 容忍扣减路径 (consumeClerkSemi, clamp) <b>不</b>纳入 —— 其扣减量
 * 不可精确预判, 强减会造成假盘盈腐蚀财务 (honest-null, 同 #1219「族未知不减」)。
 */
public interface PendingInterimFeedService {

    /**
     * 待小结 <b>SFI 投料</b> 聚合: {@code intermediateBatchNo → Σ 待扣量(来源原生单位)}。
     * 无待扣的批次不在 map 中 (调用方按 0 处理 → 账面不变)。整厂一次性预取。
     */
    Map<String, BigDecimal> pendingSemiFeedByBatchNo(String factoryId);
}
