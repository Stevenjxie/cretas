import { describe, expect, it } from 'vitest';
import { menuConfig, type MenuItem } from '../menuConfig';

/**
 * 餐饮菜单项的 `module` 必须与它指向的**路由** `meta.module` 一致。
 *
 * 为什么要专门钉这条：权限有两个承载点，而它们**各自独立**地决定一件事的两半 ——
 *
 *   菜单 module  → 决定侧栏里出不出现（AppSidebar.canSeeMenuItem）
 *   路由 module  → 决定进不进得去（router/guards.ts 的模块闸）
 *
 * 两边不一致时的表现极其难查：菜单上看得见、点进去 403；或者菜单上没有、直接敲 URL
 * 却能进。**两种都不报错**，测试也不会红 —— 因为此前没有任何用例横跨这两处。
 *
 * 2026-07-31 一天之内因为「只改了两个承载点之一」返工两次（#2084 菜单白名单、
 * 本次 IA 重排），这条用例就是把那个教训固化下来。
 */

interface RawRoute {
  path?: string;
  meta?: Record<string, unknown>;
  children?: RawRoute[];
}

function flattenMenu(items: MenuItem[], out: MenuItem[] = []): MenuItem[] {
  for (const item of items) {
    out.push(item);
    if (item.children) flattenMenu(item.children, out);
  }
  return out;
}

/** 把路由树拍平成 完整路径 → meta 的映射。 */
function flattenRoutes(
  items: RawRoute[],
  base = '',
  out: Record<string, Record<string, unknown>> = {},
): Record<string, Record<string, unknown>> {
  for (const r of items) {
    const raw = r.path ?? '';
    const full = raw.startsWith('/')
      ? raw
      : `${base}/${raw}`.replace(/\/+/g, '/').replace(/\/$/, '');
    if (r.meta) out[full || '/'] = r.meta;
    if (r.children) flattenRoutes(r.children, full, out);
  }
  return out;
}

describe('餐饮菜单与路由的 module 必须一致', () => {
  it('每个 /restaurant 菜单项的 module 与其路由 meta.module 相同', async () => {
    const mod = await import('@/router/index');
    const routes = (mod.default as { options: { routes: RawRoute[] } }).options.routes;
    const routeMeta = flattenRoutes(routes);

    const restaurantItems = flattenMenu(menuConfig)
      .filter((i) => i.path.startsWith('/restaurant/'));

    expect(restaurantItems.length, '一个餐饮菜单项都没找到, 说明选取条件写错了')
      .toBeGreaterThan(10);

    const mismatches: string[] = [];
    for (const item of restaurantItems) {
      const meta = routeMeta[item.path];
      if (!meta) {
        mismatches.push(`${item.path}: 菜单里有, 但没有对应路由`);
        continue;
      }
      if (meta.module !== item.module) {
        mismatches.push(
          `${item.path}: 菜单 module=${item.module} / 路由 module=${meta.module}`,
        );
      }
    }
    expect(mismatches, mismatches.join('\n')).toEqual([]);
  });
});

describe('餐饮功能页按五部门归属', () => {
  // 2026-08-06: 采购成为第五个部门 (Steve 拍板)。这个 Set 是写死的 ——
  // 加部门时漏改它, 新部门的页会被判成「还没归到部门」。
  const DEPT_MODULES = new Set([
    'restaurantOps', 'restaurantMarketing', 'restaurantHr', 'restaurantFinance',
    'restaurantProcurement',
  ]);

  /**
   * 两处刻意不归部门，都有明确理由 —— 写在这里而不是留白，
   * 免得以后有人看到"漏了两个"顺手改掉。
   */
  const DELIBERATE_EXCEPTIONS: Record<string, string> = {
    '/restaurant/supplier-delivery':
      '跨工厂/餐饮两侧使用(warehouse_manager / procurement_manager 也在用, 而这些角色 restaurant=-)',
    '/restaurant/data-completeness': '跨部门数据基础设施, 归进任一部门会让其余三个找不到',
    '/restaurant/admin/etl-status': 'admin 页, 只给平台管理员',
    '/restaurant/admin/name-resolution': 'admin 页, 只给平台管理员',
  };

  it('除已声明的例外外, 餐饮功能页都挂在五个部门之一', () => {
    const stray = flattenMenu(menuConfig)
      .filter((i) => i.path.startsWith('/restaurant/'))
      .filter((i) => !DEPT_MODULES.has(i.module))
      .filter((i) => !(i.path in DELIBERATE_EXCEPTIONS))
      .map((i) => `${i.path} (module=${i.module})`);

    expect(stray, `这些页面还没归到部门:\n${stray.join('\n')}`).toEqual([]);
  });

  it('五个部门各自都有驾驶舱和至少一个功能页', () => {
    const byDept = new Map<string, number>();
    for (const item of flattenMenu(menuConfig)) {
      if (!item.path.startsWith('/restaurant/')) continue;
      if (!DEPT_MODULES.has(item.module)) continue;
      byDept.set(item.module, (byDept.get(item.module) ?? 0) + 1);
    }
    // 驾驶舱本身各算一个；预测排班补齐了人事的首个功能页。
    expect(byDept.get('restaurantOps') ?? 0).toBeGreaterThan(1);
    expect(byDept.get('restaurantMarketing') ?? 0).toBeGreaterThan(1);
    expect(byDept.get('restaurantFinance') ?? 0).toBeGreaterThan(1);
    expect(byDept.get('restaurantHr') ?? 0).toBeGreaterThan(1);
  });
});
