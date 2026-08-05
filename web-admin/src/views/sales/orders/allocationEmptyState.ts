/**
 * 「分配批次」无可分配批次时的诚实空态文案 (fool-proof Rule 5)。
 *
 * 客户 2026-07-31 反馈 (Sheet 第 40 行):「针对新生成的子发运单, 点击分配批次,
 * 提示的仓库为英文代号」。这段文案是唯一告诉仓管「货其实在别的仓」的地方 ——
 * 它却直接拼 `sourceWarehouseCode` / `stockWarehouses` 里的原始 code (`WH-FG`、
 * `WH-LOG`)。仓管认得的是仓库名, 而且客户可以在仓库配置里自己重命名
 * (见 `utils/warehouse.ts` 的 2026-07-02 LIUSHANMEN 事故注释: DB name 才是权威)。
 *
 * 名字解析交给调用方注入的 `resolveName` —— 本模块不认识 warehouse 列表, 也就
 * 不会重新引入一张会和 DB name 打架的硬编码映射表。payload 仍然只走 code,
 * 换名只发生在渲染这一层。
 */

/** 把 code 解析成仓库名; 解析不到时按 `warehouseNameByCode` 的约定回退到 code 本身。 */
export type WarehouseNameResolver = (code: string | null | undefined) => string;

/**
 * 区分「来源仓没有」与「全厂真的没有」—— 两者的下一步动作完全不同
 * (改选来源仓/调拨 vs 先生产入库), 混成一句会把仓管指向错误的方向。
 */
export function allocationEmptyStateTitle(stockWarehouseCodes: string[]): string {
  return stockWarehouseCodes.length > 0 ? '当前来源仓无可用成品批次' : '没有可用成品批次';
}

export function allocationEmptyStateDesc(
  stockWarehouseCodes: string[],
  sourceWarehouseCode: string | null | undefined,
  resolveName: WarehouseNameResolver,
): string {
  if (stockWarehouseCodes.length > 0) {
    const where = stockWarehouseCodes.map((code) => resolveName(code)).join(' / ');
    const src = sourceWarehouseCode
      ? `当前发货行来源仓为「${resolveName(sourceWarehouseCode)}」`
      : '当前发货行未声明来源仓';
    return `该产品成品在「${where}」仓有库存，${src} — 请改选来源仓，或先调拨到来源仓后再分配。`;
  }
  return '该产品当前全厂无可发货成品库存（已含全部可售仓库，研发/中试库除外），请先完成生产入库，或联系仓管检查库存状态。';
}
