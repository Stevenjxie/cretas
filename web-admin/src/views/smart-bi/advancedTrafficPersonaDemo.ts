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
    {
      provider: '和风天气',
      accessMode: '注册 Key 后接入',
      bestUse: '按小时天气、空气质量、灾害预警解释堂食、外卖和临时客流变化',
    },
    {
      provider: '大麦开放平台',
      accessMode: '注册 AppKey/AppSecret 后接入',
      bestUse: '识别周边演唱会、体育、展览、亲子活动带来的临时客流',
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
    weekdayWeekendLift: 1.36,
    consumptionPowerIndex: 116,
    competitorOverlapIndex: 0.67,
    avgDwellMinutes: 74,
  },
  plainLanguageAnalysis: {
    bottomLine:
      '这家店现在不是没人路过，而是门口的人没有被充分转成进店；大丸百货这类下滑店，要先查外部变化和现场体验，别一上来就打折。',
    whatItMeans: [
      '先别急着打折。打折只能拉一部分已经想吃的人，但解决不了“路过的人为什么没进来”。',
      '如果商场整体人流没掉，只有本店掉，重点查楼层动线、同层竞品、门口展示和服务体验。',
      '如果商场整体人流也掉了，就要和商场活动、楼层导流、团购承接一起做，不能只压门店。',
      '第一百货这类高人流店，重点不是再拉更多人，而是把午市、周末排队和备货做顺。',
    ],
    whyWeThinkSo: [
      '内部 POS 只能看到已经买单的人，解释不了“经过但没进店”的人。',
      '点评能看到顾客进店后的抱怨，解释不了商场人流、楼层动线和竞品变化。',
      '客流画像、POI 竞品和商场楼层数据放在一起，才能判断外部机会少了，还是门店没接住机会。',
    ],
  },
  solutionPlan: [
    {
      step: '第一周：先查大丸百货店为什么掉',
      action: '把商场总人流、餐饮楼层人流、同层竞品变化、门口等位体验放在一张表里复盘。',
      owner: '区域运营',
      expectedOutcome: '判断下滑到底是商场没客、楼层动线变了、竞品抢走了，还是门店服务拖累。',
    },
    {
      step: '第二周：第一百货店做午市快转化',
      action: '推出工作日午市套餐，门口和团购页只讲三件事：快、稳、不踩雷。',
      owner: '门店店长',
      expectedOutcome: '把办公客从“路过看看”变成“今天就进来吃”。',
    },
    {
      step: '第三周：周末高峰做分流和预约',
      action: '排队超过 35 分钟就给同品牌近店分流券，同时把预约候补提前到 17:00 前开放。',
      owner: '区域运营',
      expectedOutcome: '减少门口流失，把本来要走掉的人留在品牌内部。',
    },
    {
      step: '第四周：把客流变化接到备货',
      action: '晚市和周末备货不要只看历史销量，要叠加商场活动、天气、楼层客流和预约情况。',
      owner: '供应链',
      expectedOutcome: '减少高峰缺货和尾段浪费同时发生。',
    },
  ],
  dataSufficiency: {
    isEnoughForRealDecision: false,
    plainVerdict: '足够做 demo 和开通说明，不足够直接下真实经营结论。',
    why: [
      '现在用的是模拟客流画像，不能证明某家门店真实的人流变化。',
      '缺少商场总客流、餐饮楼层客流、门口路过客流和竞品开闭店时间线。',
      '缺少行业白皮书或平台报告来校准：当前价格带、连锁化趋势、品类竞争强度是不是行业共性。',
    ],
    whatCanBeDecidedNow: [
      '可以决定这个高级模块值得保留，因为它解释的是 POS 和点评解释不了的问题。',
      '可以决定试点验证路径：先选第一百货和大丸百货，一个看机会承接，一个看异常下滑。',
      '可以决定需要客户授权哪些数据，而不是盲目购买全量外部数据。',
    ],
    whatCannotBeDecidedYet: [
      '不能直接认定大丸百货店下滑就是商场客流下滑导致。',
      '不能直接给出真实投放预算和 ROI。',
      '不能替代门店现场走访、点评原文和商场物业数据。',
    ],
  },
  neededEvidence: [
    {
      name: '腾讯或百度商圈/商场客流画像',
      whyNeeded: '验证商场有没有人、客从哪里来、在餐饮楼层停多久，以及是否和竞品抢同一批人。',
      sourceType: '付费/商务数据',
      priority: 'P0',
      publicAvailability: '通常需要商务开通，公开数据不能替代。',
      sourceUrl: 'https://cloud.tencent.com/product/mall',
    },
    {
      name: '高德 POI、周边搜索和竞品供给数据',
      whyNeeded: '判断门店周边到底有多少同赛道竞品、是否近期供给变密。',
      sourceType: '开放 API + 业务清洗',
      priority: 'P0',
      publicAvailability: '开放 API 可用，但只能做供给侧 proxy，不能当真实客流。',
      sourceUrl: 'https://lbs.amap.com/api/webservice/guide/api-advanced/search',
    },
    {
      name: '2025中国餐饮连锁化发展白皮书',
      whyNeeded: '校准当前餐饮大盘、价格带、连锁化趋势和品类竞争是不是行业共性。',
      sourceType: '行业白皮书',
      priority: 'P1',
      publicAvailability: '公开摘要可用，完整报告按发布方口径获取。',
      sourceUrl: 'https://www.ccfa.org.cn/portal/cn/xiangxi.jsp?id=446601&sharetype=1&type=33',
    },
    {
      name: '商场物业数据',
      whyNeeded: '确认楼层调整、出入口动线、活动排期和停车/地铁导流是否影响本店。',
      sourceType: '客户/物业授权数据',
      priority: 'P0',
      publicAvailability: '非公开，需要商户或物业合作。',
    },
    {
      name: '门店现场走访和点评原文',
      whyNeeded: '确认问题是不是等位、门口展示、服务态度、上菜慢等现场因素。',
      sourceType: '客户自有数据 + 人工复盘',
      priority: 'P0',
      publicAvailability: '客户授权后可获得。',
    },
    {
      name: '和风天气、大麦活动、节假日与商场活动源',
      whyNeeded: '解释某天突然暴涨或暴跌是不是外部天气、演出、展会、节假日或商场活动导致。',
      sourceType: '开放 API + 半自动采集',
      priority: 'P0',
      publicAvailability: '和风和大麦需要注册 Key；节假日可直接结构化；商场活动通常需要配置官网/公众号/小程序活动源。',
      sourceUrl: 'https://dev.qweather.com/docs/api/',
    },
  ],
  adviceKnowledgeBase: [
    {
      situation: '门店销售下滑，但商圈或商场客流没有同步下滑',
      plainDiagnosis: '这通常不是“没人逛”，而是本店没有接住路过的人。',
      bossAction: '先暂停大额折扣，安排 1 周现场复盘门口展示、等位体验、同层竞品和楼层动线。',
      decisionRule: '如果商场总客流稳定、本店订单下降超过 10%，优先查门店转化问题，而不是把责任归到大盘。',
      neededData: ['商场总客流', '餐饮楼层客流', '门口等位时长', '同层竞品变化', '大众点评原文'],
      sourceBasis: [
        '腾讯商场客留大数据：可做客流趋势、来源地、竞品重叠和画像判断',
        '百度慧眼：可做商圈、商场、楼层客流和客群画像评估',
      ],
    },
    {
      situation: '商圈整体客流下降，本店也下降',
      plainDiagnosis: '这时单店经理很难靠门店动作独自扛住，需要和商场、团购、外卖、会员一起做承接。',
      bossAction: '把动作从“店内整改”升级为“商圈联合运营”：商场活动导流、团购曝光、老客召回和外卖补峰同步做。',
      decisionRule: '如果商场或楼层客流连续两周下降，本店动作要从单店转化转为外部导流和线上承接。',
      neededData: ['商场活动排期', '楼层客流趋势', '团购曝光与转化', '外卖订单时段', '会员来源地'],
      sourceBasis: [
        '百度慧眼商业地产方案：支持客群来源、流向、商圈人群和竞品监测',
        '高德 POI 搜索：可补充周边供给和竞品密度，不替代真实客流',
      ],
    },
    {
      situation: '午市人多但收入没有明显起来',
      plainDiagnosis: '办公客不是不消费，而是怕慢、怕排队、怕踩雷。',
      bossAction: '做 45 分钟能吃完的工作日午市套餐，门口、团购页和点评页只强调快、稳、招牌菜。',
      decisionRule: '如果午市客流高、停留时间短、客单价不高，优先改套餐和出餐链路，不优先加座或做晚市大促。',
      neededData: ['分小时客流', '出餐时长', '午市客单价', '办公楼来源地', '套餐点击和核销'],
      sourceBasis: [
        '餐饮连锁化白皮书/平台报告：用于校准价格带、线上化和连锁门店效率趋势',
        '内部 POS 和点评原文：用于验证顾客是否在抱怨慢、排队和不稳定',
      ],
    },
    {
      situation: '周末排队很长，但评价和复购变差',
      plainDiagnosis: '这不是越排队越好，排队过长会把本来愿意吃的人变成差评和流失。',
      bossAction: '设置 35 分钟等位红线，超过就做预约候补、同品牌近店分流和排队补偿。',
      decisionRule: '如果周末客流高、等位长、点评里出现慢和服务抱怨，优先做分流和预约，不要继续硬接客。',
      neededData: ['等位时长', '周末客流', '点评差评主题', '近店座位余量', '预约候补转化'],
      sourceBasis: [
        '腾讯客流画像：可判断周末游客、家庭客和竞品重叠',
        '内部连锁门店数据：可判断能不能把人留在品牌内部分流',
      ],
    },
    {
      situation: '周边竞品突然变多，价格战压力变大',
      plainDiagnosis: '同赛道变挤时，直接降价往往会先伤毛利，不一定能解决进店理由。',
      bossAction: '先查竞品是抢同一批人还是不同场景，再决定做招牌菜稳定性、服务确定性，还是做价格带调整。',
      decisionRule: '如果 POI 竞品变密但真实客流没有增加，优先做差异化定位和产品组合，不先做全店打折。',
      neededData: ['高德/腾讯/百度 POI', '竞品开闭店时间线', '点评菜品关键词', '本店毛利结构', '同商圈客单价'],
      sourceBasis: [
        '高德 POI 搜索：用于判断供给侧竞品密度和变化',
        '行业白皮书/平台报告：用于校准品类竞争、价格带和连锁化趋势',
      ],
    },
    {
      situation: '某一天销售或客流突然暴涨',
      plainDiagnosis: '这不一定是门店运营突然变好，可能是节假日、演唱会散场、商场活动、极端天气前后造成的外部流量。',
      bossAction: '先给当天打外部原因标签，再复盘会员沉淀、点评引导、备货和排班，不要直接把当天结果外推到下周。',
      decisionRule: '如果当天命中节假日、周边活动、商场活动或天气异常，增长先按外部机会处理，再评估门店是否接住机会。',
      neededData: ['节假日/调休', '和风天气', '大麦周边活动', '商场活动页', '当天排队和备货记录'],
      sourceBasis: [
        '和风天气：小时级天气、预警和极端天气解释',
        '大麦开放平台：周边演出、体育、亲子、展览活动解释',
        '国务院节假日通知：节假日和调休解释',
        '商场活动采集：IP 展、市集、快闪、会员日解释',
      ],
    },
  ],
  externalSignals: {
    moduleName: '外部原因解释器',
    purpose: '解释某天或某周销售/客流异常是不是天气、节假日、周边活动或商场活动导致。',
    sourceStatuses: [
      {
        source: '和风天气',
        keyRequired: true,
        envVars: ['QWEATHER_API_KEY'],
        status: '待配置',
        refreshCadence: '小时级',
        bestUse: '解释雨天、高温、寒潮、台风、空气质量和预警对堂食/外卖的影响。',
        officialUrl: 'https://dev.qweather.com/docs/api/',
      },
      {
        source: '大麦开放平台',
        keyRequired: true,
        envVars: ['DAMAI_APP_KEY', 'DAMAI_APP_SECRET'],
        status: '待配置',
        refreshCadence: '日更/小时级',
        bestUse: '识别附近演唱会、体育比赛、亲子演出、展览带来的临时客流。',
        officialUrl: 'https://developer.alibaba.com/docs/api.htm?apiId=71773',
      },
      {
        source: '中国节假日/调休',
        keyRequired: false,
        envVars: [],
        status: '可直接使用',
        refreshCadence: '年度更新',
        bestUse: '解释节假日、补班日和长假前后造成的异常客流。',
      },
      {
        source: '商场活动采集',
        keyRequired: false,
        envVars: ['MALL_ACTIVITY_FEED_URLS'],
        status: '可先半自动接入',
        refreshCadence: '日更',
        bestUse: '采集商场官网、公众号、小程序活动页，解释 IP 展、市集、会员日、品牌快闪带来的客流波动。',
      },
    ],
    signals: [
      {
        type: 'holiday',
        source: '中国节假日/调休',
        severity: 'low',
        title: '普通日期',
        plainImpact: '没有明显官方节假日因素，需要继续看天气、活动、交通和商场活动。',
        actionHint: '按普通工作日/周末逻辑分析，但保留其他外部信号校验。',
      },
      {
        type: 'weather',
        source: '和风天气',
        severity: 'medium',
        title: '天气影响待接入',
        plainImpact: '雨天、高温、寒潮或预警会影响堂食、外卖和排队意愿。',
        actionHint: '配置 QWEATHER_API_KEY 后，按小时天气和预警自动解释当天客流异常。',
      },
      {
        type: 'nearby_event',
        source: '大麦开放平台',
        severity: 'medium',
        title: '周边演出活动待接入',
        plainImpact: '周边演唱会、体育、亲子、展览活动可能造成晚市或周末突增客流。',
        actionHint: '配置大麦 AppKey/Secret 后，每天拉取 1-3km 重点场馆活动，给异常日打活动标签。',
      },
      {
        type: 'mall_activity',
        source: '商场活动采集',
        severity: 'medium',
        title: '商场活动源待配置',
        plainImpact: '商场 IP 展、市集、品牌快闪、会员日会显著影响客流，且通常没有统一开放 API。',
        actionHint: '先配置商场官网/公众号/小程序活动页，做日更采集；有物业合作后再接内部活动表。',
      },
    ],
    collectionPipeline: {
      defaultMode: 'manual_or_cron',
      whyNotOnPageLoad: '外部接口有每日额度，demo 页面打开不自动消耗配额；由后台定时任务或管理员手动触发采集。',
      dailyBudgetEnv: {
        weather: 'QWEATHER_DAILY_QUERY_BUDGET',
        mapPoi: 'AMAP_DAILY_QUERY_BUDGET / TENCENT_MAP_DAILY_QUERY_BUDGET / BAIDU_MAP_DAILY_QUERY_BUDGET',
      },
      steps: [
        {
          source: '和风天气',
          productionStatus: 'needs_key_and_location',
          refreshCadence: '小时级/日更均可',
          storesOneApiCall: true,
          whatItWrites: ['天气现况', '体感温度', '湿度', '风力', '供应商更新时间'],
        },
        {
          source: '中国节假日/调休',
          productionStatus: 'ready_without_key',
          refreshCadence: '年度更新',
          storesOneApiCall: false,
          whatItWrites: ['节假日标签', '补班标签'],
        },
        {
          source: '大麦开放平台',
          productionStatus: 'needs_key_and_api_scope',
          refreshCadence: '日更',
          storesOneApiCall: false,
          whatItWrites: ['活动类型', '场馆', '活动日期', '售票状态'],
        },
        {
          source: '商场活动采集',
          productionStatus: 'needs_feed_urls',
          refreshCadence: '日更',
          storesOneApiCall: false,
          whatItWrites: ['活动标题', '活动日期', '楼层/场地', '品牌或活动类型'],
        },
      ],
    },
    plainConclusion: '当天销售或客流异常不能只看门店内部，要先排查节假日、天气、周边活动和商场活动这些外部原因。',
    bossActions: [
      '不要把节假日、演出散场、商场活动带来的短期增长直接外推到下周。',
      '如果外部活动带来新增客，重点做会员沉淀、点评引导和高峰备货复盘。',
      '如果天气或调休导致下滑，先调整排班和外卖承接，不要马上判断门店经营变差。',
    ],
    dataNeededForProduction: [
      '门店经纬度和所属商圈',
      '商场官网/公众号/小程序活动页 URL',
      '周边 1-3km 重点场馆列表',
      '和风天气 QWEATHER_API_KEY',
      '大麦 DAMAI_APP_KEY 和 DAMAI_APP_SECRET',
    ],
  },
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
        why: '办公客时间紧，午市先解决“进来吃得快、吃得稳”，比晚市继续加座更稳。',
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
