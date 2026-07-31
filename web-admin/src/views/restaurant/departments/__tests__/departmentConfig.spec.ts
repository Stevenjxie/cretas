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

/**
 * 端点前缀 —— Python 侧**两个 router 的挂载方式不同**，这一点只看前端代码看不出来：
 *
 *   main.py: include_router(restaurant_ops_gold.router, prefix="/api/smartbi")
 *            而 restaurant_ops_gold 自己 APIRouter(tags=...) **没有 prefix**
 *            → 真实路径 /api/smartbi/restaurant-ops/...
 *
 *   main.py: include_router(gold_reads.router, prefix="/api/smartbi")
 *            而 gold_reads 自己 APIRouter(prefix="/gold")
 *            → 真实路径 /api/smartbi/gold/...
 *
 * 我第一版给 restaurant-ops 也写了 `/gold`，结果四个部门页全部 404 ——
 * 页面只显示「加载失败」，单测全绿，**只有真打开页面才看得见**。
 */
describe('部门数据源的端点前缀', () => {
  const collect = (): string[] => {
    const out: string[] = [];
    for (const key of DEPARTMENT_ORDER) {
      const t = DEPARTMENTS[key].trend;
      if (t) out.push(t.endpoint);
    }
    return out;
  };

  it('restaurant-ops 系列不带 /gold（那个 router 没有 prefix）', () => {
    for (const ep of collect()) {
      if (!ep.includes('restaurant-ops')) continue;
      expect(ep, `${ep} 多了 /gold —— 会 404`).not.toContain('/gold/restaurant-ops');
      expect(ep).toContain('/api/smartbi/restaurant-ops/');
    }
  });

  it('gold_reads 系列必须带 /gold（那个 router 有 prefix）', () => {
    for (const ep of collect()) {
      if (ep.includes('restaurant-ops')) continue;
      expect(ep, `${ep} 缺 /gold —— 会 404`).toContain('/api/smartbi/gold/');
    }
  });
});

/**
 * 取值路径必须是 camelCase。
 *
 * `pythonFetch` 出口有 `transformKeys()`，把后端返回的 snake_case 全部转成
 * camelCase。照抄后端字段名（`total_wastage_cost`）会取不到值，而 `pickPath`
 * 取不到只返回 `undefined` → KPI 渲染成「—」，**不抛错、不报警**。
 *
 * 2026-07-31 实际后果：四个部门页的 KPI 带与排行表全是空的，而趋势图正常
 * （趋势只用 `date` / `value` 两个单词字段，camelCase 转换对它没有影响）——
 * 这个"一半正常一半空"的形态极具迷惑性。单测全绿、类型检查全绿，
 * **只有打开页面才看得见**。
 */
describe('取值路径必须 camelCase', () => {
  it('KPI 路径不含下划线', () => {
    const bad: string[] = [];
    for (const key of DEPARTMENT_ORDER) {
      for (const kpi of DEPARTMENTS[key].kpis) {
        if (kpi.path.includes('_')) bad.push(`${key}.${kpi.label}: ${kpi.path}`);
      }
    }
    expect(bad, `这些路径照抄了后端 snake_case, 取不到值:\n${bad.join('\n')}`).toEqual([]);
  });

  it('排行表的路径与字段名不含下划线', () => {
    const bad: string[] = [];
    for (const key of DEPARTMENT_ORDER) {
      const r = DEPARTMENTS[key].ranking;
      if (!r) continue;
      for (const [label, v] of Object.entries({
        path: r.path, nameKey: r.nameKey, valueKey: r.valueKey,
        categoryKey: r.categoryKey ?? '',
      })) {
        if (v.includes('_')) bad.push(`${key}.${label}: ${v}`);
      }
    }
    expect(bad, bad.join('\n')).toEqual([]);
  });
});
