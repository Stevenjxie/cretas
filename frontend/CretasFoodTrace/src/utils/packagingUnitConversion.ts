/**
 * 按「原料字典的规格层级」把一个数量换算成同一原料的其它单位。
 *
 * 背景 (2026-08-02): 库存页此前把不同单位的数量直接相加, prod F006 实测算出
 * 「可用数量 725,908.175」= 70 万克食材 + 1 万个盒子 + 1 万张膜 + 310 个箱子 硬加,
 * 这个数不对应任何真实事物。而全局单位表 `unit_of_measurements` 里, 计数族 16 个单位
 * (盒/袋/箱/片/卷…) 的 conversion_factor **全是 1.000000**, 即表里断言「1盒 = 1箱 = 1件」——
 * 比"没有换算"更危险: 代码信了它就会理直气壮地换算完再相加。
 *
 * 真正的权威在**原料字典**的规格层级 (material_packaging_hierarchy), 一个原料一条:
 *   level1Unit  = 最小单位            例: g
 *   level1PerLevel2 = 几个一级 = 一个二级   例: 800  (800 g = 1 盒)
 *   level2Unit  = 中间单位            例: box
 *   level2PerLevel3 = 几个二级 = 一个三级   例: 8    (8 盒 = 1 箱)
 *   level3Unit  = 最大单位            例: case
 *
 * ⚠️ 本模块**只在同一原料自己的层级内换算**, 绝不跨原料、绝不动全局单位表。
 * 没配层级就返回 null —— 由调用方显示「未设规格」并给出设置入口, 不允许猜。
 */

import type { MaterialPackagingHierarchy } from '../services/api/materialPackagingApiClient';

/** 同义单位归一: 库里中英混存 (盒/box、箱/case 并存), 比较前先折成同一写法。 */
const UNIT_ALIASES: Record<string, string> = {
  盒: 'box',
  箱: 'case',
  片: 'slice',
  卷: 'roll',
  袋: 'bag',
  包: 'pack',
  瓶: 'bottle',
  罐: 'can',
  个: 'pcs',
  件: 'pcs',
  张: 'sheet',
  克: 'g',
  公斤: 'kg',
  千克: 'kg',
};

export function canonicalUnit(unit: string | null | undefined): string {
  if (!unit) return '';
  const trimmed = String(unit).trim();
  return UNIT_ALIASES[trimmed] ?? trimmed.toLowerCase();
}

export function sameUnit(a: string | null | undefined, b: string | null | undefined): boolean {
  const ca = canonicalUnit(a);
  return ca !== '' && ca === canonicalUnit(b);
}

/** 一个层级换算出来的等价表示。 */
export interface ConvertedUnit {
  unit: string;
  quantity: number;
}

export interface HierarchyConversion {
  /** 按层级从小到大排列, 含传入单位本身 */
  levels: ConvertedUnit[];
  /** 传入数量在层级里的位置 (1/2/3); 未命中为 null */
  matchedLevel: 1 | 2 | 3 | null;
}

/**
 * 把 `quantity` (单位 `unit`) 换算成该原料层级里的每一级。
 *
 * @returns null 表示**无法换算** —— 没配层级, 或传入单位不在这条层级里。
 *          调用方必须显示「未设规格」而不是退回按 1:1 相加。
 */
export function convertByHierarchy(
  quantity: number,
  unit: string | null | undefined,
  hierarchy: MaterialPackagingHierarchy | null | undefined
): HierarchyConversion | null {
  if (!hierarchy || !Number.isFinite(quantity)) return null;

  const l1 = hierarchy.level1Unit;
  const l2 = hierarchy.level2Unit;
  const l3 = hierarchy.level3Unit;
  const per2 = toPositive(hierarchy.level1PerLevel2);
  const per3 = toPositive(hierarchy.level2PerLevel3);

  if (!l1) return null;

  // 先把数量折算到一级 (最小单位)
  let base: number;
  let matched: 1 | 2 | 3 | null = null;

  if (sameUnit(unit, l1)) {
    base = quantity;
    matched = 1;
  } else if (l2 && sameUnit(unit, l2) && per2 != null) {
    base = quantity * per2;
    matched = 2;
  } else if (l3 && sameUnit(unit, l3) && per2 != null && per3 != null) {
    base = quantity * per2 * per3;
    matched = 3;
  } else {
    // 传入单位不在这条层级里 —— 不猜。
    return null;
  }

  const levels: ConvertedUnit[] = [{ unit: l1, quantity: base }];
  if (l2 && per2 != null) {
    levels.push({ unit: l2, quantity: base / per2 });
    if (l3 && per3 != null) {
      levels.push({ unit: l3, quantity: base / per2 / per3 });
    }
  }
  return { levels, matchedLevel: matched };
}

function toPositive(v: number | null | undefined): number | null {
  if (v == null) return null;
  const n = Number(v);
  // 0 或负数是无效配置 —— 当成「没配」, 不能拿去做除数。
  return Number.isFinite(n) && n > 0 ? n : null;
}

/** 展示用: 去掉无意义的小数尾巴 (8000.00 → 8000, 6.40 → 6.4)。 */
export function formatQuantity(n: number): string {
  if (!Number.isFinite(n)) return '-';
  const rounded = Math.round(n * 1000) / 1000;
  return String(rounded);
}

/**
 * 把一批「同单位数量」按量纲分组求和。
 *
 * 重量族内部 (g/kg/斤/两/mg/ton) 有**真实**换算, 折成 kg 后可以合并;
 * 计数族**没有**通用换算 (一盒 ≠ 一箱), 按单位各自成组, 绝不合并。
 */
const WEIGHT_TO_KG: Record<string, number> = {
  kg: 1,
  g: 0.001,
  mg: 0.000001,
  ton: 1000,
  jin: 0.5,
  liang: 0.05,
};

export interface UnitBucket {
  /** 'weight' 组内已折成 kg; 'count' 组保持原单位 */
  kind: 'weight' | 'count';
  unit: string;
  quantity: number;
  batchCount: number;
}

export function bucketByDimension(
  rows: Array<{ unit: string | null | undefined; quantity: number }>
): UnitBucket[] {
  let weightKg = 0;
  let weightBatches = 0;
  const counts = new Map<string, { quantity: number; batchCount: number }>();

  for (const row of rows) {
    const u = canonicalUnit(row.unit);
    const q = Number(row.quantity);
    if (!Number.isFinite(q)) continue;
    const factor = WEIGHT_TO_KG[u];
    if (factor != null) {
      weightKg += q * factor;
      weightBatches += 1;
    } else {
      const key = u || '(未标注)';
      const prev = counts.get(key) ?? { quantity: 0, batchCount: 0 };
      counts.set(key, { quantity: prev.quantity + q, batchCount: prev.batchCount + 1 });
    }
  }

  const buckets: UnitBucket[] = [];
  if (weightBatches > 0) {
    buckets.push({ kind: 'weight', unit: 'kg', quantity: weightKg, batchCount: weightBatches });
  }
  for (const [unit, v] of Array.from(counts.entries()).sort((a, b) => b[1].quantity - a[1].quantity)) {
    buckets.push({ kind: 'count', unit, quantity: v.quantity, batchCount: v.batchCount });
  }
  return buckets;
}
