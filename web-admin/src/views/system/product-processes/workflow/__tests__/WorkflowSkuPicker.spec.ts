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

interface CascaderNode {
  value: string;
  label: string;
  name?: string;
  isCreate?: boolean;
  children?: CascaderNode[];
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

function cascader(wrapper: ReturnType<typeof mountPicker>) {
  return wrapper.findComponent({ name: 'ElCascader' });
}

describe('WorkflowSkuPicker (真·两级 cascader)', () => {
  it('一级只有「半成品/成品」，二级才是各自 SKU', () => {
    const options = cascader(mountPicker()).props('options') as CascaderNode[];
    expect(options.map((o) => o.label)).toEqual(['半成品', '成品']);
    // 半成品二级 = 创建入口 + 两个半成品; 成品二级 = 一个成品
    expect(options[0].children?.map((c) => c.value)).toEqual(['__CREATE__', 'SKU-PIG-SEMI', 'SKU-CHICKEN-SEMI']);
    expect(options[1].children?.map((c) => c.value)).toEqual(['SKU-PIG-FIN']);
  });

  it('现场创建半成品固定在「半成品」二级首位', () => {
    const options = cascader(mountPicker()).props('options') as CascaderNode[];
    const firstSemiChild = options[0].children?.[0];
    expect(firstSemiChild?.value).toBe('__CREATE__');
    expect(firstSemiChild?.isCreate).toBe(true);
    // 成品分支不含创建入口
    expect(options[1].children?.some((c) => c.value === '__CREATE__')).toBe(false);
  });

  it('emitPath:false → v-model 值仍是扁平 skuId; change 原样转发', () => {
    const wrapper = mountPicker();
    const c = cascader(wrapper);
    expect((c.props('props') as { emitPath: boolean }).emitPath).toBe(false);
    c.vm.$emit('change', 'SKU-PIG-SEMI');
    expect(wrapper.emitted('change')).toEqual([['SKU-PIG-SEMI']]);
  });

  it('filter-method 支持拼音首字母/字面子串, 大小写不限; 创建入口搜索时始终保留', () => {
    const filter = cascader(mountPicker()).props('filterMethod') as (
      node: { data?: CascaderNode; text?: string }, keyword: string,
    ) => boolean;
    // zt = 猪蹄 拼音首字母
    expect(filter({ data: { value: 'SKU-PIG-SEMI', label: '五香去骨猪蹄半成品', name: '五香去骨猪蹄半成品' } }, 'zt')).toBe(true);
    expect(filter({ data: { value: 'SKU-CHICKEN-SEMI', label: '干式熟成鸡半成品', name: '干式熟成鸡半成品' } }, 'zt')).toBe(false);
    // 创建入口无论搜什么都保留 (不留死胡同)
    expect(filter({ data: { value: '__CREATE__', label: '＋ 现场创建半成品 SKU', isCreate: true } }, 'xxxyyy')).toBe(true);
  });
});
