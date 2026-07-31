/**
 * 物料大类归类 (原料/辅料/调料/包材/其他/副产)
 *
 * 客户张权反馈 (2026-07-02): 物料下拉太乱 (原料/辅料/调料/包材混在一起, 面酱/白醋/牛腩排/黄油…),
 * 加"先选大类再选物料"两级筛选。首次在 procurement/receives/list.vue 落地, 提取为共用工具供
 * production/bom/index.vue 等其它物料选择器复用 (single source of truth, 避免两套归类逻辑漂移)。
 *
 * category 字段是自由文本, 两套并存的取值来源 (2026-07-02 curl F006 真实数据确认):
 * 1. system_enums.MATERIAL_CATEGORY 字典 (V20260506_01 seed, 新建物料表单下拉用的规范值):
 *    主材/辅材/调味料/包材/添加剂 (添加剂 2026-07-01 新增)
 * 2. 历史遗留自由文本 (早于字典落地 / material-spec-config 系统默认类别):
 *    原料/肉类/禽类/海鲜/水产类/海水鱼/淡水鱼/虾类/贝类/蔬菜/水果/粉类/米面/油类/调料/调味品
 * F006 真实数据目前只有 原料(5)/肉类(1)/包材(1) 三个值 (无调料/辅料记录), 两套来源都要能归到
 * 4 个业务大类; 未识别的 category 归"其他"桶 (不隐藏, 宁缺勿藏 — fool-proof-design Rule 5)。
 *
 * "添加剂" 归入"辅料"桶 (客户特别提到辅料/添加剂混在一起, 两者业务上都属"非主料的配方成分",
 * 且 system_enums 目前没有独立的"添加剂"大类筛选项, 归并可避免下拉再多分一档增加认知负担).
 *
 * 第 5 个桶 "副产" 与上面 4 个不同源: 不是从自由文本里"猜"出来的, 而是建副产 SKU 时**显式打标**
 * category = '副产' (见 BYPRODUCT_CATEGORY / isByproductCategory)。加这条分支是因为
 * warehouse/stocktakes/index.vue 直接把 BIG_CATEGORY_OPTIONS 渲成选择器, 再用
 * filterOptionsByBigCategory → bigCategoryOf 过滤物料列表 —— 选择器显示"副产"选项,
 * 但 bigCategoryOf 若没有对应分支, 所有 category='副产' 的物料都会落进"其他"桶,
 * 用户选"副产"永远筛出 0 条 (显示半成品加了, 承载它的分类半成品没加, 同一天踩过的坑)。
 */
export type BigCategory = '原料' | '辅料' | '调料' | '包材' | '其他' | '副产';

export const BIG_CATEGORY_OPTIONS: { label: string; value: BigCategory | '' }[] = [
  { label: '全部', value: '' },
  { label: '原料', value: '原料' },
  { label: '辅料', value: '辅料' },
  { label: '调料', value: '调料' },
  { label: '包材', value: '包材' },
  { label: '其他', value: '其他' },
  { label: '副产', value: '副产' },
];

export const RAW_CATEGORY_VALUES = new Set([
  '原料', '主材', '肉类', '禽类', '海鲜', '水产类', '海水鱼', '淡水鱼', '虾类', '贝类',
  '蔬菜', '水果', '粉类', '米面', '油类',
]);
export const AUX_CATEGORY_VALUES = new Set(['辅料', '辅材', '添加剂']);
export const SEASONING_CATEGORY_VALUES = new Set(['调料', '调味料', '调味品']);
export const PACKAGING_CATEGORY_VALUES = new Set(['包材', 'packaging', 'PACKAGING']);

/**
 * 把物料的 category 字段归类到 5 大业务类别之一 (未识别归"其他")。
 * 副产走显式打标判定 (isByproductCategory), 其余 4 类走自由文本集合匹配 —— 两条不同的
 * 归类依据, 但都要收敛到同一个 BigCategory, 否则 BIG_CATEGORY_OPTIONS 里显示的桶和
 * filterOptionsByBigCategory 实际筛出来的桶会对不上 (见文件头注释)。
 */
export function bigCategoryOf(category: string | null | undefined): BigCategory {
  if (isByproductCategory(category)) return '副产';
  const c = (category || '').trim();
  if (!c) return '其他';
  if (RAW_CATEGORY_VALUES.has(c)) return '原料';
  if (AUX_CATEGORY_VALUES.has(c)) return '辅料';
  if (SEASONING_CATEGORY_VALUES.has(c)) return '调料';
  if (PACKAGING_CATEGORY_VALUES.has(c)) return '包材';
  return '其他';
}

/**
 * 按选中的大类 (row._bigCategory 等) 过滤物料选项; 未选大类 (空字符串) 时返回全部, 保持原有顺序。
 */
export function filterOptionsByBigCategory<T extends { category?: string | null }>(
  options: T[],
  bigCategory: BigCategory | '' | undefined,
): T[] {
  if (!bigCategory) return options;
  return options.filter((m) => bigCategoryOf(m.category) === bigCategory);
}

/**
 * 副产大类 —— 副产 SKU 放在原料字典里(与 WIP 半成品同一条路, prod 已有 249 条先例),
 * 但它**没有采购来源**: unitPrice / taxIncludedUnitPrice / movingAvgPrice / minStock
 * 这些采购属性对它全是空的。用大类把它与「买来的原料」隔开, 避免出现在采购下拉与补货建议。
 */
export const BYPRODUCT_CATEGORY = '副产' as const;

export function isByproductCategory(category: string | null | undefined): boolean {
  return typeof category === 'string' && category.trim() === BYPRODUCT_CATEGORY;
}
