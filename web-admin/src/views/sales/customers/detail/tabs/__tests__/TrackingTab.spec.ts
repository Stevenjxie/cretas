/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 (Phase F) — TrackingTab spec.
 * 重点: fetch on mount + state machine + dialog header (R2).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref } from 'vue';
import TrackingTab from '../TrackingTab.vue';

const listMock = vi.fn();
vi.mock('@/api/customerTracking', () => {
  // Define inside factory because vi.mock is hoisted to top of file
  const types = [
    { value: 'PHONE', label: '电话沟通' },
    { value: 'WECHAT', label: '微信沟通' },
    { value: 'EMAIL', label: '邮件沟通' },
    { value: 'VISIT', label: '上门拜访' },
    { value: 'VIDEO', label: '视频会议' },
    { value: 'OTHER', label: '其他' },
  ];
  return {
    listTrackingRecords: (...a: any[]) => listMock(...a),
    createTrackingRecord: vi.fn(),
    updateTrackingRecord: vi.fn(),
    deleteTrackingRecord: vi.fn(),
    TRACKING_TYPES: types,
    trackingTypeLabel: (t: string) => types.find((x) => x.value === t)?.label || t,
  };
});

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: ref('F999') }),
}));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

const globalStubs = {
  'el-skeleton': { template: '<div />' },
  'el-empty': { props: ['description', 'imageSize'], template: '<div class="el-empty"><slot /></div>' },
  'el-table': {
    props: ['data'],
    template: '<div class="el-table-stub" :data-count="data?.length || 0" :data-rows-json="JSON.stringify(data)" />',
  },
  'el-table-column': { props: ['label', 'prop'], template: '<div />' },
  'el-pagination': { template: '<div />' },
  'el-icon': { template: '<i />' },
  'el-button': {
    props: ['type', 'loading', 'icon'],
    template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>',
    emits: ['click'],
  },
  'el-dialog': {
    props: ['modelValue', 'title'],
    template: '<div class="el-dialog" v-if="modelValue"><h3 class="title">{{ title }}</h3><slot /><div class="footer"><slot name="footer" /></div></div>',
    emits: ['update:modelValue'],
  },
  'el-form': {
    props: ['model', 'rules', 'labelWidth'],
    template: '<form><slot /></form>',
    methods: { validate(cb: any) { return Promise.resolve(cb(true)); } },
  },
  'el-form-item': { props: ['label', 'prop'], template: '<div><slot /></div>' },
  'el-input': {
    props: ['modelValue', 'type', 'rows', 'placeholder'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
    emits: ['update:modelValue'],
  },
  'el-select': {
    props: ['modelValue', 'placeholder'],
    template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
    emits: ['update:modelValue'],
  },
  'el-option': { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  'el-tag': { props: ['size', 'type'], template: '<span class="el-tag"><slot /></span>' },
  'el-result': { template: '<div><slot name="extra" /></div>' },
};

const customerMock = {
  id: 'cust-1',
  name: '六腾门食品',
  customerCode: 'CUST-F006-0001',
};

describe('TrackingTab', () => {
  beforeEach(() => {
    listMock.mockReset();
  });

  it('fetches records on mount', async () => {
    listMock.mockResolvedValueOnce({ content: [], totalElements: 0, totalPages: 0 });
    mount(TrackingTab, {
      props: { customerId: 'cust-1', customer: customerMock as any },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    expect(listMock).toHaveBeenCalled();
    expect(listMock.mock.calls[0][0]).toBe('F999');
    expect(listMock.mock.calls[0][1]).toMatchObject({ customerId: 'cust-1' });
  });

  it('shows empty state when no records', async () => {
    listMock.mockResolvedValueOnce({ content: [], totalElements: 0, totalPages: 0 });
    const w = mount(TrackingTab, {
      props: { customerId: 'cust-1', customer: customerMock as any },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    expect(w.find('.el-empty').exists()).toBe(true);
  });

  it('renders records count when data present', async () => {
    listMock.mockResolvedValueOnce({
      content: [
        { id: 1, customerId: 'cust-1', content: '电话联系客户', recordTime: '2026-05-17T10:00:00', recorderName: '张三' },
      ],
      totalElements: 1,
      totalPages: 1,
    });
    const w = mount(TrackingTab, {
      props: { customerId: 'cust-1', customer: customerMock as any },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    const table = w.find('.el-table-stub');
    expect(table.attributes('data-count')).toBe('1');
    const rows = JSON.parse(table.attributes('data-rows-json') || '[]');
    expect(rows[0].content).toBe('电话联系客户');
  });

  it('R2: new dialog title contains customer name + code', async () => {
    listMock.mockResolvedValueOnce({ content: [], totalElements: 0, totalPages: 0 });
    const w = mount(TrackingTab, {
      props: { customerId: 'cust-1', customer: customerMock as any },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    // "新增跟踪" appears in BOTH toolbar button AND dialog title — find the BUTTON specifically
    const addBtn = w.findAll('button.el-button').find((b) => b.text().trim() === '新增跟踪');
    expect(addBtn).toBeTruthy();
    await addBtn!.trigger('click');
    await flushPromises();
    // dialog title is the .title INSIDE .el-dialog (not the toolbar .title)
    const dialogTitle = w.find('.el-dialog .title').text();
    expect(dialogTitle).toContain('新增跟踪');
    expect(dialogTitle).toContain('六腾门食品');
    expect(dialogTitle).toContain('CUST-F006-0001');
  });
});
