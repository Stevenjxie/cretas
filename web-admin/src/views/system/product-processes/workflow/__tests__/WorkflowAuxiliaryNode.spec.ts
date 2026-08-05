import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import WorkflowAuxiliaryNode from '../WorkflowAuxiliaryNode.vue';

// WorkflowAuxiliaryNode 用 @vue-flow/core 的 <Handle>，真实实现要求组件挂在 <VueFlow>
// provider 下才能拿到画布上下文；单测只关心辅料 cell 本身的展示/交互逻辑，跟连线
// handle 无关，桩掉即可（同 WorkflowMaterialNode.spec.ts 的做法）。
const HandleStub = defineComponent({
  name: 'Handle',
  inheritAttrs: false,
  props: { id: String, type: String, position: String },
  setup() {
    return () => h('span', { 'data-testid': 'handle-stub' });
  },
});

const baseData = {
  processName: '卤制',
  processNodeId: 'p1',
  usageSupported: true,
  rows: [
    { id: 'r1', materialName: '八角', dosageText: '2 g/kg',
      markers: [{ glyph: '◷', kind: 'pot', title: '首锅 100% · 后续 60%' }] },
    { id: 'r2', materialName: '生抽', dosageText: '15 g/kg', markers: [] },
  ],
};

describe('辅料 cell', () => {
  it('渲染工序名与行数', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: baseData } });
    expect(w.text()).toContain('卤制');
    expect(w.text()).toContain('2 种');
  });

  it('有锅序时副标题说出对报工的后果', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: baseData } });
    expect(w.text(), '技术员勾一个开关车间就多两栏, 必须写出来').toContain('报工需录锅数');
  });

  it('无锅序时不说报工', () => {
    const data = { ...baseData, rows: [{ id: 'r2', materialName: '生抽', dosageText: '15 g/kg', markers: [] }] };
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data } });
    expect(w.text()).not.toContain('报工需录锅数');
  });

  it('标记渲染出 glyph 且 title 可查', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: baseData } });
    const marker = w.find('[data-testid="aux-marker-pot"]');
    expect(marker.exists()).toBe(true);
    expect(marker.attributes('title')).toContain('60');
  });

  it('空态显示「未配」而不是空白', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: { ...baseData, rows: [] } } });
    expect(w.text()).toContain('未配');
    expect(w.find('[data-testid="aux-empty"]').exists()).toBe(true);
  });

  it('灰态说明原因且不给加辅料入口', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: { ...baseData, usageSupported: false, rows: [] } },
    });
    expect(w.text()).toContain('换算契约');
    expect(w.find('[data-testid="aux-add"]').exists()).toBe(false);
  });

  it('可配时给加辅料入口并 emit', async () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: baseData } });
    await w.find('[data-testid="aux-add"]').trigger('click');
    expect(w.emitted('add-row')).toBeTruthy();
  });

  it('只读用户(canWrite=false)即使可配也不给加辅料入口', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: false, data: baseData },
    });
    expect(w.find('[data-testid="aux-add"]').exists()).toBe(false);
  });

  it('只读用户点击行不触发 edit-row', async () => {
    const w = mount(WorkflowAuxiliaryNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: false, data: baseData },
    });
    await w.find('.aux-row').trigger('click');
    expect(w.emitted('edit-row')).toBeFalsy();
  });
});
