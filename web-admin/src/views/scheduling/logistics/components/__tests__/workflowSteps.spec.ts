import { mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it } from 'vitest';
import Workbench from '../../workbench/index.vue';
import RecordsPage from '../../records/index.vue';
import OrdersPage from '../../orders/index.vue';
import ResourcesPage from '../../resources/index.vue';
import { resetLogisticsDemoState } from '../../useLogisticsDemoState';
import { useLogisticsDemoState } from '../../useLogisticsDemoState';
import ExportConfirmStep from '../ExportConfirmStep.vue';
import ManualConfirmStep from '../ManualConfirmStep.vue';
import OrderImportStep from '../OrderImportStep.vue';
import { MOCK_STORES, MOCK_VEHICLES } from '../../mockData';

const supportPageStubs = {
  ElAlert: { template: '<div><slot /></div>' },
  ElButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  ElCard: { template: '<section><slot /></section>' },
  ElRadioButton: { template: '<button><slot /></button>' },
  ElRadioGroup: { template: '<div><slot /></div>' },
  ElTable: { template: '<section><slot /></section>' },
  ElTableColumn: { props: ['label'], template: '<span :data-column-label="label" />' },
  ElTag: { template: '<span><slot /></span>' },
};

describe('logistics workbench steps', () => {
  beforeEach(() => resetLogisticsDemoState());

  it('renders the scheduling records support page with its core columns', () => {
    const page = mount(RecordsPage, { global: { stubs: supportPageStubs } });

    expect(page.text()).toContain('调度记录');
    expect(page.html()).toContain('日期');
    expect(page.html()).toContain('批次号');
    expect(page.html()).toContain('门店数');
    expect(page.html()).toContain('车次数');
    expect(page.html()).toContain('总里程');
  });

  it('renders the store and orders support page with its core columns', () => {
    const page = mount(OrdersPage, { global: { stubs: supportPageStubs } });

    expect(page.text()).toContain('门店与订单');
    expect(page.html()).toContain('门店编码');
    expect(page.html()).toContain('门店名称');
    expect(page.html()).toContain('配送地址');
    expect(page.html()).toContain('定位状态');
  });

  it('renders the vehicles and drivers support page with all resource sources', () => {
    const page = mount(ResourcesPage, { global: { stubs: supportPageStubs } });

    expect(page.text()).toContain('车辆与司机');
    expect(page.text()).toContain('自有');
    expect(page.text()).toContain('外协');
    expect(page.html()).toContain('车牌号');
    expect(page.html()).toContain('备班司机');
  });

  it('shows import validation before the user advances to route planning', async () => {
    const wrapper = mount(Workbench, { global: { stubs: { ElButton: false } } });

    expect(wrapper.find('[data-testid="import-step"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="map-step"]').exists()).toBe(false);

    await wrapper.get('[data-testid="import-orders"]').trigger('click');
    expect(wrapper.find('[data-testid="import-step"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('13 家门店的订单字段与配送地址已通过校验。');

    await wrapper.get('[data-testid="finish-schedule"]').trigger('click');

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

  it('confirms the export preview without resolving the pending vehicle trip', async () => {
    const state = useLogisticsDemoState();
    state.generateRoutes();
    state.activeStep.value = 'export';
    const wrapper = mount(Workbench, { global: { stubs: { ElButton: false } } });

    wrapper.findComponent(ExportConfirmStep).vm.$emit('confirm');
    await wrapper.vm.$nextTick();

    expect(state.activeStep.value).toBe('export');
    expect(wrapper.get('[data-testid="export-confirmed"]').text()).toContain('已确认导出');
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

  it('does not shift required fields when a quoted address contains a comma', async () => {
    const wrapper = mount(OrderImportStep, { props: { stores: MOCK_STORES, imported: false } });
    const file = new File([''], 'orders.csv', { type: 'text/csv' });
    Object.assign(file, { text: async () => '门店编号,门店名称,配送地址,时间窗\nS-001,测试门店,"苏州市,工业园区",' });
    const input = wrapper.get('[data-testid="csv-input"]');
    Object.defineProperty(input.element, 'files', { value: [file] });

    await input.trigger('change');

    expect(wrapper.get('[data-testid="import-validation"]').text()).toContain('第 2 行缺少时间窗');
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
      props: { confirmed: false, rows: [{ tripId: 'T-1', vehicle: '待匹配车辆', driver: '', storeIds: [], storeOrder: 'S-001', volume: '1.80', loadRate: '18%', distance: '12.50 km' }] },
    });

    expect(wrapper.html()).toContain('volume');
    expect(wrapper.html()).toContain('distance');
  });
});
