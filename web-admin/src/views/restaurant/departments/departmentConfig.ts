/**
 * 餐饮四部门驾驶舱的配置。
 *
 * 四个部门共用一套骨架（头部 / KPI 带 / 排行明细 / AI 入口 / 建议 + 功能入口），
 * 只有内容不同 —— 所以差异集中在这份纯数据里，页面组件只有一个。
 * 新增第五个部门不需要写新组件。
 *
 * ⚠️ 这里**不放假数据**。人事读取预测排班 FactBook；模拟预订在源头和页面上
 * 始终明标，不能把模拟行伪装成真实平台预订。
 */

export type DeptKey = 'ops' | 'marketing' | 'hr' | 'finance' | 'procurement';

/** KPI 的取值路径与格式。`money: true` 的项在无价格权限时显示「—」。 */
export interface DeptKpi {
  label: string;
  /**
   * 取值路径，相对于接口返回的 data。例: 'totals.totalWastageCost'
   *
   * 🔴 **必须写 camelCase**：`pythonFetch` 出口有 `transformKeys()`，把后端的
   * snake_case 全部转成 camelCase。照抄后端字段名(total_wastage_cost)取不到值，
   * 而 `pickPath` 取不到只返回 undefined → KPI 显示「—」，**不报错**。
   * 单测和类型检查都发现不了，只有打开页面才看得见。
   */
  path: string;
  money?: boolean;
  /** 比率：后端给 0~1 或 0~100，统一按 `rate01` 标注哪种 */
  percent?: boolean;
  rate01?: boolean;
  hint?: string;
  /**
   * 「这个指标算不算得出来」的依据路径。该值为 0 / 缺失时，本 KPI 显示「—」而不是 0。
   *
   * 🔴 为什么需要：后端算不出来时返回的是 **0**，不是 null。毛利率照原样渲染就成了
   * 「0.0%」—— 读起来是「毛利率真的是零」，而实际是「一道可算毛利的菜都没有」。
   * 拿 0 冒充「没有数据」是本项目反复出问题的那一类，这里挡住。
   */
  basisPath?: string;
  /** 依据不成立时给用户的解释，显示在 KPI 带下方 */
  basisHint?: string;
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

/**
 * ③ 趋势图。`endpoint` 里的 `{days}` 会被当前窗口替换。
 * 两种返回形状都存在于现有 Gold 接口，用 `shape` 指明，不做运行时猜测。
 */
export interface DeptTrend {
  title: string;
  unit: string;
  money?: boolean;
  /**
   * 'ops-kpi' → {success,data:[{date,value}]}
   * 'revenue-points' → {points:[{date,revenue}]}
   * 'staffing-daily' → {success,data:{dailyRows:[{date,predictedGuests}]}}
   */
  shape: 'ops-kpi' | 'revenue-points' | 'staffing-daily';
  endpoint: string;
}

export interface DeptConfig {
  key: DeptKey;
  title: string;
  /** 一句话说明本部门在餐饮经营链中的责任，不用菜单名代替职责。 */
  description: string;
  /** 页面头部展示的三个高频责任。 */
  responsibilities: string[];
  /** 跨部门问题的明确交接方向。 */
  handoff: string;
  /** 权限模块名，与 store/modules/permission.ts 的四个部门键一致 */
  module: string;
  /** 部门身份色 —— 只用于标题前那颗圆点，绝不整页染色 */
  accent: string;
  /**
   * 数据源。null = 该部门尚无可用数据源，页面走 emptyState。
   *  - 'ops-summary'  → /api/smartbi/restaurant-ops/summary（含 totals 与 margin）
   *  - 'kpi-summary'  → /api/smartbi/gold/kpi-summary
   *  - 'staffing-summary' → /api/smartbi/restaurant/staffing/dashboard?horizon=tomorrow
   */
  source: 'ops-summary' | 'kpi-summary' | 'staffing-summary' | null;
  kpis: DeptKpi[];
  /** ③ 趋势图；无则不渲染该区（人事） */
  trend?: DeptTrend;
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
    description: '把领料、损耗、盘点和配方串成可追踪的后厨日常。',
    responsibilities: ['领料闭环', '损耗核对', '盘点与配方'],
    handoff: '价格与供应商问题交采购；经营优先级和人员协调交店长。',
    module: 'restaurantOps',
    accent: '#0F7B8A',
    source: 'ops-summary',
    kpis: [
      { label: '损耗金额', path: 'totals.totalWastageCost', money: true },
      { label: '损耗次数', path: 'totals.totalWastage' },
      { label: '领料成本', path: 'totals.totalReqCost', money: true },
      { label: '盘亏总量', path: 'totals.totalShortage' },
      { label: '有数据天数', path: 'totals.activeDays', hint: '窗口内实际有记录的天数' },
    ],
    trend: {
      title: '损耗金额趋势',
      unit: '元',
      money: true,
      shape: 'ops-kpi',
      endpoint: '/api/smartbi/restaurant-ops/daily-trend?kpi_kind=wastage_cost&days={days}',
    },
    ranking: {
      title: '领用成本前列食材',
      path: 'top5Ingredients',
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
    description: '从营收、门店、菜品和渠道里找到增长与掉队位置。',
    responsibilities: ['经营表现', '菜品结构', '门店与渠道'],
    handoff: '促销和门店动作交店长；成本可行性与财务共同确认。',
    module: 'restaurantMarketing',
    accent: '#B4652F',
    source: 'kpi-summary',
    kpis: [
      { label: '营收', path: 'revenue', money: true },
      { label: '订单数', path: 'billCount' },
      { label: '菜品件数', path: 'itemCount' },
      { label: '客流', path: 'customerCount' },
      { label: '门店数', path: 'storeCount' },
    ],
    trend: {
      title: '营收趋势',
      unit: '元',
      money: true,
      shape: 'revenue-points',
      endpoint: '/api/smartbi/gold/daily-trend',
    },
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
    description: '把毛利、食材成本、价格异常和供应商对账放在同一口径下。',
    responsibilities: ['毛利口径', '成本归因', '价格与对账'],
    handoff: '异常数量交运营复核；异常价格交采购核对；口径调整由老板或财务确认。',
    module: 'restaurantFinance',
    accent: '#2C7A4B',
    // 与运营同一个接口：那一次调用同时返回 totals(后厨) 与 margin(毛利)
    source: 'ops-summary',
    kpis: [
      // 三项都以「有可算毛利的菜品」为前提。dishCountWithCost=0 时后端返回 0,
      // 直接渲染会变成「毛利率 0.0%」—— 那是假的精确。
      {
        label: '毛利率', path: 'margin.avgMarginRate', percent: true, rate01: true,
        basisPath: 'margin.dishCountWithCost',
        basisHint: '还没有可算毛利的菜品 —— 需要 POS 菜名与配方成本对上，毛利率与毛利额因此无法计算（不是 0）',
      },
      {
        label: 'POS 营收', path: 'margin.totalPosRevenue', money: true,
        basisPath: 'margin.dishCountWithCost',
      },
      {
        label: '毛利额', path: 'margin.totalGrossProfit', money: true,
        basisPath: 'margin.dishCountWithCost',
      },
      {
        label: '已核成本菜品',
        path: 'margin.dishCountWithCost',
        hint: '有配方成本可算毛利的菜品数',
      },
    ],
    trend: {
      title: '食材成本趋势',
      unit: '元',
      money: true,
      shape: 'ops-kpi',
      endpoint: '/api/smartbi/restaurant-ops/daily-trend?kpi_kind=requisition_cost&days={days}',
    },
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

  /**
   * 采购（2026-08-06 Steve 拍板独立成第五个部门）。
   *
   * ⚠️ KPI 只用**实打过接口确认存在**的字段。采购最想看的「供应商比价 / 采购价
   * 异常 / 采购订单」在 MOCK_REST 是 0 行（`agg_supplier_price` 空），所以本部门
   * 先按「进货与消耗」组织：领料是采购需求信号，盘点差异是账实校验，
   * TOP 食材是谈价对象。比价类等数据接进来再加，**不先摆一个恒空的卡**。
   */
  procurement: {
    key: 'procurement',
    title: '采购',
    description: '从领用量、进货与盘点差异里看清该买多少、该跟谁谈价。',
    responsibilities: ['进货与验收', '领用需求', '账实差异'],
    handoff: '价格口径与对账交财务；出品与备料需求交店长。',
    module: 'restaurantProcurement',
    accent: '#2F6FB4',
    source: 'ops-summary',
    kpis: [
      { label: '领料成本', path: 'totals.totalReqCost', money: true, hint: '窗口内领用食材成本，是采购量的直接依据' },
      { label: '领料单数', path: 'totals.totalRequisitions' },
      { label: '领料总量', path: 'totals.totalReqQty' },
      { label: '盘亏总量', path: 'totals.totalShortage', hint: '账面多于实盘的部分' },
      { label: '盘盈总量', path: 'totals.totalSurplus' },
      { label: '有数据天数', path: 'totals.activeDays', hint: '窗口内实际有记录的天数' },
    ],
    trend: {
      title: '领料成本趋势',
      unit: '元',
      money: true,
      shape: 'ops-kpi',
      endpoint: '/api/smartbi/restaurant-ops/daily-trend?kpi_kind=requisition_cost&days={days}',
    },
    ranking: {
      title: '领用成本前列食材（优先谈价对象）',
      path: 'top5Ingredients',
      nameKey: 'name',
      categoryKey: 'category',
      valueKey: 'cost',
      valueLabel: '领用成本',
      valueMoney: true,
    },
    entries: [
      { title: '供应商进货录入', path: '/restaurant/supplier-delivery' },
      { title: '领料管理', path: '/restaurant/requisitions' },
      { title: '盘点管理', path: '/restaurant/stocktaking' },
    ],
    questions: [
      '最近30天领料趋势',
      '哪些食材经常盘亏',
      '最近7天损耗最多的食材',
    ],
    emptyState: {
      title: '还没有可用的进货与领用数据',
      detail: '采购看板依赖领料、盘点记录。这些还没开始录入时，这里不显示 0，而是空着。',
      todos: [
        '先在「领料管理」录入日常领用',
        '按周做一次盘点，账实差异才有对照',
        '供应商送货用「供应商进货录入」登记',
      ],
      actionLabel: '去领料管理',
      actionPath: '/restaurant/requisitions',
    },
  },

  hr: {
    key: 'hr',
    title: '人事',
    description: '把客流时段与在岗人数对齐，形成排班和人效依据。',
    responsibilities: ['时段人力', '排班基准', '人效目标'],
    handoff: '门店需求由店长提出；人员配置完成后回到经营数据验证效果。',
    module: 'restaurantHr',
    accent: '#6455A0',
    source: 'staffing-summary',
    kpis: [
      { label: '明日预订覆盖', path: 'summary.reservationCoveragePct', percent: true },
      { label: '明日预测客流', path: 'summary.predictedGuests' },
      { label: '建议人数', path: 'summary.recommendedStaff' },
      { label: '现有人数', path: 'summary.currentStaff' },
      { label: '正向缺口', path: 'summary.positiveGap' },
      { label: '置信度', path: 'summary.confidencePct', percent: true },
    ],
    trend: {
      title: '下周预测客流',
      unit: '人次',
      shape: 'staffing-daily',
      endpoint: '/api/smartbi/restaurant/staffing/dashboard?horizon=week',
    },
    entries: [
      { title: '预测排班', path: '/restaurant/staffing' },
    ],
    questions: [
      '明天怎么排班',
      '下周需要多少兼职',
      '下个月各店人效安排',
    ],
  },
};

export const DEPARTMENT_ORDER: DeptKey[] = ['ops', 'marketing', 'procurement', 'hr', 'finance'];

/** 从接口返回的对象里按 'a.b.c' 取值；取不到返回 undefined（不返回 0）。 */
export function pickPath(source: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((acc, key) => {
    if (acc && typeof acc === 'object' && key in (acc as Record<string, unknown>)) {
      return (acc as Record<string, unknown>)[key];
    }
    return undefined;
  }, source);
}
