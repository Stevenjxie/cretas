/**
 * 邓总餐饮 Wave1 polish — Fix 3 + Fix 4 (G2 KPI 看板误导数据).
 *
 * Fix 3: 进行中周期 (week/month) 的达成率只覆盖已过天数, 与整周期目标比会低估
 *   → point 带 inProgress=true + daysElapsed/daysTotal, FE 显 "进行中 (已过 N/总 M 天)"
 *     徽章 + 中性色进度条, 不当成低达成告警。
 *
 * Fix 4: loadAchievementData 读失败 (success:false / 抛错) 旧实现只 console.error,
 *   data 保持 null → 模板渲染 "尚未设置营业目标"/"预警阈值未配置" 把系统错误伪装成
 *   用户未配置 (防呆 Rule 5)。现区分: 失败 → 显式错误态 (el-alert + 重试), 非空态。
 *
 * Mount-with-mocks pattern mirrors RestaurantSalesContent.default.spec.ts.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

// ── auth / permission stores: RESTAURANT tenant, price-view ──
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'RES_3101_009', factoryType: 'RESTAURANT' }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canViewPrice: true }),
}));

// ── router: capture push, no real navigation ─────────────────
const pushMock = vi.fn();
vi.mock('vue-router', () => ({ useRouter: () => ({ push: pushMock }) }));

// ── generic request gateway (factory KPI path; unused for restaurant) ─
vi.mock('@/api/request', () => ({ get: vi.fn(async () => ({ success: true, data: {} })) }));

// ── pythonFetch (restaurant-ops/summary) → minimal empty success ─
vi.mock('@/api/smartbi/common', () => ({
  pythonFetch: vi.fn(async () => ({ success: true, data: { totals: {}, top5_ingredients: [] } })),
}));

// ── gold board APIs → harmless success (not under test) ──────
vi.mock('@/api/smartbi/gold', () => ({
  getKpiSummary: vi.fn(async () => ({})),
  getFinanceSummary: vi.fn(async () => ({})),
  getTrendBundle: vi.fn(async () => ({})),
}));
vi.mock('../restaurantKpiBoard', () => ({
  buildRestaurantKpiBoard: vi.fn(() => ({ hasData: false, items: [], topStoreName: '' })),
}));

// ── ElMessage spy (Fix 4 toast) ──────────────────────────────
const elMessageMock = vi.fn();
vi.mock('element-plus', () => ({ ElMessage: (...a: unknown[]) => elMessageMock(...a) }));

// ── restaurant-targets API: the unit under test ──────────────
const fetchAchievementMock = vi.fn();
const fetchAlertsMock = vi.fn();
vi.mock('@/api/smartbi/restaurant-targets', () => ({
  fetchAchievement: (...a: unknown[]) => fetchAchievementMock(...a),
  fetchAlerts: (...a: unknown[]) => fetchAlertsMock(...a),
}));

import KpiIndex from '../index.vue';

const globalStubs = {
  'el-card': { template: '<div class="el-card"><slot name="header" /><slot /></div>' },
  'el-button': { emits: ['click'], template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-radio-group': { props: ['modelValue'], template: '<div class="el-radio-group"><slot /></div>' },
  'el-radio-button': { template: '<span><slot /></span>' },
  'el-progress': {
    props: ['percentage', 'color'],
    template: '<div class="el-progress" :data-color="color" :data-pct="percentage" />',
  },
  'el-empty': { props: ['description'], template: '<div class="el-empty">{{ description }}<slot /></div>' },
  'el-alert': { props: ['title', 'type'], template: '<div class="el-alert" :data-type="type">{{ title }}<slot /></div>' },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'el-timeline': { template: '<div class="el-timeline"><slot /></div>' },
  'el-timeline-item': { template: '<div class="el-timeline-item"><slot /></div>' },
  'el-breadcrumb': { template: '<div><slot /></div>' },
  'el-breadcrumb-item': { template: '<span><slot /></span>' },
  'el-divider': { template: '<hr />' },
};

async function mountView() {
  const wrapper = mount(KpiIndex, { global: { stubs: globalStubs } });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

describe('KPI 看板 — Fix 3 进行中周期不误导', () => {
  beforeEach(() => {
    fetchAchievementMock.mockReset();
    fetchAlertsMock.mockReset();
    elMessageMock.mockReset();
    pushMock.mockReset();
    fetchAlertsMock.mockResolvedValue({ success: true, data: { configExists: false, timeline: [], summary: {} } });
  });

  it('flags an in-progress month with 进行中 badge + neutral progress color (not alert red)', async () => {
    fetchAchievementMock.mockResolvedValue({
      success: true,
      data: {
        factoryId: 'RES_3101_009',
        kpiKind: 'revenue',
        level: 'month',
        periodWithoutTarget: [],
        points: [
          {
            periodKey: '2026-06',
            target: 1000000,
            actual: 100000,        // only 10% — but month is only 3 days in
            achievementRate: 0.1,
            dataMissing: false,
            inProgress: true,
            periodComplete: false,
            daysElapsed: 3,
            daysTotal: 30,
          },
        ],
      },
    });
    const wrapper = await mountView();
    const html = wrapper.html();
    // 进行中 badge with elapsed/total days
    expect(html).toContain('进行中');
    expect(html).toContain('已过 3/30 天');
    // progress bar uses neutral grey, NOT the low-achievement red
    const bar = wrapper.find('.el-progress');
    expect(bar.exists()).toBe(true);
    expect(bar.attributes('data-color')).toBe('#909399');
    // the 10% partial rate must NOT trigger the empty "尚未设置营业目标" state
    expect(html).not.toContain('尚未设置营业目标');
  });

  it('a complete past period keeps the normal achievement color (no 进行中 badge)', async () => {
    fetchAchievementMock.mockResolvedValue({
      success: true,
      data: {
        factoryId: 'RES_3101_009',
        kpiKind: 'revenue',
        level: 'month',
        periodWithoutTarget: [],
        points: [
          {
            periodKey: '2026-05',
            target: 1000000,
            actual: 980000,
            achievementRate: 0.98,
            dataMissing: false,
            inProgress: false,
            periodComplete: true,
            daysElapsed: 31,
            daysTotal: 31,
          },
        ],
      },
    });
    const wrapper = await mountView();
    const html = wrapper.html();
    expect(html).not.toContain('进行中');
    const bar = wrapper.find('.el-progress');
    expect(bar.attributes('data-color')).toBe('#67c23a'); // healthy green (>=0.8)
  });
});

describe('KPI 看板 — Fix 4 读失败不伪装未配置', () => {
  beforeEach(() => {
    fetchAchievementMock.mockReset();
    fetchAlertsMock.mockReset();
    elMessageMock.mockReset();
    pushMock.mockReset();
  });

  it('renders an explicit error state + retry (NOT 尚未设置营业目标) when the read throws', async () => {
    fetchAchievementMock.mockRejectedValue(new Error('500 内部错误'));
    fetchAlertsMock.mockRejectedValue(new Error('500 内部错误'));
    const wrapper = await mountView();
    const html = wrapper.html();
    // error alert carries the backend message
    expect(html).toContain('达成率数据加载失败');
    expect(html).toContain('500 内部错误');
    // must NOT disguise as user-not-configured
    expect(html).not.toContain('尚未设置营业目标');
    expect(html).not.toContain('预警阈值未配置');
    // sticky error toast emitted
    expect(elMessageMock).toHaveBeenCalled();
    const arg = elMessageMock.mock.calls[0][0] as { type: string; duration: number; showClose: boolean };
    expect(arg.type).toBe('error');
    expect(arg.duration).toBe(0);
    expect(arg.showClose).toBe(true);
  });

  it('treats success:false as a read failure (error state), not an empty config state', async () => {
    fetchAchievementMock.mockResolvedValue({ success: false, message: '权限不足', data: null });
    fetchAlertsMock.mockResolvedValue({ success: true, data: { configExists: false, timeline: [], summary: {} } });
    const wrapper = await mountView();
    const html = wrapper.html();
    expect(html).toContain('达成率数据加载失败');
    expect(html).toContain('权限不足');
    expect(html).not.toContain('尚未设置营业目标');
  });

  it('still shows the 未配置 empty state when the read SUCCEEDS but no target/config exists', async () => {
    fetchAchievementMock.mockResolvedValue({
      success: true,
      data: { factoryId: 'RES_3101_009', kpiKind: 'revenue', level: 'day', points: [], periodWithoutTarget: [] },
    });
    fetchAlertsMock.mockResolvedValue({ success: true, data: { configExists: false, timeline: [], summary: {} } });
    const wrapper = await mountView();
    const html = wrapper.html();
    // genuine empty state preserved (no error)
    expect(html).toContain('尚未设置营业目标');
    expect(html).toContain('预警阈值未配置');
    expect(html).not.toContain('达成率数据加载失败');
    expect(elMessageMock).not.toHaveBeenCalled();
  });
});
