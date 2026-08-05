import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import WorkflowPackagingNode from '../WorkflowPackagingNode.vue';
import type { PackagingCellData } from '../WorkflowPackagingNode.vue';

const data: PackagingCellData = {
  outputName: '鸭油',
  baseUnit: 'kg',
  rows: [{
    id: 'r1', materialName: '塑料桶 20L', dosageText: '0.05 个/kg',
    naturalHint: '= 1 个 / 20 kg', markers: [],
  }],
};

describe('包材 cell', () => {
  it('副标题带出分母', () => {
    const w = mount(WorkflowPackagingNode, { props: { id: 'x', data } });
    expect(w.text(), '「1 个/盒」和「1 个/kg」不是一回事, 分母必须写出来').toContain('每 1 kg');
  });

  it('换算过的用量保留原始表达', () => {
    const w = mount(WorkflowPackagingNode, { props: { id: 'x', data } });
    const cell = w.find('[data-testid="pack-qty-r1"]');
    expect(cell.attributes('title'), '「0.05 个」对仓管毫无意义, 「1 桶装 20kg」才是他认识的').toContain('20 kg');
  });

  it('没有 naturalHint 时不设置 title 属性(而不是空串)', () => {
    const noHint: PackagingCellData = {
      ...data,
      rows: [{ id: 'r2', materialName: '标签纸', dosageText: '1 张/kg', markers: [] }],
    };
    const w = mount(WorkflowPackagingNode, { props: { id: 'x', data: noHint } });
    const cell = w.find('[data-testid="pack-qty-r2"]');
    expect(cell.attributes('title')).toBeUndefined();
  });

  it('多层包装副标题说层数', () => {
    const layered: PackagingCellData = { ...data, rows: [
      { id: 'a', materialName: '内袋', dosageText: '1 个/盒', markers: [{ glyph: '▤', kind: 'lvl', title: '内袋' }] },
      { id: 'b', materialName: '外箱', dosageText: '0.125 个/盒', markers: [{ glyph: '▤', kind: 'lvl', title: '1 箱 8 盒' }] },
    ] };
    const w = mount(WorkflowPackagingNode, { props: { id: 'x', data: layered } });
    expect(w.text()).toContain('分 2 层');
    expect(w.find('[data-testid="pack-marker-lvl"]').exists()).toBe(true);
  });

  it('空态说出发布后果', () => {
    const w = mount(WorkflowPackagingNode, { props: { id: 'x', data: { ...data, rows: [] } } });
    expect(w.text()).toContain('未配');
    expect(w.text(), '缺包材整条工艺发布不了, 这个后果要提前说').toContain('发布不了');
    expect(w.find('[data-testid="pack-empty"]').exists()).toBe(true);
  });

  it('pack-add 触发 add-row 事件', async () => {
    const w = mount(WorkflowPackagingNode, { props: { id: 'x', data } });
    await w.find('[data-testid="pack-add"]').trigger('click');
    expect(w.emitted('add-row')).toBeTruthy();
  });

  it('点击行触发 edit-row 事件, 携带 rowId', async () => {
    const w = mount(WorkflowPackagingNode, { props: { id: 'x', data } });
    await w.find('[data-testid="pack-qty-r1"]').trigger('click');
    expect(w.emitted('edit-row')?.[0]).toEqual(['r1']);
  });

  it('缺失用量渲染显式占位, 不降级成 0 或空白', () => {
    const missingQty: PackagingCellData = {
      ...data,
      rows: [{ id: 'r3', materialName: '内衬纸', dosageText: '', markers: [] }],
    };
    const w = mount(WorkflowPackagingNode, { props: { id: 'x', data: missingQty } });
    const cell = w.find('[data-testid="pack-qty-r3"]');
    expect(cell.text()).not.toBe('0');
    expect(cell.text()).not.toBe('');
    expect(cell.text()).toContain('待补全');
  });
});
