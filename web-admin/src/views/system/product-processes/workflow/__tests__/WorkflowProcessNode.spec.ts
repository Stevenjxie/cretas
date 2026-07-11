import ElementPlus from 'element-plus';
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { defineComponent, h } from 'vue';
import WorkflowProcessNode from '../WorkflowProcessNode.vue';
import type { ProcessNodeData, ProcessPort } from '../types';

const processData: ProcessNodeData = {
  workProcessId: 'WP-CUT',
  processName: 'Cutting',
  inputUnit: 'kg',
  outputUnit: 'kg',
  ports: [
    { id: 'input-1', direction: 'INPUT', unit: 'kg', ordinal: 0 },
    {
      id: 'output-1',
      direction: 'OUTPUT',
      materialNodeId: 'material-1',
      materialKind: 'SEMI_FINISHED',
      unit: 'kg',
      ordinal: 0,
    },
  ],
  conversionRule: { mode: 'ACTUAL_WEIGHT' },
  reportingRequired: true,
};

const PositionedHandleStub = defineComponent({
  name: 'Handle',
  inheritAttrs: false,
  props: {
    id: { type: String, required: true },
    type: { type: String, required: true },
    position: { type: String, required: true },
    style: { type: Object, required: true },
  },
  setup(props) {
    return () => h('button', {
      type: 'button',
      'data-testid': 'workflow-handle',
      'data-handle-id': props.id,
      'data-handle-type': props.type,
      'data-position': props.position,
      style: [props.style, { width: '10px', height: '10px' }],
    });
  },
});

function mountNode(selected = true, data: ProcessNodeData = processData) {
  return mount(WorkflowProcessNode, {
    props: {
      data,
      selected,
      canWrite: true,
    },
    global: {
      plugins: [ElementPlus],
      stubs: { Handle: PositionedHandleStub },
    },
  });
}

function withPorts(overrides: Partial<ProcessNodeData>): ProcessNodeData {
  return {
    ...processData,
    ...overrides,
  };
}

describe('WorkflowProcessNode output gestures', () => {
  it.each(['add-output-inline', 'add-output-edge'])(
    '%s emits the same addOutput action once',
    async (testId) => {
      const wrapper = mountNode();

      await wrapper.get(`[data-testid="${testId}"]`).trigger('click');

      expect(wrapper.emitted('addOutput')).toHaveLength(1);
    },
  );

  it('keeps the inline action visible and reveals the edge action on hover', async () => {
    const wrapper = mountNode(false);

    expect(wrapper.find('[data-testid="add-output-inline"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="add-output-edge"]').exists()).toBe(false);

    await wrapper.get('.process-node').trigger('mouseenter');

    expect(wrapper.find('[data-testid="add-output-edge"]').exists()).toBe(true);
    await wrapper.get('[data-testid="add-output-edge"]').trigger('click');
    expect(wrapper.emitted('addOutput')).toHaveLength(1);
  });

  it('does not expose a manual output material kind selector', () => {
    const wrapper = mountNode();

    expect(wrapper.find('[data-testid="output-kind-select"]').exists()).toBe(false);
    expect(wrapper.find('.kind-select').exists()).toBe(false);
  });

  it('does not expose a SKU selector on the process Cell (SKU binding lives on the material Cell)', () => {
    const wrapper = mountNode();

    expect(wrapper.find('.sku-select').exists()).toBe(false);
    expect(wrapper.find('[data-testid="material-sku-select"]').exists()).toBe(false);
    expect(wrapper.emitted('selectOutputSku')).toBeUndefined();
  });

  it('renders investment and output units as read-only chips, not editable selects', () => {
    const wrapper = mountNode();

    expect(wrapper.find('.unit-select').exists()).toBe(false);
    expect(wrapper.get('[data-testid="input-unit-chip"]').text()).toBe('kg');
    expect(wrapper.get('[data-testid="output-unit-chip"]').text()).toBe('kg');
  });

  it('displays the produced material name read-only on the output row', () => {
    const wrapper = mountNode();
    const outputRow = wrapper.get('.output-row');

    expect(outputRow.find('input').attributes('readonly')).toBeDefined();
  });

  it('does not emit addOutput when a workflow handle is clicked', async () => {
    const wrapper = mountNode();

    await wrapper.get('[data-testid="workflow-handle"]').trigger('click');

    expect(wrapper.emitted('addOutput')).toBeUndefined();
  });

  it('keeps the edge action separated from the source handle without a hover gap', () => {
    const wrapper = mountNode();
    const processNode = wrapper.get('.process-node');
    const sourceHandle = wrapper.get('[data-handle-type="source"]');
    const edgeAction = wrapper.get('[data-testid="add-output-edge"]');

    expect(sourceHandle.attributes('data-position')).toBe('right');
    const sourceTopPercent = Number.parseFloat((sourceHandle.element as HTMLElement).style.top);
    const nodeMinHeight = Number.parseFloat((processNode.element as HTMLElement).style.minHeight);
    const edgeTop = Number.parseFloat((edgeAction.element as HTMLElement).style.top);
    const edgeRight = Number.parseFloat((edgeAction.element as HTMLElement).style.right);
    expect({ sourceTopPercent, nodeMinHeight, edgeTop, edgeRight }).toEqual({
      sourceTopPercent: 50,
      nodeMinHeight: 96,
      edgeTop: 12,
      edgeRight: -14,
    });

    const sourceCenterY = nodeMinHeight * sourceTopPercent / 100;
    const sourceTop = sourceCenterY - 10 / 2;
    const edgeBottom = edgeTop + 28;
    expect(edgeBottom).toBeLessThanOrEqual(sourceTop);

    const edgeLeftFromNodeRight = -edgeRight - 28;
    expect(edgeLeftFromNodeRight).toBeLessThanOrEqual(0);
  });
});

describe('WorkflowProcessNode 系统研判 + 数量关系 (P2)', () => {
  it('shows a single-in/single-out 系统研判 line without the multi-source/multi-output suffixes', () => {
    const wrapper = mountNode();
    const badge = wrapper.get('[data-testid="system-inference"]');

    expect(badge.text()).toContain('系统研判');
    expect(badge.text()).toContain('1 入');
    expect(badge.text()).toContain('1 出');
    expect(badge.text()).not.toContain('多来源合流');
    expect(badge.text()).not.toContain('同时多产出');
    expect(badge.text()).toContain('增删左右的来源/产出 Cell 后自动更新');
  });

  it('reflects multi-input/multi-output port counts with the 合流/多产出 suffixes', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', materialName: '猪前腿肉', unit: 'kg', ordinal: 0 },
      { id: 'in-2', direction: 'INPUT', materialName: '猪五花肉', unit: 'kg', ordinal: 1 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '腌制猪肉', unit: 'kg', ordinal: 0 },
      { id: 'out-2', direction: 'OUTPUT', materialName: '猪皮下脚料', unit: 'kg', ordinal: 1 },
      { id: 'out-3', direction: 'OUTPUT', materialName: '肉汁', unit: 'kg', ordinal: 2 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));
    const badge = wrapper.get('[data-testid="system-inference"]');

    expect(badge.text()).toContain('2 入（多来源合流）');
    expect(badge.text()).toContain('3 出（同时多产出）');
  });

  it('keeps the conversion mode select present and editable', () => {
    const wrapper = mountNode();

    const select = wrapper.get('.conversion-row .el-select');
    expect(select.exists()).toBe(true);
    expect(select.classes()).not.toContain('is-disabled');
  });

  it('shows a name-based placeholder on the expression input for SUM_OUTPUTS', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', unit: 'kg', ordinal: 0 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '瘦肉出品', unit: 'kg', ordinal: 0 },
      { id: 'out-2', direction: 'OUTPUT', materialName: '肥肉出品', unit: 'kg', ordinal: 1 },
    ];
    const wrapper = mountNode(true, withPorts({
      ports,
      conversionRule: { mode: 'SUM_OUTPUTS', expression: null },
    }));

    const input = wrapper.get('[data-testid="conversion-expression-input"]');
    expect(input.attributes('placeholder')).toBe('投入 = 瘦肉出品 + 肥肉出品');
  });

  it('renders the ACTUAL_WEIGHT semantic sentence using real material names + units', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', materialName: '整猪', unit: 'kg', ordinal: 0 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '分割肉', unit: 'kg', ordinal: 0 },
    ];
    const wrapper = mountNode(true, withPorts({
      ports,
      conversionRule: { mode: 'ACTUAL_WEIGHT' },
    }));

    const sentence = wrapper.get('[data-testid="conversion-sentence"]').text();
    expect(sentence).toContain('按实际称重');
    expect(sentence).toContain('整猪');
    expect(sentence).toContain('分割肉');
    expect(sentence).toContain('kg');
    expect(sentence).toContain('报工记录实际投入/产出');
  });

  it('renders the FIXED_RATIO semantic sentence from the user expression, with a fallback hint when empty', () => {
    const withExpr = mountNode(true, withPorts({
      conversionRule: { mode: 'FIXED_RATIO', expression: '1 只 = 1 只' },
    }));
    expect(withExpr.get('[data-testid="conversion-sentence"]').text()).toBe('固定比例：1 只 = 1 只');

    const withoutExpr = mountNode(true, withPorts({
      conversionRule: { mode: 'FIXED_RATIO', expression: null },
    }));
    expect(withoutExpr.get('[data-testid="conversion-sentence"]').text()).toContain('固定比例');
    expect(withoutExpr.get('[data-testid="conversion-sentence"]').text()).toContain('待填');
  });

  it('renders the SUM_OUTPUTS semantic sentence listing every output material name', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', unit: 'kg', ordinal: 0 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '瘦肉出品', unit: 'kg', ordinal: 0 },
      { id: 'out-2', direction: 'OUTPUT', materialName: '肥肉出品', unit: 'kg', ordinal: 1 },
    ];
    const wrapper = mountNode(true, withPorts({
      ports,
      conversionRule: { mode: 'SUM_OUTPUTS', expression: null },
    }));

    const sentence = wrapper.get('[data-testid="conversion-sentence"]').text();
    expect(sentence).toContain('投入 = 瘦肉出品 + 肥肉出品');
    expect(sentence).toContain('按各自实际数量');
  });

  it('renders the FORMULA semantic sentence from the user expression', () => {
    const wrapper = mountNode(true, withPorts({
      conversionRule: { mode: 'FORMULA', expression: '(产出1 + 产出2) * 0.95' },
    }));

    expect(wrapper.get('[data-testid="conversion-sentence"]').text()).toBe('自定义：(产出1 + 产出2) * 0.95');
  });

  it('renders a labeled 样例 line derived from real material names, not an editable field', () => {
    const wrapper = mountNode();
    const sample = wrapper.get('[data-testid="conversion-sample"]');

    expect(sample.text()).toContain('样例');
    expect(sample.element.tagName.toLowerCase()).not.toBe('input');
    expect(sample.find('input').exists()).toBe(false);
  });

  it('renders a multi-output 样例 line when there are 2+ output ports', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', unit: 'kg', ordinal: 0 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '瘦肉出品', unit: 'kg', ordinal: 0 },
      { id: 'out-2', direction: 'OUTPUT', materialName: '肥肉出品', unit: 'kg', ordinal: 1 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));

    expect(wrapper.get('[data-testid="conversion-sample"]').text()).toContain('样例：投入 = Σ 各产出');
  });

  it('keeps P1 guarantees intact: no SKU picker on the process Cell, units stay read-only chips', () => {
    const wrapper = mountNode();

    expect(wrapper.find('.sku-select').exists()).toBe(false);
    expect(wrapper.find('[data-testid="material-sku-select"]').exists()).toBe(false);
    expect(wrapper.find('.unit-select').exists()).toBe(false);
    expect(wrapper.get('[data-testid="input-unit-chip"]').text()).toBe('kg');
    expect(wrapper.get('[data-testid="output-unit-chip"]').text()).toBe('kg');
  });
});
