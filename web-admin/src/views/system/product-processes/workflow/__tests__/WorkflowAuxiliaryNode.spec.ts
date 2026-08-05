import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import WorkflowAuxiliaryNode from '../WorkflowAuxiliaryNode.vue';

const baseData = {
  processName: '卤制',
  usageSupported: true,
  rows: [
    { id: 'r1', materialName: '八角', dosageText: '2 g/kg',
      markers: [{ glyph: '◷', kind: 'pot', title: '首锅 100% · 后续 60%' }] },
    { id: 'r2', materialName: '生抽', dosageText: '15 g/kg', markers: [] },
  ],
};

describe('辅料 cell', () => {
  it('渲染工序名与行数', () => {
    const w = mount(WorkflowAuxiliaryNode, { props: { id: 'x', data: baseData } });
    expect(w.text()).toContain('卤制');
    expect(w.text()).toContain('2 种');
  });

  it('有锅序时副标题说出对报工的后果', () => {
    const w = mount(WorkflowAuxiliaryNode, { props: { id: 'x', data: baseData } });
    expect(w.text(), '技术员勾一个开关车间就多两栏, 必须写出来').toContain('报工需录锅数');
  });

  it('无锅序时不说报工', () => {
    const data = { ...baseData, rows: [{ id: 'r2', materialName: '生抽', dosageText: '15 g/kg', markers: [] }] };
    const w = mount(WorkflowAuxiliaryNode, { props: { id: 'x', data } });
    expect(w.text()).not.toContain('报工需录锅数');
  });

  it('标记渲染出 glyph 且 title 可查', () => {
    const w = mount(WorkflowAuxiliaryNode, { props: { id: 'x', data: baseData } });
    const marker = w.find('[data-testid="aux-marker-pot"]');
    expect(marker.exists()).toBe(true);
    expect(marker.attributes('title')).toContain('60');
  });

  it('空态显示「未配」而不是空白', () => {
    const w = mount(WorkflowAuxiliaryNode, { props: { id: 'x', data: { ...baseData, rows: [] } } });
    expect(w.text()).toContain('未配');
    expect(w.find('[data-testid="aux-empty"]').exists()).toBe(true);
  });

  it('灰态说明原因且不给加辅料入口', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      props: { id: 'x', data: { ...baseData, usageSupported: false, rows: [] } },
    });
    expect(w.text()).toContain('换算契约');
    expect(w.find('[data-testid="aux-add"]').exists()).toBe(false);
  });

  it('可配时给加辅料入口并 emit', async () => {
    const w = mount(WorkflowAuxiliaryNode, { props: { id: 'x', data: baseData } });
    await w.find('[data-testid="aux-add"]').trigger('click');
    expect(w.emitted('add-row')).toBeTruthy();
  });
});
