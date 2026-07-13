import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { CapacityDiagnosis } from '@/api/logistics';
import CapacityDiagnosisBanner from '../CapacityDiagnosisBanner.vue';

/** el-alert 只关心少数几个我们用到的 prop/slot —— 用最小 stub 代替真组件（同项目既有惯例，见
 * OpinionInputDialog.spec.ts），非声明的 attrs（data-testid/data-verdict/type/closable）
 * 靠 Vue attrs fallthrough 落在根节点上，断言时能照常取到。 */
const globalStubs = {
  // 不声明 props —— 让 type/closable/show-icon/data-testid/data-verdict 全部走 Vue attrs
  // fallthrough 落在根节点上，断言时能直接用 attributes() 取到（同项目既有 stub 惯例的简化版）。
  'el-alert': {
    template: '<div class="el-alert"><slot name="title" /><slot /></div>',
  },
};

function baseDiagnosis(overrides: Partial<CapacityDiagnosis> = {}): CapacityDiagnosis {
  return {
    verdict: 'SUFFICIENT',
    totalDemandCbm: 9,
    totalDemandKg: 2100,
    fleetSingleRoundCbm: 20,
    fleetSingleRoundKg: 6400,
    vehicleCount: 2,
    usedTripCount: 2,
    multiTripVehicleCount: 0,
    unassignedCount: 0,
    suggestedAddCbm: 0,
    message: '运力充足 — 2 辆车一轮可送完 2 店 / 9.0m³。',
    ...overrides,
  };
}

describe('CapacityDiagnosisBanner', () => {
  it('renders nothing when diagnosis is null (old data / not-yet-generated plan)', () => {
    const wrapper = mount(CapacityDiagnosisBanner, {
      props: { diagnosis: null },
      global: { stubs: globalStubs },
    });
    expect(wrapper.find('[data-testid="capacity-diagnosis-banner"]').exists()).toBe(false);
  });

  it('SUFFICIENT — success alert, no next-action button, closable', () => {
    const wrapper = mount(CapacityDiagnosisBanner, {
      props: { diagnosis: baseDiagnosis() },
      global: { stubs: globalStubs },
    });
    const banner = wrapper.get('[data-testid="capacity-diagnosis-banner"]');
    expect(banner.attributes('data-verdict')).toBe('SUFFICIENT');
    expect(banner.attributes('type')).toBe('success');
    expect(banner.attributes('closable')).toBe('true');
    expect(wrapper.text()).toContain('运力充足');
    expect(wrapper.find('[data-testid="capacity-diagnosis-action"]').exists()).toBe(false);
  });

  it('INSUFFICIENT — warning alert, sticky (not closable), next-action button emits manage-vehicles', async () => {
    const wrapper = mount(CapacityDiagnosisBanner, {
      props: { diagnosis: baseDiagnosis({
        verdict: 'INSUFFICIENT',
        totalDemandCbm: 57.2,
        fleetSingleRoundCbm: 47,
        usedTripCount: 6,
        vehicleCount: 4,
        multiTripVehicleCount: 2,
        suggestedAddCbm: 11,
        message: '车队单轮运力不足 — 本批 57.2m³ 超过在册车队单轮 47.0m³，需跑 6 趟（有车回仓补货再出发）。建议增补约 11m³ 运力可减少回仓趟次。',
      }) },
      global: { stubs: globalStubs },
    });
    const banner = wrapper.get('[data-testid="capacity-diagnosis-banner"]');
    expect(banner.attributes('data-verdict')).toBe('INSUFFICIENT');
    expect(banner.attributes('type')).toBe('warning');
    expect(banner.attributes('closable')).toBe('false');
    expect(wrapper.text()).toContain('车队单轮运力不足');
    expect(wrapper.text()).toContain('57.2m³');

    const action = wrapper.get('[data-testid="capacity-diagnosis-action"]');
    await action.trigger('click');
    expect(wrapper.emitted('manage-vehicles')).toHaveLength(1);
  });

  it('UNSERVABLE — error alert, sticky, next-action button present', () => {
    const wrapper = mount(CapacityDiagnosisBanner, {
      props: { diagnosis: baseDiagnosis({
        verdict: 'UNSERVABLE',
        unassignedCount: 3,
        message: '3 单暂无法派送 — 所在区域无车覆盖，或单件体积/重量超最大车。请为相应区域增派车辆或联系管理员。',
      }) },
      global: { stubs: globalStubs },
    });
    const banner = wrapper.get('[data-testid="capacity-diagnosis-banner"]');
    expect(banner.attributes('data-verdict')).toBe('UNSERVABLE');
    expect(banner.attributes('type')).toBe('error');
    expect(banner.attributes('closable')).toBe('false');
    expect(wrapper.text()).toContain('3 单暂无法派送');
    expect(wrapper.find('[data-testid="capacity-diagnosis-action"]').exists()).toBe(true);
  });
});
