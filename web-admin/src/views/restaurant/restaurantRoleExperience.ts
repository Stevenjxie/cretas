import type { ModuleName } from '@/store/modules/permission';

export type RestaurantRole =
  | 'restaurant_owner'
  | 'restaurant_manager'
  | 'restaurant_purchaser'
  | 'restaurant_chef';

export interface RestaurantRoleAction {
  title: string;
  description: string;
  path: string;
  module: ModuleName;
  emphasis?: 'primary' | 'normal';
}

export interface RestaurantQuestionGroup {
  label: string;
  questions: string[];
}

export interface RestaurantRoleExperience {
  role: RestaurantRole;
  roleLabel: string;
  workspaceLabel: string;
  headline: string;
  summary: string;
  responsibilities: string[];
  handoff: string;
  actions: RestaurantRoleAction[];
  ai: {
    title: string;
    description: string;
    placeholder: string;
    primaryQuestions: string[];
    moreGroups: RestaurantQuestionGroup[];
  };
}

const SHARED_ANALYSIS_GROUPS: RestaurantQuestionGroup[] = [
  {
    label: '经营总览',
    questions: ['总营业额', '今年营收', '本季度营收', '营收趋势'],
  },
  {
    label: '菜品',
    questions: ['哪个菜卖得最好', '慢销菜品', '外卖点什么多', '高频好评词'],
  },
  {
    label: '门店',
    questions: ['哪家店业绩最好', '门店销售对比', '哪家店差评多'],
  },
  {
    label: '顾客与评价',
    questions: ['客户评价怎么样', 'VIP客户分析', '有哪些差评', '平台口碑如何'],
  },
  {
    label: '渠道与促销',
    questions: ['外卖占比', '优惠券使用', '客单价', '周末周中对比'],
  },
];

const EXPERIENCES: Record<RestaurantRole, RestaurantRoleExperience> = {
  restaurant_owner: {
    role: 'restaurant_owner',
    roleLabel: '餐饮老板',
    workspaceLabel: '经营决策台',
    headline: '先看全局，再把决定交给负责人',
    summary: '聚合运营、市场、人事和财务，只突出今天需要拍板的异常、趋势和行动。',
    responsibilities: ['经营结果与目标', '门店与菜品组合', '成本和现金口径'],
    handoff: '决策后交给店长、采购或财务执行；操作仍需预览确认。',
    actions: [
      { title: '经营驾驶舱', description: '看全局营收、门店与渠道', path: '/smart-bi/dashboard', module: 'analytics', emphasis: 'primary' },
      { title: '财务驾驶舱', description: '核对毛利、成本与价格异常', path: '/restaurant/finance', module: 'restaurantFinance' },
      { title: '门店对比', description: '找到领先与掉队门店', path: '/restaurant/analytics/stores', module: 'restaurantMarketing' },
      { title: '问经营 AI', description: '让大模型综合解释并给行动建议', path: '/smart-bi/query', module: 'analytics' },
    ],
    ai: {
      title: '老板经营 AI',
      description: '从真实经营数据取数，由大模型综合解释、比较并形成可执行建议。',
      placeholder: '例如：这周营收怎么提高，今天先做哪几个动作',
      primaryQuestions: [
        '这个星期营收比上周怎么提高？给我今天能做的动作',
        '厨房出餐慢和差评变多，今天先改哪三个动作？',
        '哪家店业绩最好',
        '根据菜品毛利和成本，帮我算一个适合今天推的小套餐',
      ],
      moreGroups: SHARED_ANALYSIS_GROUPS,
    },
  },
  restaurant_manager: {
    role: 'restaurant_manager',
    roleLabel: '餐饮店长',
    workspaceLabel: '今日营运台',
    headline: '把今天的运营问题闭环',
    summary: '围绕领料、损耗、经营表现和人员安排组织任务，财务数据只读。',
    responsibilities: ['今日营运与异常', '门店执行与目标', '排班和跨岗协同'],
    handoff: '采购异常交采购，成本口径交老板或财务，后厨执行交厨师长。',
    actions: [
      { title: '运营驾驶舱', description: '先看损耗、领料和盘点', path: '/restaurant/ops', module: 'restaurantOps', emphasis: 'primary' },
      { title: '经营看板', description: '跟进营收、客流和目标', path: '/restaurant/analytics/role-kpi', module: 'restaurantMarketing' },
      { title: '人事驾驶舱', description: '查看人效数据准备状态', path: '/restaurant/hr', module: 'restaurantHr' },
      { title: '问营运 AI', description: '让大模型定位问题并排优先级', path: '/smart-bi/query', module: 'analytics' },
    ],
    ai: {
      title: '店长营运 AI',
      description: '结合门店经营事实，由大模型帮助排查异常、比较门店并给出当天可执行的顺序。',
      placeholder: '例如：最近30天哪里异常，今天先处理什么',
      primaryQuestions: [
        '最近30天营收趋势',
        '本月哪个菜卖得最好',
        '哪些食材经常盘亏',
        '哪家店业绩最好',
      ],
      moreGroups: SHARED_ANALYSIS_GROUPS,
    },
  },
  restaurant_purchaser: {
    role: 'restaurant_purchaser',
    roleLabel: '餐饮采购',
    workspaceLabel: '采购协同台',
    headline: '从报货到对账，一条链处理完',
    summary: '优先处理报货计划、到货验收、供应商价格异常与月度对账。',
    responsibilities: ['报货与采购计划', '供应商到货协同', '价格异常与对账'],
    handoff: '数量与品项向厨师长确认，经营优先级向店长确认，审核交财务。',
    actions: [
      { title: '报货 / 采购计划', description: '汇总并处理后厨需求', path: '/procurement/requisitions/my', module: 'procurement', emphasis: 'primary' },
      { title: '供应商进货', description: '登记到货与验收信息', path: '/restaurant/supplier-delivery', module: 'dashboard' },
      { title: '价格异常', description: '检查涨价与异常波动', path: '/restaurant/price-anomaly', module: 'restaurantFinance' },
      { title: '供应商对账', description: '核对月度采购金额', path: '/restaurant/supplier-reconciliation', module: 'restaurantFinance' },
    ],
    ai: {
      title: '采购协同 AI',
      description: '基于采购、领料和供应商事实数据，由大模型解释波动并提示需要核对的对象。',
      placeholder: '例如：最近30天哪些食材或供应商需要优先核对',
      primaryQuestions: [
        '最近30天领料趋势',
        '有没有供应商偷偷涨价',
        '本月损耗金额最高的食材',
        '哪些食材经常盘亏',
      ],
      moreGroups: [
        { label: '采购与成本', questions: ['食材成本最高的菜', '毛利最低的菜品', '进货 Top 10', '采购金额排名'] },
        ...SHARED_ANALYSIS_GROUPS.slice(0, 2),
      ],
    },
  },
  restaurant_chef: {
    role: 'restaurant_chef',
    roleLabel: '厨师长',
    workspaceLabel: '后厨执行台',
    headline: '报货、领料、盘点按顺序完成',
    summary: '只展示后厨执行需要的数量、状态和菜品信息；采购价格保持隐藏。',
    responsibilities: ['后厨报货', '领料与盘点', '损耗和配方执行'],
    handoff: '价格与供应商问题交采购，经营目标与人员协调交店长。',
    actions: [
      { title: '报货 / 采购计划', description: '提交后厨品项需求', path: '/procurement/requisitions/my', module: 'procurement', emphasis: 'primary' },
      { title: '领料管理', description: '查看和登记领料', path: '/restaurant/requisitions', module: 'restaurantOps' },
      { title: '供应商进货', description: '完成到货验收入库', path: '/restaurant/supplier-delivery', module: 'dashboard' },
      { title: '配方管理', description: '维护菜品用料口径', path: '/restaurant/recipes', module: 'restaurantOps' },
    ],
    ai: {
      title: '后厨执行 AI',
      description: '从领料、损耗、盘点和菜品数据取数，由大模型帮助发现异常与安排检查顺序。',
      placeholder: '例如：最近30天领料和损耗有什么异常',
      primaryQuestions: [
        '最近30天领料趋势',
        '哪些食材经常盘亏',
        '哪个菜卖得最好',
        '最近30天损耗次数最多的食材',
      ],
      moreGroups: [
        { label: '后厨运营', questions: ['慢销菜品', '外卖点什么多', '本月哪个菜卖得最好', '最近30天领料趋势'] },
      ],
    },
  },
};

const ADMIN_OWNER_ROLES = new Set(['factory_super_admin', 'platform_admin', 'permission_admin']);

export function normalizeRestaurantRole(role?: string | null): RestaurantRole {
  if (role && role in EXPERIENCES) return role as RestaurantRole;
  if (role && ADMIN_OWNER_ROLES.has(role)) return 'restaurant_owner';
  return 'restaurant_manager';
}

export function getRestaurantRoleExperience(role?: string | null): RestaurantRoleExperience {
  return EXPERIENCES[normalizeRestaurantRole(role)];
}

export const RESTAURANT_ALL_ROLES: RestaurantRole[] = [
  'restaurant_owner',
  'restaurant_manager',
  'restaurant_purchaser',
  'restaurant_chef',
];

export const RESTAURANT_DECISION_ROLES: RestaurantRole[] = [
  'restaurant_owner',
  'restaurant_manager',
];

export const RESTAURANT_DATA_STEWARD_ROLES: RestaurantRole[] = [
  'restaurant_owner',
  'restaurant_manager',
];
