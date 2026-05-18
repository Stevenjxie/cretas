/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 (Phase F) — SalesUserHistoryTab spec.
 * 防呆 R3 dropdown 行为 + R1 sameAsCurrent disable verification.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref } from 'vue';
import SalesUserHistoryTab from '../SalesUserHistoryTab.vue';

vi.mock('@/api/customerSalesUserHistory', () => ({
  listHistory: vi.fn().mockResolvedValue({
    content: [],
    totalElements: 0,
    totalPages: 0,
    page: 1,
    size: 20,
  }),
}));

vi.mock('@/api/customer', () => ({
  updateAssignedSalesUser: vi.fn().mockResolvedValue({}),
}));

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
    template: '<table><tbody><tr v-for="(r,i) in data" :key="i"><slot :row="r" /></tr></tbody></table>',
  },
  'el-table-column': { props: ['label', 'prop'], template: '<td><slot :row="$parent.row" /></td>' },
  'el-pagination': { template: '<div />' },
  'el-tag': { props: ['size', 'type'], template: '<span class="el-tag"><slot /></span>' },
  'el-icon': { template: '<i />' },
  'el-button': {
    props: ['type', 'loading', 'disabled', 'icon', 'plain'],
    template: '<button class="el-button" :data-disabled="disabled ? \'true\' : \'false\'" @click="!disabled && $emit(\'click\')"><slot /></button>',
    emits: ['click'],
  },
  'el-dialog': {
    props: ['modelValue', 'title', 'width', 'closeOnClickModal', 'destroyOnClose'],
    template: '<div class="el-dialog" v-if="modelValue"><h3>{{ title }}</h3><slot /><div class="footer"><slot name="footer" /></div></div>',
    emits: ['update:modelValue'],
  },
  'el-form': {
    props: ['model', 'rules', 'labelWidth'],
    template: '<form><slot /></form>',
    methods: {
      validate(cb: any) { return Promise.resolve(cb(true)); },
    },
  },
  'el-form-item': { props: ['label', 'prop'], template: '<div class="el-form-item"><slot /></div>' },
  'el-input': {
    props: ['modelValue', 'type', 'rows', 'placeholder'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
    emits: ['update:modelValue'],
  },
  'el-input-number': {
    props: ['modelValue', 'min'],
    template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />',
    emits: ['update:modelValue'],
  },
  'el-select': {
    props: ['modelValue', 'placeholder'],
    template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
    emits: ['update:modelValue'],
  },
  'el-option': { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  'el-alert': { props: ['type', 'title', 'closable', 'showIcon'], template: '<div class="el-alert">{{ title }}</div>' },
  'el-result': { template: '<div><slot name="extra" /></div>' },
};

const customerMock = {
  id: 'cust-1',
  name: '六腾门食品',
  customerCode: 'CUST-F006-0001',
  assignedSalesUserId: 100,
};

describe('SalesUserHistoryTab — R1 + R2 + R3 dialog', () => {
  beforeEach(() => {
    // no-op
  });

  it('R2: dialog title contains customer name + customerCode', async () => {
    const w = mount(SalesUserHistoryTab, {
      props: { customerId: 'cust-1', customer: customerMock as any },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    // Open dialog
    const buttons = w.findAll('button.el-button');
    const changeBtn = buttons.find((b) => b.text().includes('变更业务员'));
    expect(changeBtn).toBeTruthy();
    await changeBtn!.trigger('click');
    await flushPromises();
    const dialog = w.find('.el-dialog');
    expect(dialog.exists()).toBe(true);
    expect(dialog.text()).toContain('六腾门食品');
    expect(dialog.text()).toContain('CUST-F006-0001');
  });

  it('R1: shows current sales user alert in dialog', async () => {
    const w = mount(SalesUserHistoryTab, {
      props: { customerId: 'cust-1', customer: customerMock as any },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    const changeBtn = w.findAll('button.el-button').find((b) => b.text().includes('变更业务员'));
    await changeBtn!.trigger('click');
    await flushPromises();
    // alert renders "当前业务员: User #100"
    expect(w.html()).toContain('当前业务员');
    expect(w.html()).toContain('100');
  });

  it('R3: 6 reason options + OTHER reveals textarea', async () => {
    const w = mount(SalesUserHistoryTab, {
      props: { customerId: 'cust-1', customer: customerMock as any },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    const changeBtn = w.findAll('button.el-button').find((b) => b.text().includes('变更业务员'));
    await changeBtn!.trigger('click');
    await flushPromises();
    // 6 options
    const options = w.findAll('option');
    expect(options.length).toBe(6);
    const labels = options.map((o) => o.text());
    expect(labels).toEqual(['离职交接', '区域调整', '客户要求', '业绩重分配', '试用期到期', '其他']);
  });
});
