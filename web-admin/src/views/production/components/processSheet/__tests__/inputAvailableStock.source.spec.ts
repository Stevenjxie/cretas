import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '..', 'ProcessDataTable.vue'), 'utf8');

/**
 * 投入行的「可用库存」提示 —— **前端自算的那一版已于 2026-07-31 撤下**。
 *
 * 起因: 客户 2026-07-30 反馈「填完点正式报工才被告知可用 0只」, 于是加了行内可用量提示。
 * 2026-07-31 客户实测发现它**与后端打架**: 行内显示「可用 10kg」, 提交时后端说
 * 「需要 1kg, 可用 0kg, 缺少 1kg」。
 *
 * 🔴 根因不是参数调错, 是**前端结构上算不出这个数**。后端
 * `ProductionStockAllocationServiceImpl.plan()` 的可投量口径含三样前端拿不到的东西:
 *
 *   1. `warehouseResolver.resolveWorkshopId()` —— 只认那**一个**生产仓;
 *      前端 `pickConsumableWarehouseIds` 汇总的是原料仓 + 物流仓 + 生产仓。
 *   2. `allocationRepository.sumPendingQuantityByMaterialBatchId()` —— 扣掉**其它草稿行
 *      已占用**的量; 前端完全没有这个概念。
 *   3. `ProductionInventoryOwnershipGuard` —— 客供料 / 归属别的订单的批次在仓里,
 *      但本计划不能用。
 *
 * 少任何一样, 算出来的都是个**偏大且看着权威**的数 —— 比不显示更糟, 仓管员会照着它排活。
 *
 * 所以这组测试现在守的不是「显示得对」, 而是**「不许再让前端自己算这个数」**。
 * 正解是后端出只读接口复用同一段代码, 前端只负责显示。
 */
describe('投入行可用库存: 前端自算版已撤下 (客户 2026-07-31)', () => {
  it('不再渲染任何前端自算的「可用 X」文案', () => {
    // 撤下前是 `可用 ${fmtStockQty(stock.available)}${displayProcessUnit(stock.unit)}`
    expect(source).not.toMatch(/`可用 \$\{/);
    expect(source).not.toContain('单位不同, 未计入');
  });

  it('inputStockText 直接返回空串, 并写明为什么不能自算', () => {
    expect(source).toMatch(/function inputStockText[\s\S]{0,1600}?return '';/);
    // 注释里必须留下三条口径差异, 否则后人只会看到"返回空串"而以为是漏写
    expect(source).toContain('resolveWorkshopId');
    expect(source).toContain('sumPendingQuantityByMaterialBatchId');
    expect(source).toContain('ProductionInventoryOwnershipGuard');
  });

  it('也不再按那个数标红 —— 判据错了, 标红同样是错的', () => {
    expect(source).toMatch(/function inputExceedsAvailable[\s\S]{0,600}?return false;/);
    // 撤下前的判据
    expect(source).not.toContain('need > stock.available');
  });

  it('物料名仍是固定文本, 没有多余下拉 (这一条客户是满意的, 别顺手改回去)', () => {
    const fixed = source.match(/data-testid="bom-authorized-material-fixed"/g) || [];
    expect(fixed.length).toBe(2); // 卡片 + 表格两套模板
    expect(source).not.toContain('data-testid="bom-authorized-material-select"');
  });

  it('保留 成品工序投入量 的 advisory 语义 (与本次撤下无关, 别连坐)', () => {
    expect(source).toContain('function finishedInputOverAvailableHint');
    expect(source).toContain('账实差异由盘点纠正');
  });
});
