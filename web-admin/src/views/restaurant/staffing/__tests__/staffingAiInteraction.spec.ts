import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import StaffingPage from '../index.vue';

const mocks = vi.hoisted(() => ({
  askRestaurantQuestion: vi.fn(),
  getRestaurantStaffingDashboard: vi.fn(),
}));

vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ currentRole: 'restaurant_owner' }),
}));

vi.mock('@/api/smartbi/common', () => ({
  getFactoryId: () => 'RES_3101_009',
  getUserData: () => ({ userId: 'owner-1' }),
}));

vi.mock('@/api/smartbi/restaurant-chat', () => ({
  askRestaurantQuestion: (...args: unknown[]) => mocks.askRestaurantQuestion(...args),
}));

vi.mock('@/api/smartbi/restaurant-staffing', () => ({
  applyRestaurantStaffingAdjustment: vi.fn(),
  getRestaurantStaffingDashboard: (...args: unknown[]) => (
    mocks.getRestaurantStaffingDashboard(...args)
  ),
}));

const dashboard = {
  factoryId: 'RES_3101_009',
  horizon: 'tomorrow',
  horizonLabel: '明天',
  windowStart: '2026-08-04',
  windowEnd: '2026-08-04',
  generatedAt: '2026-08-03T12:00:00Z',
  asOf: '2026-08-03',
  numericSource: 'forecast_factbook_only',
  historicalProductivityRule: 'evidence_only_not_gap_input',
  sources: [],
  summary: {
    predictedGuests: 100,
    reservedGuests: 40,
    reservationCoveragePct: 40,
    recommendedStaff: 12,
    currentStaff: 10,
    positiveGap: 2,
    partTimePeople: 2,
    confidencePct: 70,
    storeCount: 1,
  },
  summaryRows: [],
  dailyRows: [],
};

function mountPage() {
  return shallowMount(StaffingPage, {
    global: {
      directives: { loading: {} },
      stubs: {
        'el-card': {
          template: '<section><header><slot name="header" /></header><slot /></section>',
        },
        'el-button': {
          props: ['disabled', 'loading'],
          emits: ['click'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
        },
        'el-alert': {
          props: ['title', 'description'],
          template: '<div class="el-alert"><strong>{{ title }}</strong><p>{{ description }}</p><slot /></div>',
        },
        'el-tag': { template: '<span class="el-tag"><slot /></span>' },
        'el-input': { template: '<div class="el-input"><slot name="append" /></div>' },
        'el-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
        'el-table': { template: '<div class="el-table" />' },
        'el-table-column': { template: '<div />' },
        'el-select': { template: '<div />' },
        'el-option': { template: '<div />' },
        'el-pagination': { template: '<div />' },
        'el-drawer': { template: '<div />' },
        'el-dialog': { template: '<div />' },
        'el-form': { template: '<div />' },
        'el-form-item': { template: '<div />' },
        'el-input-number': { template: '<div />' },
      },
    },
  });
}

describe('预测排班 AI 连续问答', () => {
  beforeEach(() => {
    mocks.askRestaurantQuestion.mockReset();
    mocks.getRestaurantStaffingDashboard.mockReset();
    mocks.getRestaurantStaffingDashboard.mockResolvedValue(dashboard);
  });

  it('首轮保存 Java session，后端 follow-up 追问复用同一 session', async () => {
    mocks.askRestaurantQuestion
      .mockResolvedValueOnce({
        success: true,
        intentCode: 'RESTAURANT_OPS_STAFFING_ADVICE',
        message: '**结论**：先覆盖晚市技能缺口。',
        javaSessionId: 'java-staffing-1',
        sessionId: 'java-staffing-1',
        followUpChips: ['那晚市呢'],
      })
      .mockResolvedValueOnce({
        success: true,
        intentCode: 'RESTAURANT_OPS_STAFFING_ADVICE',
        message: '晚市继续按同一 FactBook 解释。',
        javaSessionId: 'java-staffing-1',
        sessionId: 'java-staffing-1',
        followUpChips: [],
      });

    const wrapper = mountPage();
    await flushPromises();
    await wrapper.findAll('.question-chips button')[0].trigger('click');
    await flushPromises();

    expect(mocks.askRestaurantQuestion).toHaveBeenNthCalledWith(1, expect.objectContaining({
      query: '明天怎么排班',
      sessionId: undefined,
    }));
    expect(wrapper.text()).toContain('排班 FactBook 已绑定');
    expect(wrapper.html()).toContain('<strong>结论</strong>');

    await wrapper.find('.ai-followups button').trigger('click');
    await flushPromises();
    expect(mocks.askRestaurantQuestion).toHaveBeenNthCalledWith(2, expect.objectContaining({
      query: '那晚市呢',
      sessionId: 'java-staffing-1',
    }));
  });

  it('追问失败时保留上一条有效回答和原会话，并提供重试', async () => {
    mocks.askRestaurantQuestion
      .mockResolvedValueOnce({
        success: true,
        intentCode: 'RESTAURANT_OPS_STAFFING_ADVICE',
        message: '上一条有效排班回答',
        javaSessionId: 'java-staffing-2',
        sessionId: 'java-staffing-2',
        followUpChips: ['那晚市呢'],
      })
      .mockRejectedValueOnce(new Error('解释链暂不可用'));

    const wrapper = mountPage();
    await flushPromises();
    await wrapper.findAll('.question-chips button')[0].trigger('click');
    await flushPromises();
    await wrapper.find('.ai-followups button').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('上一条有效排班回答');
    expect(wrapper.text()).toContain('解释链暂不可用');
    expect(wrapper.text()).toContain('上一条有效回答仍保留');
    expect(wrapper.text()).toContain('重试这次问题');
  });

  it('其他餐饮意图不会被标记为当前排班 FactBook 回答', async () => {
    mocks.askRestaurantQuestion.mockResolvedValue({
      success: true,
      intentCode: 'RESTAURANT_DAILY_REVENUE',
      message: '这是营收问题。',
      javaSessionId: 'java-other-1',
      sessionId: 'java-other-1',
      followUpChips: [],
    });

    const wrapper = mountPage();
    await flushPromises();
    await wrapper.findAll('.question-chips button')[0].trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('本次回答未命中预测排班能力');
    expect(wrapper.text()).not.toContain('排班 FactBook 已绑定');
  });

  it('首轮失败不会声称保留了不存在的旧回答', async () => {
    mocks.askRestaurantQuestion.mockRejectedValue(new Error('解释链暂不可用'));

    const wrapper = mountPage();
    await flushPromises();
    await wrapper.findAll('.question-chips button')[0].trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('本次没有产生新的排班结论');
    expect(wrapper.text()).not.toContain('上一条有效回答仍保留');
  });
});
