/**
 * 餐饮部门：一处定义，四处兑现。
 *
 * ## 为什么需要这个文件
 *
 * 2026-08-01 盘点：同一批部门在**四处**各自维护一份清单 ——
 *   - `views/restaurant/departments/departmentConfig.ts`（key / module / 标题 / KPI）
 *   - `store/modules/permission.ts`（权限矩阵与解析）
 *   - `components/layout/menuConfig.ts`（菜单项的 module 标注）
 *   - `router/index.ts`（路由 meta.module）
 *
 * `permission.ts` 里那份的注释甚至写着「避免两处各写一份」——**没有兑现**。
 *
 * 加部门要改四处；漏一处的表现是「看得见点进去 403」或「菜单里没有、
 * 敲 URL 能进」——**两种都不报错**。这正是 #2082 一天返工四次的成因：
 * 只改权限矩阵 → 菜单不出来；补了菜单 → 页面 module 又没跟上。
 *
 * 现在 `permission.ts` 从 `DEPARTMENTS` 派生；本文件钉住剩下两处不许漂。
 *
 * ⚠️ 只覆盖**餐饮**部门(2026-08-06 起为五个: 运营/市场/采购/财务/人事)，
 * 不碰工厂侧的模块与角色。
 */
import { describe, expect, it } from 'vitest';

import { menuConfig } from '@/components/layout/menuConfig';
import { RESTAURANT_DEPARTMENT_MODULES } from '@/store/modules/permission';
import { DEPARTMENTS, DEPARTMENT_ORDER } from '../departmentConfig';

const DEPT_MODULES = DEPARTMENT_ORDER.map((k) => DEPARTMENTS[k].module);

function flattenMenu(items: any[]): any[] {
  return items.flatMap((it) => [it, ...(it?.children ? flattenMenu(it.children) : [])]);
}

describe('餐饮部门单一权威', () => {
  it('权威表本身自洽：key 与 module 一一对应且不重复', () => {
    // ⛔ 刻意不硬编码部门数。上一版写死 4, 采购成为第五个部门(PR#2345)后这条
    // **假红**, 把 main 拦住、所有人的 PR 都合不进去。加第六个部门时不该再重演。
    // 真正要钉的是「一一对应且不重复」——module 数与 key 数相等即可表达。
    expect(DEPARTMENT_ORDER.length).toBeGreaterThan(0);
    expect(new Set(DEPT_MODULES).size).toBe(DEPARTMENT_ORDER.length);
    expect(new Set(DEPARTMENT_ORDER).size).toBe(DEPARTMENT_ORDER.length);
    for (const key of DEPARTMENT_ORDER) {
      expect(DEPARTMENTS[key].key, `${key} 的 key 字段应与索引一致`).toBe(key);
      expect(DEPARTMENTS[key].module).toMatch(/^restaurant[A-Z]/);
    }
  });

  it('permission.ts 的部门清单是派生的，不是另写的一份', () => {
    expect([...RESTAURANT_DEPARTMENT_MODULES]).toEqual(DEPT_MODULES);
  });

  it('每个部门在菜单里都有入口 —— 少一个就是「有权限但看不见」', () => {
    const modules = new Set(
      flattenMenu(menuConfig as unknown as any[]).map((it) => it?.module).filter(Boolean),
    );
    const missing = DEPT_MODULES.filter((m) => !modules.has(m));
    expect(missing, `这些部门有权限键但菜单里没有入口：${missing.join(', ')}`).toEqual([]);
  });

  it('菜单里不出现权威表之外的 restaurantXxx 部门键 —— 多一个就是「菜单有而权限没有」', () => {
    const menuDeptLike = flattenMenu(menuConfig as unknown as any[])
      .map((it) => it?.module)
      .filter((m): m is string => typeof m === 'string' && /^restaurant[A-Z]/.test(m));
    const unknown = [...new Set(menuDeptLike)].filter((m) => !DEPT_MODULES.includes(m));
    expect(unknown, `菜单标了这些部门键，但权威表里没有：${unknown.join(', ')}`).toEqual([]);
  });
});
