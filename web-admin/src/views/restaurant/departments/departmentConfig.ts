/**
 * 餐饮四部门驾驶舱的配置。
 *
 * 四个部门共用一套骨架（头部 / KPI 带 / 排行明细 / AI 入口 / 建议 + 功能入口），
 * 只有内容不同 —— 所以差异集中在这份纯数据里，页面组件只有一个。
 * 新增第五个部门不需要写新组件。
 *
 * ⚠️ 这里**不放假数据**。人事目前没有任何事实数据（`fact_staffing_daypart` 全表 0 行），
 * 所以它的 `source` 是 null，页面走空态：写清楚缺哪两项 + 给配置入口，不显示 0，
 * 也不给假图表。
 */

export type DeptKey = 'ops' | 'marketing' | 'hr' | 'finance';

/** KPI 的取值路径与格式。`money: true` 的项在无价格权限时显示「—」。 */
export interface DeptKpi {
  label: string;
  /** 取值路径，相对于接口返回的 data。例: 'totals.total_wastage_cost' */
  path: string;
  money?: boolean;
  /** 比率：后端给 0~1 或 0~100，统一按 `rate01` 标注哪种 */
  percent?: boolean;
  rate01?: boolean;
  hint?: string;
}

export interface DeptEntry {
  title: string;
  path: string;
}

export interface DeptEmptyState {
  title: string;
  detail: string;
  todos: string[];
  actionLabel: string;
  actionPath: string;
}

export interface DeptConfig {
  key: DeptKey;
  title: string;
  /** 权限模块名，与 store/modules/permission.ts 的四个部门键一致 */
  module: string;
  /** 部门身份色 —— 只用于标题前那颗圆点，绝不整页染色 */
  accent: string;
  /**
   * 数据源。null = 该部门尚无可用数据源，页面走 emptyState。
   *  - 'ops-summary'  → /api/smartbi/gold/restaurant-ops/summary（含 totals 与 margin）
   *  - 'kpi-summary'  → /api/smartbi/gold/kpi-summary
   */
  source: 'ops-summary' | 'kpi-summary' | null;
  kpis: DeptKpi[];
  /** ④ 排行明细的取值路径与列定义；无则不渲染该区 */
  ranking?: {
    title: string;
    path: string;
    nameKey: string;
    valueKey: string;
    valueLabel: string;
    valueMoney?: boolean;
    categoryKey?: string;
  };
  entries: DeptEntry[];
  /** ⑤ AI 入口的推荐问题 —— 必须是系统真答得了的，不是口号 */
  questions: string[];
  emptyState?: DeptEmptyState;
}

export const DEPARTMENTS: Record<DeptKey, DeptConfig> = {
  ops: {
    key: 'ops',
    title: '运营',
    module: 'restaurantOps',
    accent: '#0F7B8A',
    source: 'ops-summary',
    kpis: [
      { label: '损耗金额', path: 'totals.total_wastage_cost', money: true },
      { label: '损耗次数', path: 'totals.total_wastage' },
      { label: '领料成本', path: 'totals.total_req_cost', money: true },
      { label: '盘亏总量', path: 'totals.total_shortage' },
      { label: '有数据天数', path: 'totals.active_days', hint: '窗口内实际有记录的天数' },
    ],
    ranking: {
      title: '领用成本前列食材',
      path: 'top_ingredients',
      nameKey: 'name',
      categoryKey: 'category',
      valueKey: 'cost',
      valueLabel: '领用成本',
      valueMoney: true,
    },
    entries: [
      { title: '领料管理', path: '/restaurant/requisitions' },
      { title: '损耗管理', path: '/restaurant/wastage' },
      { title: '盘点管理', path: '/restaurant/stocktaking' },
      { title: '配方管理', path: '/restaurant/recipes' },
    ],
    questions: [
      '本月损耗金额最高的食材',
      '哪些食材经常盘亏',
      '最近30天领料趋势',
    ],
  },

  marketing: {
    key: 'marketing',
    title: '市场',
    module: 'restaurantMarketing',
    accent: '#B4652F',
    source: 'kpi-summary',
    kpis: [
      { label: '营收', path: 'revenue', money: true },
      { label: '订单数', path: 'bills' },
      { label: '菜品件数', path: 'items' },
      { label: '客流', path: 'customers' },
      { label: '门店数', path: 'stores' },
    ],
    entries: [
      { title: '菜品分析', path: '/restaurant/analytics/dishes' },
      { title: '门店对比', path: '/restaurant/analytics/stores' },
      { title: '平台口碑', path: '/restaurant/analytics/platform' },
    ],
    questions: [
      '本月哪个菜卖得最好',
      '堂食和外卖的占比',
      '哪家店业绩最好',
    ],
  },

  finance: {
    key: 'finance',
    title: '财务',
    module: 'restaurantFinance',
    accent: '#2C7A4B',
    // 与运营同一个接口：那一次调用同时返回 totals(后厨) 与 margin(毛利)
    source: 'ops-summary',
    kpis: [
      { label: '毛利率', path: 'margin.avg_margin_rate', percent: true, rate01: true },
      { label: 'POS 营收', path: 'margin.total_pos_revenue', money: true },
      { label: '毛利额', path: 'margin.total_gross_profit', money: true },
      {
        label: '已核成本菜品',
        path: 'margin.dish_count_with_cost',
        hint: '有配方成本可算毛利的菜品数',
      },
    ],
    entries: [
      { title: '成本归因', path: '/restaurant/cost-attribution' },
      { title: '供应商月对账', path: '/restaurant/supplier-reconciliation' },
      { title: '价格异常预警', path: '/restaurant/price-anomaly' },
    ],
    questions: [
      '哪家店最赚钱',
      '毛利最低的菜品',
      '食材成本最高的菜',
    ],
  },

  hr: {
    key: 'hr',
    title: '人事',
    module: 'restaurantHr',
    accent: '#6455A0',
    // 🔴 目前没有任何事实数据: fact_staffing_daypart 全表 0 行(所有租户)。
    //    不给假数据, 走空态。补齐配置后把 source 改成对应接口即可。
    source: null,
    kpis: [],
    entries: [],
    questions: [
      '哪个时段人手不够',
      '上个月人效怎么样',
    ],
    emptyState: {
      title: '还不能算人效',
      detail: '各时段订单量已能从 POS 自动算出，还差两项配置：',
      todos: ['各时段在岗人数（基准）', '各时段目标人效'],
      actionLabel: '去配置人效基准',
      actionPath: '/restaurant/data-completeness',
    },
  },
};

export const DEPARTMENT_ORDER: DeptKey[] = ['ops', 'marketing', 'hr', 'finance'];

/** 从接口返回的对象里按 'a.b.c' 取值；取不到返回 undefined（不返回 0）。 */
export function pickPath(source: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((acc, key) => {
    if (acc && typeof acc === 'object' && key in (acc as Record<string, unknown>)) {
      return (acc as Record<string, unknown>)[key];
    }
    return undefined;
  }, source);
}
