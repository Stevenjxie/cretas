import ElementPlus from 'element-plus';
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import WorkflowSkuPicker from '../WorkflowSkuPicker.vue';

const SEMI_OPTIONS = [
  { id: 'SKU-PIG-SEMI', name: '五香去骨猪蹄半成品', unit: 'kg' },
  { id: 'SKU-CHICKEN-SEMI', name: '干式熟成鸡半成品', unit: 'kg' },
];
const FINISHED_OPTIONS = [
  { id: 'SKU-PIG-FIN', name: '五香去骨猪蹄 400g', unit: '盒' },
];

interface PickerVm {
  filteredSemiOptions: typeof SEMI_OPTIONS;
  filteredFinishedOptions: typeof FINISHED_OPTIONS;
  handleFilter: (query: string) => void;
}

function mountPicker(modelValue = '') {
  return mount(WorkflowSkuPicker, {
    props: {
      modelValue,
      semiOptions: SEMI_OPTIONS,
      finishedOptions: FINISHED_OPTIONS,
      testId: 'sku-picker',
    },
    global: { plugins: [ElementPlus] },
  });
}

describe('WorkflowSkuPicker', () => {
  it('re-emits the underlying el-select change with the picked skuId (both semi/finished share one value shape)', () => {
    const wrapper = mountPicker();

    // 不依赖 Element Plus 被 teleport 到 body 的下拉 DOM 结构, 直接驱动 el-select 实例的
    // change 事件来验证 WorkflowSkuPicker 的转发逻辑。
    const select = wrapper.findComponent({ name: 'ElSelect' });
    select.vm.$emit('change', 'SKU-PIG-SEMI');

    expect(wrapper.emitted('change')).toEqual([['SKU-PIG-SEMI']]);
  });

  it('shows every semi/finished option when there is no search query', () => {
    const wrapper = mountPicker();
    const vm = wrapper.vm as unknown as PickerVm;

    expect(vm.filteredSemiOptions).toEqual(SEMI_OPTIONS);
    expect(vm.filteredFinishedOptions).toEqual(FINISHED_OPTIONS);
  });

  it('filters both groups by pinyin-initial or literal substring, case-insensitively', () => {
    const wrapper = mountPicker();
    const vm = wrapper.vm as unknown as PickerVm;

    vm.handleFilter('zt');
    expect(vm.filteredSemiOptions.map((o) => o.id)).toEqual(['SKU-PIG-SEMI']);
    expect(vm.filteredFinishedOptions.map((o) => o.id)).toEqual(['SKU-PIG-FIN']);
  });

  it('resets the filter when a query no longer matches anything', () => {
    const wrapper = mountPicker();
    const vm = wrapper.vm as unknown as PickerVm;

    vm.handleFilter('does-not-exist');
    expect(vm.filteredSemiOptions).toEqual([]);
    expect(vm.filteredFinishedOptions).toEqual([]);

    vm.handleFilter('');
    expect(vm.filteredSemiOptions).toEqual(SEMI_OPTIONS);
  });
});
