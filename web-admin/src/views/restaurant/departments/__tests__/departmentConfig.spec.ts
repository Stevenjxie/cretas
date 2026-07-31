import { describe, expect, it } from 'vitest';
import {
  DEPARTMENTS,
  DEPARTMENT_ORDER,
  pickPath,
  type DeptKey,
} from '../departmentConfig';
import { RESTAURANT_DEPARTMENT_MODULES } from '@/store/modules/permission';
import { menuConfig, type MenuItem } from '@/components/layout/menuConfig';

function findByPath(items: MenuItem[], path: string): MenuItem | undefined {
  for (const item of items) {
    if (item.path === path) return item;
    const hit = item.children && findByPath(item.children, path);
    if (hit) return hit;
  }
  return undefined;
}

describe('餐饮部门驾驶舱配置', () => {
  it('四个部门的 module 与权限 store 的部门键完全一致', () => {
    // 两处各写一份部门名就会漂 —— 漂了的表现是「某个部门页谁都进不去」或
    // 「谁都能进」，两种都不报错。
    const fromConfig = DEPARTMENT_ORDER.map((k) => DEPARTMENTS[k].module).sort();
    const fromStore = [...RESTAURANT_DEPARTMENT_MODULES].sort();
    expect(fromConfig).toEqual(fromStore);
  });

  it('每个部门都在菜单里, 且菜单项的 module 与配置一致', () => {
    for (const key of DEPARTMENT_ORDER) {
      const cfg = DEPARTMENTS[key];
      const item = findByPath(menuConfig, `/restaurant/${key}`);
      expect(item, `${key} 不在菜单里`).toBeTruthy();
      expect(item!.module, `${key} 菜单 module 不一致`).toBe(cfg.module);
      // 部门项刻意不加 roles 白名单：module 权限已经足够，再叠一层就是第二处
      // 要同步的地方（#2084 修的正是那个坑）。
      expect(item!.roles, `${key} 不该再叠角色白名单`).toBeUndefined();
    }
  });

  it('人事没有数据源, 必须有空态, 且不给任何 KPI', () => {
    const hr = DEPARTMENTS.hr;
    // fact_staffing_daypart 全表 0 行 —— 给 KPI 就只能显示 0，那是假数据
    expect(hr.source).toBeNull();
    expect(hr.kpis).toHaveLength(0);
    expect(hr.emptyState).toBeTruthy();
    expect(hr.emptyState!.todos.length).toBeGreaterThan(0);
  });

  it('有数据源的部门必须有 KPI —— 否则页面是空的', () => {
    for (const key of DEPARTMENT_ORDER) {
      const cfg = DEPARTMENTS[key];
      if (!cfg.source) continue;
      expect(cfg.kpis.length, `${key} 有数据源却没有 KPI`).toBeGreaterThan(0);
    }
  });

  it('没有数据源的部门不能声明 KPI, 否则会渲染出一排「—」冒充指标', () => {
    for (const key of DEPARTMENT_ORDER) {
      const cfg = DEPARTMENTS[key];
      if (cfg.source) continue;
      expect(cfg.kpis, `${key} 无数据源却声明了 KPI`).toHaveLength(0);
    }
  });

  it('推荐问题都不为空 —— 必须是真答得了的问题, 不是口号', () => {
    for (const key of DEPARTMENT_ORDER) {
      const qs = DEPARTMENTS[key].questions;
      expect(qs.length, `${key} 没有推荐问题`).toBeGreaterThan(0);
      for (const q of qs) expect(q.trim().length).toBeGreaterThan(3);
    }
  });

  it('功能入口的路径都以 / 开头（相对路径会拼错）', () => {
    for (const key of DEPARTMENT_ORDER) {
      for (const e of DEPARTMENTS[key].entries) {
        expect(e.path.startsWith('/'), `${key}: ${e.path}`).toBe(true);
      }
    }
  });
});

describe('pickPath', () => {
  const data = { totals: { total_wastage_cost: 10071.77, zero: 0 }, top: [1, 2] };

  it('按点号路径取值', () => {
    expect(pickPath(data, 'totals.total_wastage_cost')).toBe(10071.77);
    expect(pickPath(data, 'top')).toEqual([1, 2]);
  });

  it('取不到返回 undefined 而不是 0', () => {
    // 关键：0 是合法数值，缺字段不能伪装成 0，否则「没有数据」看起来像「就是 0」
    expect(pickPath(data, 'totals.missing')).toBeUndefined();
    expect(pickPath(data, 'nope.deep')).toBeUndefined();
    expect(pickPath(data, 'totals.zero')).toBe(0);
  });

  it('源为 null/undefined 时不抛', () => {
    expect(pickPath(null, 'a.b')).toBeUndefined();
    expect(pickPath(undefined, 'a')).toBeUndefined();
  });
});

describe('部门路由', () => {
  it('四个部门各有一条路由, meta.module 与配置一致', async () => {
    const mod = await import('@/router/index');
    const routes = (mod.default as { options: { routes: unknown[] } }).options.routes;

    type RawRoute = { path?: string; meta?: Record<string, unknown>; children?: unknown[] };

    // ⚠️ 必须限定在 restaurant 子树内找。顶层还有一个工厂侧的 `/hr` 路由，
    // 全树搜 path==='hr' 会先命中它 —— 第一版就是这么误报的。
    function findRestaurantNode(items: unknown[]): RawRoute | undefined {
      for (const raw of items) {
        const r = raw as RawRoute;
        if (r.path === 'restaurant') return r;
        if (r.children) {
          const hit = findRestaurantNode(r.children);
          if (hit) return hit;
        }
      }
      return undefined;
    }

    const restaurant = findRestaurantNode(routes);
    expect(restaurant, '找不到 restaurant 路由节点').toBeTruthy();

    for (const key of DEPARTMENT_ORDER as DeptKey[]) {
      const child = (restaurant!.children ?? []).find(
        (raw) => (raw as RawRoute).path === key,
      ) as RawRoute | undefined;
      expect(child, `${key} 路由不存在`).toBeTruthy();
      expect(child!.meta?.module, `${key} 路由 module 不一致`).toBe(DEPARTMENTS[key].module);
    }
  });
});
