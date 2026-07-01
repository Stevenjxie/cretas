import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import AdvancedTrafficPersonaCard from '../AdvancedTrafficPersonaCard.vue';

const SAMPLE = {
  moduleName: '高级客流画像分析',
  requiresEnablement: true,
  demoMode: true,
  dataNote: '当前为 demo 模拟数据，仅用于展示分析形态。',
  enablement: { status: '需额外开通' },
  providers: [
    { provider: '腾讯位置大数据', bestUse: '商圈客流' },
    { provider: '百度慧眼/百度地图', bestUse: '区域热力' },
    { provider: '高德/商业位置数据', bestUse: 'POI 供给密度' },
  ],
  simulatedMetrics: {
    dailyFootfall: 21800,
    estimatedStoreVisits: 828,
    weekdayWeekendLift: 1.36,
    consumptionPowerIndex: 116,
    competitorOverlapIndex: 0.67,
  },
  analysis: {
    headline: '人民广场客流强，但火锅竞品重叠高。',
    recommendations: [
      {
        priority: 'P0',
        action: '把午市做成 45 分钟可完成的工作日套餐。',
        why: '办公客流占比高。',
        metricTrigger: '办公客群 46%',
        expectedImpact: '2 周验证',
      },
    ],
  },
  plainLanguageAnalysis: {
    bottomLine: '这家店现在不是没人路过，而是路过的人没有被充分转成进店。',
    whatItMeans: [
      '先别急着打折，先查门口动线、等位体验和午市套餐是不是挡住了转化。',
    ],
    whyWeThinkSo: [
      '内部 POS 只能看到已经买单的人，外部客流能解释没进店的人。',
    ],
  },
  solutionPlan: [
    {
      step: '第一周',
      action: '派区域经理去大丸百货店现场复盘。',
      owner: '区域运营',
      expectedOutcome: '判断问题在商场客流、楼层动线还是门店体验。',
    },
  ],
  dataSufficiency: {
    isEnoughForRealDecision: false,
    plainVerdict: '足够做 demo 和开通说明，不足够直接下真实经营结论。',
    why: [
      '现在缺少真实商场客流、楼层动线和竞品变化。',
    ],
    whatCanBeDecidedNow: ['先确定要验证的问题和需要开通的数据。'],
    whatCannotBeDecidedYet: ['不能直接判断大丸百货店下滑一定是外部客流导致。'],
  },
  neededEvidence: [
    {
      name: '2025中国餐饮连锁化发展白皮书',
      whyNeeded: '判断连锁餐饮行业大盘和价格趋势。',
      sourceType: '行业白皮书',
      priority: 'P1',
      publicAvailability: '公开摘要可用',
    },
    {
      name: '腾讯/百度商圈客流画像',
      whyNeeded: '验证商场客流和客群来源。',
      sourceType: '商务数据',
      priority: 'P0',
      publicAvailability: '需商务开通',
    },
  ],
  adviceKnowledgeBase: [
    {
      situation: '门店销售下滑，但商圈或商场客流没有同步下滑',
      plainDiagnosis: '这通常不是没人逛，而是本店没有接住路过的人。',
      bossAction: '先暂停大额折扣，安排 1 周现场复盘门口展示、等位体验、同层竞品和楼层动线。',
      decisionRule: '如果商场总客流稳定、本店订单下降超过 10%，优先查门店转化问题。',
      neededData: ['商场总客流', '餐饮楼层客流', '大众点评原文'],
      sourceBasis: ['腾讯商场客留大数据', '百度慧眼'],
    },
  ],
  externalSignals: {
    moduleName: '外部原因解释器',
    purpose: '解释某天销售异常是不是天气、节假日、周边活动或商场活动导致。',
    sourceStatuses: [
      {
        source: '和风天气',
        keyRequired: true,
        envVars: ['QWEATHER_API_KEY'],
        status: '待配置',
        refreshCadence: '小时级',
        bestUse: '解释雨天、高温和灾害预警。',
      },
      {
        source: '大麦开放平台',
        keyRequired: true,
        envVars: ['DAMAI_APP_KEY', 'DAMAI_APP_SECRET'],
        status: '待配置',
        refreshCadence: '日更',
        bestUse: '识别附近演出活动。',
      },
      {
        source: '中国节假日/调休',
        keyRequired: false,
        envVars: [],
        status: '可直接使用',
        refreshCadence: '年度更新',
        bestUse: '解释节假日和补班日。',
      },
      {
        source: '商场活动采集',
        keyRequired: false,
        envVars: ['MALL_ACTIVITY_FEED_URLS'],
        status: '可先半自动接入',
        refreshCadence: '日更',
        bestUse: '采集商场活动页。',
      },
    ],
    signals: [
      {
        type: 'weather',
        source: '和风天气',
        severity: 'medium',
        title: '天气影响待接入',
        plainImpact: '雨天会影响堂食和排队意愿。',
        actionHint: '配置 QWEATHER_API_KEY 后自动解释。',
      },
      {
        type: 'mall_activity',
        source: '商场活动采集',
        severity: 'medium',
        title: '商场活动源待配置',
        plainImpact: '商场 IP 展会影响客流。',
        actionHint: '先配置商场活动页。',
      },
    ],
    plainConclusion: '当天销售或客流异常不能只看门店内部。',
    bossActions: ['不要把短期增长直接外推到下周。'],
    dataNeededForProduction: ['QWEATHER_API_KEY', 'QWEATHER_API_HOST', 'DAMAI_APP_KEY'],
    collectionPipeline: {
      defaultMode: 'manual_or_cron',
      whyNotOnPageLoad: '外部接口有每日额度，demo 页面打开不应自动消耗配额。',
      dailyBudgetEnv: {
        weather: 'QWEATHER_DAILY_QUERY_BUDGET',
        mapPoi: 'AMAP_DAILY_QUERY_BUDGET / TENCENT_MAP_DAILY_QUERY_BUDGET / BAIDU_MAP_DAILY_QUERY_BUDGET',
      },
      steps: [
        {
          source: '和风天气',
          productionStatus: 'needs_key_host_and_location',
          refreshCadence: '小时级/日更均可',
          storesOneApiCall: true,
          whatItWrites: ['天气现况', '体感温度'],
        },
      ],
    },
  },
  decisionScores: [
    {
      key: 'capture_gap',
      label: '捕获率缺口',
      score: 82,
      maxScore: 100,
      level: 'high',
      evidence: '第一百货捕获率仍低于同商圈机会位。',
      recommendation: '先做午市快转化。',
    },
  ],
  scenarioSimulations: [
    {
      name: '捕获率 +0.4pp',
      assumption: '把午市入口套餐和等位透明度做好。',
      metricDelta: '约 +87 到店机会/日',
      operatingImplication: '优先压缩出餐链路。',
      nextAction: '2 周 A/B 验证。',
    },
  ],
  validationPlan: [
    {
      question: '商场总客流是否同步下降？',
      requiredFields: ['mall_daily_footfall', 'floor_flow_index'],
      decisionRule: '若商场不降而本店下降，优先查动线和竞品。',
      owner: '区域运营',
    },
  ],
};

describe('AdvancedTrafficPersonaCard', () => {
  it('renders premium status, providers and actionable demo recommendations', () => {
    const wrapper = mount(AdvancedTrafficPersonaCard, {
      props: { data: SAMPLE },
    });

    expect(wrapper.get('[data-test="premium-status"]').text()).toContain('需额外开通');
    expect(wrapper.findAll('[data-test="provider"]')).toHaveLength(3);
    expect(wrapper.text()).toContain('腾讯位置大数据');
    expect(wrapper.text()).toContain('百度慧眼');
    expect(wrapper.text()).toContain('高德');
    expect(wrapper.text()).toContain('午市');
    expect(wrapper.text()).toContain('demo 模拟数据');
    expect(wrapper.text()).toContain('白话结论');
    expect(wrapper.text()).toContain('这家店现在不是没人路过');
    expect(wrapper.text()).toContain('直接方案');
    expect(wrapper.text()).toContain('第一周');
    expect(wrapper.text()).toContain('数据够不够');
    expect(wrapper.text()).toContain('不足够直接下真实经营结论');
    expect(wrapper.text()).toContain('还缺哪些资料');
    expect(wrapper.text()).toContain('餐饮连锁化发展白皮书');
    expect(wrapper.text()).toContain('建议依据库');
    expect(wrapper.text()).toContain('先暂停大额折扣');
    expect(wrapper.text()).toContain('外部原因解释器');
    expect(wrapper.text()).toContain('和风天气');
    expect(wrapper.text()).toContain('QWEATHER_API_KEY');
    expect(wrapper.text()).toContain('大麦开放平台');
    expect(wrapper.text()).toContain('商场活动源待配置');
    expect(wrapper.text()).toContain('采集链路');
    expect(wrapper.text()).toContain('manual_or_cron');
    expect(wrapper.text()).toContain('QWEATHER_DAILY_QUERY_BUDGET');
    expect(wrapper.text()).not.toContain('诊断评分');
    expect(wrapper.text()).not.toContain('捕获率 +0.4pp');
  });
});
