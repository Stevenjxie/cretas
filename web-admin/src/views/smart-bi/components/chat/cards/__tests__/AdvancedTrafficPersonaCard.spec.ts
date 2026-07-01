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
    expect(wrapper.text()).toContain('诊断评分');
    expect(wrapper.text()).toContain('场景推演');
    expect(wrapper.text()).toContain('验证计划');
    expect(wrapper.text()).toContain('捕获率 +0.4pp');
    expect(wrapper.text()).toContain('商场总客流是否同步下降');
  });
});
