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
 * 🔴 「副产」**不是**这里的第 5 个桶。它是物料上的标记 (`isByproduct`), 与材质分类**正交** ——
 * 见 utils/byproductMaterial.ts。2026-07-31 上午一度把它做成 category='副产', 当天走前端
 * 验收时被推翻: 那样副产 SKU 在 BOM「原料」页签里选不到, 而「副产以后能当原料被别的
 * workflow 投入」正是把副产放进原料字典的初衷。肥油的材质是原料, 来历是副产, 两件事。
 */
/**
 * `system_enums.MATERIAL_CATEGORY` 的规范取值 (V20260506_01 seed, 见上方注释第 1 条)。
 *
 * 用途: 给**没有配物料分段字典**的工厂当「类别」下拉选项 —— 有字典的工厂类别由分段字典
 * L1 类族派生(materialFamilyOptions), 无字典的工厂就没有那个来源, 以前因此建不了物料。
 * 2026-08-07 实测六膳门 115 个自由料号物料的 category 全落在这 5 个值里
 * (调味料 65 / 包材 25 / 主材 19 / 添加剂 6), 与本枚举一致。
 */
export const MATERIAL_CATEGORY_ENUM_VALUES = ['主材', '辅材', '调味料', '包材', '添加剂'] as const;

export type BigCategory = '原料' | '辅料' | '调料' | '包材' | '其他';

export const BIG_CATEGORY_OPTIONS: { label: string; value: BigCategory | '' }[] = [
  { label: '全部', value: '' },
  { label: '原料', value: '原料' },
  { label: '辅料', value: '辅料' },
  { label: '调料', value: '调料' },
  { label: '包材', value: '包材' },
  { label: '其他', value: '其他' },
];

export const RAW_CATEGORY_VALUES = new Set([
  '原料', '主材', '肉类', '禽类', '海鲜', '水产类', '海水鱼', '淡水鱼', '虾类', '贝类',
  '蔬菜', '水果', '粉类', '米面', '油类',
]);
export const AUX_CATEGORY_VALUES = new Set(['辅料', '辅材', '添加剂']);
export const SEASONING_CATEGORY_VALUES = new Set(['调料', '调味料', '调味品']);
export const PACKAGING_CATEGORY_VALUES = new Set(['包材', 'packaging', 'PACKAGING']);

/**
 * 把物料的 category 字段归类到 4 大业务类别之一 (未识别归"其他")。
 * 「副产」不在这里 —— 它是标记不是材质, 判定走 utils/byproductMaterial.ts#isByproductMaterial。
 */
export function bigCategoryOf(category: string | null | undefined): BigCategory {
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
