import ElementPlus, { ElOption, ElOptionGroup } from 'element-plus';
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { defineComponent, h } from 'vue';
import WorkflowMaterialNode from '../WorkflowMaterialNode.vue';
import type { MaterialNodeData } from '../types';

// WorkflowMaterialNode 用 @vue-flow/core 的 <Handle>，真实实现要求组件挂在
// <VueFlow> provider 下才能拿到画布上下文；单测只关心原料 picker 本身的分组/
// 过滤/提示逻辑，跟连线 handle 无关，桩掉即可（同 WorkflowProcessNode.spec.ts
// 里 PositionedHandleStub 的做法）。
const HandleStub = defineComponent({
  name: 'Handle',
  inheritAttrs: false,
  props: { id: String, type: String, position: String },
  setup() {
    return () => h('span', { 'data-testid': 'handle-stub' });
  },
});

/**
 * #3 (Steve 定: BOM 原料优先、可加其他) — 原料 Cell picker 按 BOM 原辅料清单
 * 分组置顶，仍可选其它，BOM 为空时给出跳转提示（不硬禁）。
 */

const RAW_OPTIONS = [
  { id: 'RM-PIG', name: '猪蹄', unit: 'kg' },
  { id: 'RM-CHICKEN', name: '鸡胸肉', unit: 'kg' },
  { id: 'RM-SALT', name: '食盐', unit: 'kg' },
];

const RAW_DATA: MaterialNodeData = {
  name: '入口原料',
  skuId: '',
  bound: false,
};

function mountNode(overrides: {
  bomRawMaterialIds?: string[];
  data?: MaterialNodeData;
} = {}) {
  return mount(WorkflowMaterialNode, {
    props: {
      kind: 'RAW_MATERIAL',
      data: overrides.data || RAW_DATA,
      canWrite: true,
      rawMaterialOptions: RAW_OPTIONS,
      bomRawMaterialIds: overrides.bomRawMaterialIds ?? [],
      semiOptions: [],
      finishedOptions: [],
    },
    global: {
      plugins: [ElementPlus],
      stubs: {
        Handle: HandleStub,
        // 组件里的 <router-link> 需要真实/桩装的 vue-router 才能 resolve；单测只关心
        // "BOM 为空时有一个跳转出口"这件事本身，不关心真实路由跳转行为，桩装即可。
        RouterLink: { template: '<a class="router-link-stub"><slot /></a>', props: ['to'] },
      },
    },
  });
}

describe('WorkflowMaterialNode raw material picker — BOM priority grouping (#3)', () => {
  it('splits candidates into a "本产品 BOM 原料" group (top) and an "其它原料" group when the product has a BOM', () => {
    const wrapper = mountNode({ bomRawMaterialIds: ['RM-PIG'] });

    const groups = wrapper.findAllComponents(ElOptionGroup);
    expect(groups).toHaveLength(2);
    expect(groups[0].props('label')).toBe('本产品 BOM 原料');
    expect(groups[1].props('label')).toBe('其它原料');

    const bomGroupOptions = groups[0].findAllComponents(ElOption).map((o) => o.props('value'));
    const otherGroupOptions = groups[1].findAllComponents(ElOption).map((o) => o.props('value'));
    expect(bomGroupOptions).toEqual(['RM-PIG']);
    expect(otherGroupOptions.sort()).toEqual(['RM-CHICKEN', 'RM-SALT']);
  });

  it('does not hard-block selecting a raw material outside the BOM (soft constraint per Steve)', () => {
    const wrapper = mountNode({ bomRawMaterialIds: ['RM-PIG'] });

    const groups = wrapper.findAllComponents(ElOptionGroup);
    const otherOption = groups[1].findAllComponents(ElOption).find((o) => o.props('value') === 'RM-SALT');
    expect(otherOption?.props('disabled')).toBeFalsy();
  });

  it('shows a single "全部原料" group (no BOM/other split) and a configure-BOM hint with a next-action link when the product has no BOM configured', async () => {
    const wrapper = mountNode({ bomRawMaterialIds: [] });

    const groups = wrapper.findAllComponents(ElOptionGroup);
    expect(groups).toHaveLength(1);
    expect(groups[0].props('label')).toBe('全部原料');
    expect(groups[0].findAllComponents(ElOption)).toHaveLength(RAW_OPTIONS.length);

    const hint = wrapper.get('[data-testid="bom-hint"]');
    expect(hint.text()).toContain('该产品尚未配置原辅料 BOM，建议先去配置');
    // #10: 「去配置」不再跳转页面, 改成按钮触发 configBom (父组件在右侧抽屉里打开 BOM 配置)
    const configBtn = hint.get('.bom-hint-link');
    await configBtn.trigger('click');
    expect(wrapper.emitted('configBom')).toBeTruthy();
  });

  it('filters both groups by pinyin-initial match, keeping BOM priority grouping intact while searching', async () => {
    const wrapper = mountNode({ bomRawMaterialIds: ['RM-CHICKEN'] });
    const select = wrapper.getComponent({ name: 'ElSelect' });

    // filter-method 场景下真正驱动可见性的是组件自己的 filteredBomRawOptions/
    // filteredOtherRawOptions computed（不依赖 el-select 内部私有过滤），所以直接
    // 调用绑定给 el-select 的 filter-method prop 函数，贴近真实输入触发路径。
    const filterMethod = select.props('filterMethod') as (query: string) => void;
    filterMethod('zt');
    await wrapper.vm.$nextTick();

    const groups = wrapper.findAllComponents(ElOptionGroup);
    // BOM 组本身只有「鸡胸肉」，拼音 zt 不命中鸡胸肉 -> BOM 组应该整体消失
    // (bomRawOptions.length === 0 分支之外，这里 bomRawOptions 本身不为空，只是
    // filteredBomRawOptions 过滤后为空 —— 组的 v-if 只看 bomRawOptions 是否为空,
    // 所以分组标题仍在，只是组内没有可见 option)。
    const bomGroup = groups.find((g) => g.props('label') === '本产品 BOM 原料');
    expect(bomGroup?.findAllComponents(ElOption)).toHaveLength(0);
    const otherGroup = groups.find((g) => g.props('label') === '其它原料');
    expect(otherGroup?.findAllComponents(ElOption).map((o) => o.props('value'))).toEqual(['RM-PIG']);
  });

  it('emits selectRawSku with the picked SKU id', async () => {
    const wrapper = mountNode({ bomRawMaterialIds: ['RM-PIG'] });
    const select = wrapper.getComponent({ name: 'ElSelect' });

    await select.vm.$emit('change', 'RM-PIG');

    expect(wrapper.emitted('selectRawSku')).toEqual([['RM-PIG']]);
  });
});
