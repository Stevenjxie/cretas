export type SegmentLevel = 1 | 2 | 3;

export interface SegmentRuleNode {
  id: number;
  segmentLabel: string;
  level: SegmentLevel;
  parentId: number | null;
}

export const SEGMENT_LEVEL_DEFINITIONS = [
  {
    level: 1 as const,
    title: '一级分类',
    description: '最高层的物料管理范围，用于区分原料、辅料、包材等大方向。',
    example: '原料、辅料、包材',
  },
  {
    level: 2 as const,
    title: '二级分类',
    description: '一级分类下面稳定的业务分类，用于归拢性质或用途相近的物料。',
    example: '禽肉类、调味料、纸制包装',
  },
  {
    level: 3 as const,
    title: '三级分类',
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
