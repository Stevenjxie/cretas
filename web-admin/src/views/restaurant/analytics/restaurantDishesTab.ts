export type DishesTab = 'quadrant' | 'margin';
const VALID: DishesTab[] = ['quadrant', 'margin'];
/** 菜品分析 tab: 四象限(quadrant) / 毛利明细(margin)。非法/缺省 → quadrant。 */
export function resolveDishesTab(query: Record<string, unknown>): DishesTab {
  const t = query?.tab;
  return VALID.includes(t as DishesTab) ? (t as DishesTab) : 'quadrant';
}
