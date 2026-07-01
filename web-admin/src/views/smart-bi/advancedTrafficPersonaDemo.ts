import type { AdvancedTrafficPersona, V2UnifiedReport } from '@/api/smartbi/restaurant-v2';

export const DEFAULT_ADVANCED_TRAFFIC_PERSONA: AdvancedTrafficPersona = {
  moduleName: '高级客流画像分析',
  requiresEnablement: true,
  demoMode: true,
  dataNote:
    '当前为 web-admin demo 模拟数据，用于展示接入腾讯位置大数据、百度慧眼或高德商业位置数据后的分析形态。真实启用需要客户授权、供应商商务开通和聚合数据合规审查。',
  storeContext: {
    storeName: '青花椒百货商圈样板',
    city: '上海',
    businessDistrict: '人民广场 / 南京东路',
    mallName: '第一百货商业中心 / 大丸百货',
    subSector: '川菜连锁',
    period: 'Demo',
  },
  enablement: {
    status: '需额外开通',
    scope: '按客户、商圈、门店包或年度服务授权；MVP 阶段仅展示模拟聚合字段。',
    requirements: [
      '确认客户授权门店和竞品分析范围',
      '完成腾讯/百度/高德等供应商商务开通',
      '只接入聚合统计字段，不展示个人轨迹',
      '与 POS、会员、供应链和点评数据做字段映射',
    ],
    priceHint: {
      basis: '通常按项目、区域、门店包或年度服务计费',
      demoAssumption: '当前数据只用于演示高级模块价值，不作为真实经营结论',
    },
  },
  providers: [
    {
      provider: '腾讯位置大数据',
      accessMode: '商务授权',
      bestUse: '商圈客流、来源地、竞品重叠和停留时长',
    },
    {
      provider: '百度慧眼/百度地图',
      accessMode: '商务授权',
      bestUse: '区域热力、客群画像、时段趋势和消费力 proxy',
    },
    {
      provider: '高德/商业位置数据',
      accessMode: '开放 API + 商务数据',
      bestUse: 'POI 密度、竞品供给、动线和周边交通可达性',
    },
  ],
  fieldCatalog: [
    {
      key: 'daily_footfall',
      label: '日均路过客流',
      providerCoverage: ['腾讯', '百度'],
      demoValue: 21800,
      businessQuestion: '门口到底有多少可转化客流',
    },
    {
      key: 'capture_rate',
      label: '估算捕获率',
      providerCoverage: ['腾讯', '百度'],
      demoValue: 0.038,
      businessQuestion: '百货客流有多少被本店承接',
    },
    {
      key: 'competitor_overlap_index',
      label: '竞品重叠指数',
      providerCoverage: ['腾讯', '高德'],
      demoValue: 0.67,
      businessQuestion: '同商圈竞品是否抢同一类客',
    },
    {
      key: 'dwell_time_distribution',
      label: '停留时长分布',
      providerCoverage: ['腾讯', '百度'],
      demoValue: '74 分钟',
      businessQuestion: '餐饮层停留是否足够支撑正餐消费',
    },
  ],
  simulatedMetrics: {
    dailyFootfall: 21800,
    estimatedStoreVisits: 828,
    captureRate: 0.038,
    weekdayWeekendLift: 1.36,
    consumptionPowerIndex: 116,
    competitorOverlapIndex: 0.67,
    avgDwellMinutes: 74,
  },
  decisionScores: [
    {
      key: 'capture_gap',
      label: '捕获率缺口',
      name: '捕获率缺口',
      score: 82,
      maxScore: 100,
      level: 'high',
      evidence: '日均路过客流 21,800，当前估算捕获率 3.8%，仍有午市转化缺口。',
      recommendation: '先优化午市入口套餐、等位透明和门口导流。',
    },
    {
      key: 'competitor_pressure',
      label: '竞品压力',
      name: '竞品压力',
      score: 78,
      maxScore: 100,
      level: 'high',
      evidence: '竞品客群重叠指数 0.67，说明同商圈在抢同一批人。',
      recommendation: '避免同质化降价，强调服务确定性和招牌菜稳定性。',
    },
    {
      key: 'dwell_fit',
      label: '停留适配',
      name: '停留适配',
      score: 64,
      maxScore: 100,
      level: 'medium',
      evidence: '餐饮层平均停留 74 分钟，但午市办公客窗口更短。',
      recommendation: '午市做快决策套餐，晚市保留家庭/游客套餐。',
    },
    {
      key: 'supply_readiness',
      label: '供应链联动',
      name: '供应链联动',
      score: 59,
      maxScore: 100,
      level: 'medium',
      evidence: '20:00 后潜在客流仍高，历史销量补货会低估外部机会。',
      recommendation: '把客流峰谷作为备货阈值的外部修正项。',
    },
  ],
  scenarioSimulations: [
    {
      name: '捕获率 +0.4pp',
      assumption: '午市入口套餐、等位透明和点评信任线索同步优化。',
      metricDelta: '约 +87 到店机会/日，+609 到店机会/周。',
      operatingImplication: '先压缩点单和出餐链路，再看是否需要增加午市人手。',
      nextAction: '选择第一百货店做 2 周 A/B 验证。',
    },
    {
      name: '周末排队流失 -10%',
      assumption: '17:00 前开放候补预约，超过 35 分钟等待导流到同品牌近店。',
      metricDelta: '约保留 29 个高峰到店机会/日。',
      operatingImplication: '减少门口流失，同时把拥堵门店需求留在连锁内部。',
      nextAction: '用排队时长、点评等待关键词和跨店券核验。',
    },
    {
      name: '竞品重叠下降 0.07',
      assumption: '投放从通用折扣改成招牌菜稳定性、服务确定性和不踩雷心智。',
      metricDelta: '降低同质化价格竞争，优先改善转化质量。',
      operatingImplication: '预算从全城曝光转向来源地 Top3 和地图/点评决策入口。',
      nextAction: '按黄浦、静安、浦东三个来源地分组看券核销。',
    },
  ],
  validationPlan: [
    {
      question: '商场总客流是否同步下降？',
      requiredFields: ['mall_daily_footfall', 'floor_flow_index', 'storefront_passby_flow'],
      decisionRule: '如果商场不降而本店下降，优先查楼层动线、竞品新开和等位体验。',
      owner: '区域运营',
    },
    {
      question: '午市客流是办公客还是游客？',
      requiredFields: ['hourly_flow_index', 'source_area_top3', 'weekday_weekend_lift'],
      decisionRule: '办公客占比高则推快出餐套餐；游客占比高则强化点评信任和招牌菜。',
      owner: '门店运营',
    },
    {
      question: '客流峰谷是否已经影响备货？',
      requiredFields: ['hourly_flow_index', 'sku_stockout_rate', 'late_day_waste_rate'],
      decisionRule: '如果晚市潜在客流高但缺货/尾段浪费并存，调整备货阈值而非只改菜单。',
      owner: '供应链',
    },
  ],
  analysis: {
    headline: '百货商圈客流充足，但第一百货要抓午市转化，大丸百货要先解释外部客流断点。',
    comparisonNarrative: [
      'POS 解释已成交结果，客流画像解释未成交机会和商圈天花板。',
      '如果商场总客流不降而本店下降，优先排查楼层动线、同层新竞品和等位体验。',
      '连锁店应把满载门店、衰退门店和近场门店一起看，做商圈内分流而不是单店孤立调价。',
    ],
    risks: [
      '同商圈川菜/火锅供给密集，竞品重叠指数高时降价容易损伤毛利。',
      '周末客流提升会放大等位和上菜慢问题，继续拖累平台评分。',
      '游客和一次性客流占比高时，点评信任和门口转化比会员复购更关键。',
    ],
    opportunities: [
      '第一百货店用 68-88 元工作日午市套餐承接办公客。',
      '大丸百货店把外部客流、楼层动线、竞品新开和评分趋势做联合复盘。',
      '把商圈峰谷客流接入备货阈值，降低缺货与尾段浪费。',
    ],
    recommendations: [
      {
        priority: 'P0',
        action: '大丸百货店先做外部断点核验',
        why: '若商场总客流未降，本店 -37.4% 更可能来自楼层动线、竞品或体验问题。',
      },
      {
        priority: 'P1',
        action: '第一百货店推出工作日快出餐午市套餐',
        why: '办公客占比模拟 46%，午市捕获率提升比晚市继续加座更稳。',
      },
      {
        priority: 'P1',
        action: '周末高峰做近店分流和预约限流',
        why: '周末提升模拟 1.36x，排队体验会直接影响点评评分。',
      },
      {
        priority: 'P2',
        action: '按来源地 Top 区域做点评/团购投放',
        why: '比全城投放更容易估算 ROI，也能验证商圈客群假设。',
      },
      {
        priority: 'P2',
        action: '把客流峰谷接入备货阈值',
        why: '高峰降低缺货，低峰减少尾段浪费。',
      },
    ],
  },
};

export function resolveAdvancedTrafficPersona(
  report: V2UnifiedReport | null | undefined
): AdvancedTrafficPersona {
  return report?.sections?.advancedTrafficPersona ?? DEFAULT_ADVANCED_TRAFFIC_PERSONA;
}
