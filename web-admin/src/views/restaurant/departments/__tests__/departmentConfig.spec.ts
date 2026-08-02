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

  it('人事读取预测排班 FactBook 并提供真实问题入口', () => {
    const hr = DEPARTMENTS.hr;
    expect(hr.source).toBe('staffing-summary');
    expect(hr.kpis.map((item) => item.path)).toEqual(expect.arrayContaining([
      'summary.reservationCoveragePct',
      'summary.predictedGuests',
      'summary.recommendedStaff',
      'summary.currentStaff',
      'summary.positiveGap',
      'summary.confidencePct',
    ]));
    expect(hr.entries).toContainEqual({ title: '预测排班', path: '/restaurant/staffing' });
    expect(hr.questions).toEqual(['明天怎么排班', '下周需要多少兼职', '下个月各店人效安排']);
  });

  it('有数据源的部门必须有 KPI —— 否则页面是空的', () => {
    for (const key of DEPARTMENT_ORDER) {
      const cfg = DEPARTMENTS[key];
      if (!cfg.source) continue;
      expect(cfg.kpis.length, `${key} 有数据源却没有 KPI`).toBeGreaterThan(0);
    }
  });

  it('四个部门都有职责说明与跨部门交接', () => {
    for (const key of DEPARTMENT_ORDER) {
      const cfg = DEPARTMENTS[key];
      expect(cfg.description.length, `${key} 缺职责说明`).toBeGreaterThan(10);
      expect(cfg.responsibilities.length, `${key} 缺职责列表`).toBeGreaterThanOrEqual(3);
      expect(cfg.handoff.length, `${key} 缺交接规则`).toBeGreaterThan(10);
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

/**
 * 页头的期间选择器必须**真的**传到后端。
 *
 * 两类端点收窗口的方式不同，而且**默认行为相反**：
 *
 *   /api/smartbi/restaurant-ops/*  收 `days`；不传则默认 30
 *   /api/smartbi/gold/*            收 `start_date`/`end_date`；**不传 = 全部历史**
 *
 * 第一版我给 gold 系列漏传日期，页头写着「最近 30 天」而图表画的是 576 天全量 ——
 * 期间选择器做出了页面兑现不了的承诺，**且不报错**。
 * 这与同一天在 AI resolver 侧修过三次（#2076 / #2081）的是同一类缺陷。
 */
describe('期间选择器必须落到请求上', () => {
  it('gold 系列的趋势端点不能自带 days（那个参数它不认）', () => {
    for (const key of DEPARTMENT_ORDER) {
      const t = DEPARTMENTS[key].trend;
      if (!t || t.shape !== 'revenue-points') continue;
      expect(t.endpoint, `${key}: gold 端点不认 days, 必须靠 start_date/end_date`)
        .not.toContain('days');
    }
  });

  it('restaurant-ops 系列的趋势端点必须带 {days} 占位', () => {
    for (const key of DEPARTMENT_ORDER) {
      const t = DEPARTMENTS[key].trend;
      if (!t || t.shape !== 'ops-kpi') continue;
      expect(t.endpoint, `${key}: 少了 {days}, 窗口切换不会生效`).toContain('{days}');
    }
  });
});

/**
 * 「算不出来」不能显示成 0。
 *
 * 后端算不出毛利时返回的是 **0** 而不是 null。照原样渲染就成了「毛利率 0.0%」——
 * 读起来是「毛利率真的是零」，实际是「一道可算毛利的菜都没有」。
 * 线上 DEMO_REST 实测 `dish_count_with_cost = 0`，页面当时就显示 0.0% / ¥0 / ¥0。
 *
 * 这是本项目反复出问题的那一类（拿 0 冒充没有数据），所以钉住。
 */
describe('依赖前提的 KPI 必须声明依据', () => {
  it('毛利相关 KPI 都挂了 basisPath', () => {
    const margin = DEPARTMENTS.finance.kpis.filter((k) => k.path.startsWith('margin.'));
    expect(margin.length).toBeGreaterThan(0);
    for (const k of margin) {
      // 「已核成本菜品」本身就是那个依据，它显示 0 是正确的
      if (k.path === 'margin.dishCountWithCost') continue;
      expect(k.basisPath, `${k.label} 缺 basisPath, 算不出来时会显示成 0`).toBeTruthy();
    }
  });

  it('至少一条 basisHint 说明了为什么算不出来', () => {
    const hints = DEPARTMENTS.finance.kpis.filter((k) => k.basisHint);
    expect(hints.length).toBeGreaterThan(0);
    for (const k of hints) {
      // 必须解释原因，不能只说「无数据」
      expect(k.basisHint!.length).toBeGreaterThan(10);
      expect(k.basisHint, '要写明不是 0').toContain('不是 0');
    }
  });

  it('basisPath 指向的字段本身也在 KPI 里 —— 用户能看见依据的值', () => {
    const paths = new Set(DEPARTMENTS.finance.kpis.map((k) => k.path));
    for (const k of DEPARTMENTS.finance.kpis) {
      if (!k.basisPath) continue;
      expect(paths.has(k.basisPath), `${k.basisPath} 没有作为 KPI 展示, 用户看不到依据`)
        .toBe(true);
    }
  });
});
