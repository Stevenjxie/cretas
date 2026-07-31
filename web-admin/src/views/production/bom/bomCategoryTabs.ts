import { BYPRODUCT_CATEGORY, type BigCategory } from '@/utils/materialCategory';

/**
 * BOM「配方内容」的页签类别。
 *
 * 前三类是**投入**(要花钱买/领用的东西), 第四类「副产」语义相反 —— 它是**产出声明**:
 * 记这个配方预计产出哪个副产 SKU、多少量。所以副产行:
 *   - 不进 BOM 标准成本池 (后端 BomRecipeServiceImpl.recomputeFamilyCosts 显式跳过)
 *   - 不参与生效前提 (可选)
 *   - 没有替代物料 (替代的是投入什么, 不是产出什么)
 *   - 抵扣发生在**盘点**时 (按盘点实际重量 × 确认单价), 不在这里
 *
 * ⚠️ 这些判定原先散在 index.vue (153KB) 的 if/else 链里。提出来是因为「加了新的一类,
 * 但承载它的某一处没跟上」是本仓最高频的 bug 形状 —— 尤其原来的 currentTabItems 用的是
 * 「除前两类之外一律当包材」的兜底写法, 直接加第四个页签会让副产页签**列出全部包材**,
 * 不报错也不空白。放在这里才能用真单测把「不重不漏」钉死。
 */
export type BomCategoryTab = 'RAW' | 'AUXILIARY' | 'PACKAGING' | 'BYPRODUCT';

export const BOM_CATEGORY_TABS: readonly BomCategoryTab[] = [
  'RAW', 'AUXILIARY', 'PACKAGING', 'BYPRODUCT',
] as const;

export function isBomCategoryTab(value: unknown): value is BomCategoryTab {
  return typeof value === 'string' && (BOM_CATEGORY_TABS as readonly string[]).includes(value);
}

/**
 * 把任意来源的类别写法归一到页签码。
 *
 * 客户张权反馈 (2026-07-02): 对话框「物料类别」只有三档, AUXILIARY 沿用同一口径把
 * "调味料" 也算进去。未识别的兜底落 RAW —— 与 fool-proof-design Rule 5「宁缺勿藏」一致,
 * 未归类物料不因筛选彻底消失于任一档。
 */
export function normalizeRecipeMaterialCategory(value: unknown): BomCategoryTab {
  const category = String(value ?? '').trim().toUpperCase();
  const rawText = String(value ?? '').trim();
  if (category === 'BYPRODUCT' || rawText === BYPRODUCT_CATEGORY) return 'BYPRODUCT';
  if (category === 'PACKAGING' || rawText === '包材') return 'PACKAGING';
  if (category === 'AUXILIARY' || rawText === '辅料' || rawText === '调味料') return 'AUXILIARY';
  return 'RAW';
}

/** BOM 明细行归属哪个页签。每一行有且只有一个归属 —— 见 bomCategoryTabs.spec.ts 的「不重不漏」。 */
export function matchBomCategory(
  row: { materialCategory?: unknown; category?: unknown },
  tab: BomCategoryTab,
): boolean {
  const rawText = String(row.materialCategory ?? row.category ?? '').trim();
  const code = rawText.toUpperCase();
  if (tab === 'BYPRODUCT') return code === 'BYPRODUCT' || rawText === BYPRODUCT_CATEGORY;
  if (tab === 'PACKAGING') return code === 'PACKAGING' || rawText === '包材';
  if (tab === 'AUXILIARY') {
    return code === 'AUXILIARY' || rawText === '辅料' || rawText === '调味料';
  }
  // RAW 兜底: 历史空类别 / "其他" 仍算原料 (与 normalizeRecipeMaterialCategory 默认一致)。
  // 🔴 兜底只能挂在 RAW 上 —— 挂在最后一个页签上就会把所有未归类行倒进副产页签。
  return code === 'RAW' || rawText === '原材料' || rawText === '' || rawText === '其他';
}

/**
 * 「关联原料」下拉按当前页签放行哪些物料大类。
 *
 * AUXILIARY 同时放行 辅料 + 调料 两个桶 (二者本来就是"非原料非包材的配方成分")。
 * BYPRODUCT 只放行显式打标的「副产」—— 副产 SKU 建在原料字典里但与采购属性隔离 (Task 1)。
 */
export function bomTabBigCategories(tab: BomCategoryTab): BigCategory[] {
  if (tab === 'BYPRODUCT') return [BYPRODUCT_CATEGORY];
  if (tab === 'PACKAGING') return ['包材'];
  if (tab === 'AUXILIARY') return ['辅料', '调料'];
  return ['原料'];
}

/** 「添加X」按钮文案 (辅料页签不显示该按钮, 走工序辅料工作台)。 */
export function bomTabAddButtonLabel(tab: BomCategoryTab): string {
  if (tab === 'BYPRODUCT') return '添加副产';
  if (tab === 'PACKAGING') return '添加包材';
  return '添加原料';
}

/** 弹窗标题/占位符里对这一类物料的称呼。 */
export function bomTabItemLabel(tab: BomCategoryTab): string {
  if (tab === 'BYPRODUCT') return '副产';
  if (tab === 'PACKAGING') return '包材';
  if (tab === 'AUXILIARY') return '工序辅料';
  return '原料';
}
