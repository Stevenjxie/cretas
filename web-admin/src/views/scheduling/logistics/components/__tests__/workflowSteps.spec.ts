import { mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it } from 'vitest';
import Workbench from '../../workbench/index.vue';
import { resetLogisticsDemoState } from '../../useLogisticsDemoState';
import { useLogisticsDemoState } from '../../useLogisticsDemoState';
import ExportConfirmStep from '../ExportConfirmStep.vue';
import ManualConfirmStep from '../ManualConfirmStep.vue';
import OrderImportStep from '../OrderImportStep.vue';
import { MOCK_STORES, MOCK_VEHICLES } from '../../mockData';

describe('logistics workbench steps', () => {
  beforeEach(() => resetLogisticsDemoState());

  it('shows one task stage at a time', async () => {
    const wrapper = mount(Workbench, { global: { stubs: { ElButton: false } } });

    expect(wrapper.find('[data-testid="import-step"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="map-step"]').exists()).toBe(false);

    await wrapper.get('[data-testid="import-orders"]').trigger('click');
    await wrapper.get('[data-testid="generate-routes"]').trigger('click');

    expect(wrapper.find('[data-testid="map-step"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="export-step"]').exists()).toBe(false);
  });

  it('opens the export preview while preserving the pending overflow trip', async () => {
    const state = useLogisticsDemoState();
    state.generateRoutes();
    for (const trip of state.scheduleResult.value.trips.filter((trip) => trip.status === 'draft')) {
      state.selectTrip(trip.id);
      state.confirmTrip();
    }
    state.activeStep.value = 'confirm';
    const wrapper = mount(Workbench, { global: { stubs: { ElButton: false } } });

    await wrapper.get('[data-testid="finish-schedule"]').trigger('click');

    expect(wrapper.find('[data-testid="export-step"]').exists()).toBe(true);
    expect(wrapper.get('[data-testid="pending-export-row"]').text()).toContain('待匹配车辆');
  });

  it('validates selected CSV headers without claiming it was persisted', async () => {
    const wrapper = mount(OrderImportStep, { props: { stores: MOCK_STORES, imported: false } });
    const file = new File(['门店编号,门店名称\nS-001,测试门店'], 'orders.csv', { type: 'text/csv' });
    Object.assign(file, { text: async () => '门店编号,门店名称\nS-001,测试门店' });

    const input = wrapper.get('[data-testid="csv-input"]');
    Object.defineProperty(input.element, 'files', { value: [file] });
    await input.trigger('change');

    expect(wrapper.get('[data-testid="import-validation"]').text()).toContain('缺少必填列');
    expect(wrapper.text()).toContain('不会持久化');
  });

  it('reports rows that omit a required CSV value', async () => {
    const wrapper = mount(OrderImportStep, { props: { stores: MOCK_STORES, imported: false } });
    const file = new File([''], 'orders.csv', { type: 'text/csv' });
    Object.assign(file, { text: async () => '门店编号,门店名称,配送地址,时间窗\nS-001,测试门店,,09:00-12:00' });
    const input = wrapper.get('[data-testid="csv-input"]');
    Object.defineProperty(input.element, 'files', { value: [file] });

    await input.trigger('change');

    expect(wrapper.get('[data-testid="import-validation"]').text()).toContain('第 2 行缺少配送地址');
  });

  it('shows a conditional conflict message when a resource assignment is rejected', async () => {
    const state = useLogisticsDemoState();
    state.generateRoutes();
    const pendingTrip = state.scheduleResult.value.trips.find((trip) => trip.status === 'needs_vehicle');
    state.selectTrip(pendingTrip?.id ?? null);
    state.activeStep.value = 'confirm';
    const wrapper = mount(Workbench, { global: { stubs: { ElButton: false } } });

    wrapper.findComponent(ManualConfirmStep).vm.$emit('assign-vehicle', 'V-01');
    await wrapper.vm.$nextTick();

    expect(wrapper.get('[data-testid="assignment-issue"]').attributes('title')).toBe('需要处理');
    expect(wrapper.get('[data-testid="assignment-issue"]').attributes('description')).toContain('已被其他车次使用');
  });

  it('includes prepared volume and distance in the final preview', () => {
    const wrapper = mount(ExportConfirmStep, {
      props: { rows: [{ tripId: 'T-1', vehicle: '待匹配车辆', driver: '', storeIds: [], storeOrder: 'S-001', volume: '1.80', loadRate: '18%', distance: '12.50 km' }] },
    });

    expect(wrapper.html()).toContain('volume');
    expect(wrapper.html()).toContain('distance');
  });
});
