export type SegmentLevel = 1 | 2 | 3;

export interface SegmentRuleNode {
  id: number;
  segmentCode: string;
  segmentLabel: string;
  level: SegmentLevel;
  parentCode: string | null;
}

export const SEGMENT_LEVEL_DEFINITIONS = [
  {
    level: 1 as const,
    title: 'L1 大类',
    description: '最高层的物料管理范围，用于区分原料、辅料、包材等大方向。',
    example: '原料、辅料、包材',
  },
  {
    level: 2 as const,
    title: 'L2 中类',
    description: '大类下面稳定的业务分类，用于归拢性质或用途相近的物料。',
    example: '禽肉类、调味料、纸制包装',
  },
  {
    level: 3 as const,
    title: 'L3 小类',
    description: '可被原料类型直接复用的具体品类；单位、规格、供应商和储存方式仍维护在原料类型中。',
    example: '黄油鸡、食盐、纸盒',
  },
] as const;

function normalizeLabel(label: string): string {
  return label.trim().toLocaleLowerCase();
}

export function findLabelConflict(
  rows: SegmentRuleNode[],
  label: string,
  excludedId: number | null,
): SegmentRuleNode | undefined {
  const normalized = normalizeLabel(label);
  if (!normalized) return undefined;
  return rows.find((row) => row.id !== excludedId && normalizeLabel(row.segmentLabel) === normalized);
}

/**
 * ⛔ **不要用它做真实编码分配** —— 2026-08-06 事故就是这么来的。
 *
 * 它只看得到传进来的 `rows`（= 界面上活着的节点）。而分类是**软删除**，
 * 编码被软删行继续占用（唯一约束 `uk_mcs_factory_segment` 不排除软删行，
 * 且 `material_business_code_prefixes` 有外键指向该编码）。把一整层删干净后，
 * 这里会算出一个**已被占用**的编码 → INSERT 撞约束 → 用户收到「已存在同名分类」，
 * 于是不停改名字，而改名字永远修不好编码冲突。
 *
 * 真实分配走服务端 `GET /{factoryId}/material-segments/next-code`
 * （`MaterialCodeSegmentService#nextSegmentCode`，按含软删除的口径）。
 *
 * 本函数保留仅用于单测编码**形状**（前缀 + 补零宽度）。
 *
 * @deprecated 真实分配请调服务端 next-code 接口。
 */
export function nextSegmentCode(
  rows: SegmentRuleNode[],
  level: SegmentLevel,
  parentCode: string | null,
): string {
  const suffixWidth = level === 3 ? 4 : 3;
  const prefix = level === 1 ? '' : String(parentCode || '');
  const expectedParentLength = level === 2 ? 3 : level === 3 ? 6 : 0;
  if (level > 1 && prefix.length !== expectedParentLength) return '';

  const maxSuffix = rows
    .filter((row) => row.level === level && (level === 1 || row.parentCode === prefix))
    .reduce((max, row) => {
      const suffix = row.segmentCode.slice(-suffixWidth);
      return /^\d+$/.test(suffix) ? Math.max(max, Number(suffix)) : max;
    }, 0);

  return `${prefix}${String(maxSuffix + 1).padStart(suffixWidth, '0')}`;
}
