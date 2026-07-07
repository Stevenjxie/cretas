export const RESTAURANT_OWNER_ACTION_SCENARIOS = [
  'cost_margin',
  'external_event_response',
  'inventory_reorder',
  'kitchen_quality',
  'operations_dispatch',
  'package',
  'revenue_growth',
  'seating_mix',
  'single_item_push',
  'staff_training',
  'staffing_schedule',
  'store_compare',
  'traffic_conversion',
] as const;

export type RestaurantOwnerActionScenario = typeof RESTAURANT_OWNER_ACTION_SCENARIOS[number];

export type RestaurantOwnerActionScenarioTerms = {
  scenario: RestaurantOwnerActionScenario;
  terms: string[];
};

export const OWNER_ACTION_SCENARIO_TERMS: RestaurantOwnerActionScenarioTerms[] = [
  {
    scenario: 'store_compare',
    terms: [
      '所有门店', '其他门店', '连锁店', '连锁', '区域经理', '品牌共性',
      '单店问题', '门店里', '哪家店', '最值得学习', '复制到',
    ],
  },
  {
    scenario: 'revenue_growth',
    terms: [
      '营收杠杆', '提高营收', '提升营收',
      '增加营收', '营收比', '营收差',
      '营收下滑', '营收下降', '营收掉',
      '营业额', '收入下滑', '收入下降',
      '本周变差', '这周变差', '生意变差',
    ],
  },
  {
    scenario: 'operations_dispatch',
    terms: [
      '仓管+厨师长+前台', '仓管厨师长前台',
      '仓管、厨师长、前台', '分别盯什么',
      '分别要做什么', '谁做什么', '各岗位',
      '派工', '分工', '管理层减少工作',
    ],
  },
  {
    scenario: 'seating_mix',
    terms: [
      '桌型', '桌子', '桌数', '二人桌', '四人桌',
      '拼桌', '翻台', '座位', '排队', '等位',
    ],
  },
  {
    scenario: 'inventory_reorder',
    terms: [
      '库存预警', '补货', '采购补货', '安全库存', '临期', '缺货',
      '备货缺口', '库存风险', '备货', '备菜', '仓管今天', '具体补什么',
      '原料', '食材', '报损',
    ],
  },
  {
    scenario: 'cost_margin',
    terms: [
      '成本', '毛利', '利润', '采购', 'bom', 'BOM',
      '损耗', '验收', '涨价', '报损', '月盘点',
      '盘点', '理论用量', '实际用量', '亏毛利',
    ],
  },
  {
    scenario: 'external_event_response',
    terms: [
      '商场活动', '商圈活动', '周边活动',
      '节日', '天气', '下雨', '雨天', '高温',
      '亲子节', '会员日', '外部活动',
    ],
  },
  {
    scenario: 'traffic_conversion',
    terms: [
      '客流', '画像', '商圈', '交通', '周边',
      '竞品', '同商圈', '引流', '门口', '路过',
      '进店', '曝光', '核销', '承接', '平台',
      '页面', '首图', '美团', '大众点评',
      '抖音', '团购',
    ],
  },
  {
    scenario: 'staff_training',
    terms: [
      '培训', '训练', '服务员', '服务态度', '服务差评', '服务慢',
      '话术', '催菜', '意识', '店长', '开班前', '评论', '顾客',
      '复购', '最在意', '评价', '差评', '小红书', '口碑', '投诉', '体验',
    ],
  },
  {
    scenario: 'kitchen_quality',
    terms: [
      '厨房', '后厨', '厨师长', '出餐', '上菜慢',
      '退菜', '重做', '口味', '太咸', '出品',
    ],
  },
  {
    scenario: 'staffing_schedule',
    terms: [
      '排班', '人手', '员工', '服务员', '前台',
      '前厅', '班次', '调度', '加班', '加人',
      '多加', '工时', '人效',
    ],
  },
  {
    scenario: 'single_item_push',
    terms: [
      '主推单品', '主推', '单品', '招牌', '爆品', '引流菜',
      '低价值', '拉动加购', '首屏', '短视频',
    ],
  },
  {
    scenario: 'package',
    terms: [
      '套餐', '小套餐', '菜品组合', '推菜',
      '客单', '加购', '搭配', '单人餐', '双人餐',
      '外卖', '不打折', '不想打折', '拉营收',
    ],
  },
];

export const OWNER_ACTION_DECISION_TERMS = [
  '决策', '建议', '直接建议', '怎么提高',
  '怎么优化', '怎么做', '怎么调', '怎么排',
  '怎么改', '怎么安排', '怎么培训', '怎么训练', '怎么判断',
  '如何提高',
  '如何优化', '动作', '落地', '先做', '先改',
  '帮我算', '推荐', '主推', '要不要',
  '影响', '会影响', '有什么影响',
  '哪三个', '三个动作', '拉起来', '先管',
  '先查', '先看', '不要多备', '别亏', '配合',
  '该改', '只能', '还是', '能不能', '该不该', '是否',
  '不用等', '要考虑',
  '继续拆', '拆细', '营收杠杆',
];

export const PACKAGE_PRIORITY_TERMS = [
  '套餐', '小套餐', '菜品组合', '双人餐',
  '单人餐',
];

export function includesAny(text: string, terms: string[]): boolean {
  return terms.some((term) => text.includes(term));
}

export function inferOwnerActionScenario(query: string): RestaurantOwnerActionScenario | '' {
  const text = query.trim();
  if (!text) return '';
  if (!includesAny(text, OWNER_ACTION_DECISION_TERMS)) return '';
  if (includesAny(text, PACKAGE_PRIORITY_TERMS)) {
    return 'package';
  }
  for (const entry of OWNER_ACTION_SCENARIO_TERMS) {
    if (includesAny(text, entry.terms)) {
      return entry.scenario;
    }
  }
  return 'cost_margin';
}

export function isOwnerActionFollowupText(query: string): boolean {
  return /继续|具体|详细|为什么|怎么做|怎么调|怎么排|怎么改|怎么讲|怎么算|重新算|怎么备货|怎么验收|哪三个数|三个数|哪些数|先看哪|看什么|判断|验证|落地|然后呢|执行细节|拆给我|哪些事情|先不要|不要做|别做|要不要停|停不停|换一个|风险|避开|哪一个动作|放到哪些入口|复制哪家店/.test(query);
}
