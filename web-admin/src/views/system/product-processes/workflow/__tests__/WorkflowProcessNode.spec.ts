import ElementPlus, { ElOption } from 'element-plus';
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { defineComponent, h } from 'vue';
import WorkflowProcessNode from '../WorkflowProcessNode.vue';
import WorkflowSkuPicker from '../WorkflowSkuPicker.vue';
import type { ProcessNodeData, ProcessPort } from '../types';

const SEMI_OPTIONS = [{ id: 'SKU-SEMI-1', name: '半成品 A', unit: 'kg' }];
const FINISHED_OPTIONS = [{ id: 'SKU-FIN-1', name: '成品 A', unit: '盒' }];

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
      quantityMode: 'AUTO_CONVERT',
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
      semiOptions: SEMI_OPTIONS,
      finishedOptions: FINISHED_OPTIONS,
      skuSpecifications: {
        'SKU-FIN-800': { unit: '盒', gramsPerUnit: 800 },
      },
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

  it('exposes a clear pencil action for editing the real process master data', async () => {
    const wrapper = mountNode();
    const button = wrapper.get('[data-testid="quick-edit-process"]');

    expect(button.attributes('title')).toBe('快捷编辑工序');
    expect(button.attributes('aria-label')).toBe('快捷编辑工序');
    await button.trigger('click');
    expect(wrapper.emitted('editProcess')).toHaveLength(1);
  });

  it('P3: exposes an editable output SKU picker on each output row and emits selectOutput(portId, skuId)', () => {
    const wrapper = mountNode();
    // 现在允许在工序 Cell 上直接选/改产出物料的 SKU (不再只能通过右侧物料 Cell)。
    const picker = wrapper.findComponent(WorkflowSkuPicker);
    expect(picker.exists()).toBe(true);
    expect(picker.props('semiOptions')).toEqual(SEMI_OPTIONS);
    expect(picker.props('finishedOptions')).toEqual(FINISHED_OPTIONS);

    picker.vm.$emit('change', 'SKU-FIN-1');

    expect(wrapper.emitted('selectOutput')).toEqual([['output-1', 'SKU-FIN-1']]);
  });

  it('renders investment units as a read-only chip and output units as a live chip (unit follows the picked SKU)', () => {
    const wrapper = mountNode();

    expect(wrapper.find('.unit-select').exists()).toBe(false);
    expect(wrapper.get('[data-testid="input-unit-chip"]').text()).toBe('kg');
    expect(wrapper.get('[data-testid="output-unit-chip"]').text()).toBe('kg');
  });

  it('renders the produced material as an editable SKU picker on the output row, not a readonly input', () => {
    const wrapper = mountNode();
    const outputRow = wrapper.get('.output-row');

    expect(outputRow.findComponent(WorkflowSkuPicker).exists()).toBe(true);
    expect(outputRow.find('input[readonly]').exists()).toBe(false);
  });

  it('keeps the investment row read-only (only the output row became editable)', () => {
    const wrapper = mountNode();
    const inputRow = wrapper.get('.port-section:not(.output-section) .port-row');

    expect(inputRow.find('input').attributes('readonly')).toBeDefined();
    expect(inputRow.findComponent(WorkflowSkuPicker).exists()).toBe(false);
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

  it('shows a direct same-row input-to-output relationship even when units match', () => {
    const wrapper = mountNode();

    expect(wrapper.get('[data-testid="quantity-baseline"]').text()).toContain('产出基准');
    expect(wrapper.get('[data-testid="fixed-input-quantity"]').text()).toContain('kg投入=1kg产出');
    expect(wrapper.findComponent({ name: 'ElInputNumber' }).exists()).toBe(true);
  });

  it('renders every multi-output port independently without forcing a shared ratio', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', unit: 'kg', quantityMode: 'FIXED_RATIO', standardQuantity: 1, ordinal: 0 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '瘦肉出品', unit: 'g', quantityMode: 'AUTO_CONVERT', ordinal: 0 },
      { id: 'out-2', direction: 'OUTPUT', materialName: '装盒成品', unit: '盒', quantityMode: 'FIXED_RATIO', standardQuantity: 2, ordinal: 1 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));

    expect(wrapper.findAll('[data-testid="auto-convert-output"]')).toHaveLength(0);
    expect(wrapper.findAll('[data-testid="fixed-output-quantity"]')).toHaveLength(1);
    expect(wrapper.findAllComponents({ name: 'ElInputNumber' })).toHaveLength(2);
  });

  it('does not offer a generic conversion mode selector', () => {
    const wrapper = mountNode();

    const modeOptionValues = wrapper.findAllComponents(ElOption)
      .map((option) => option.props('value'))
      .filter((value): value is string => (
        typeof value === 'string' && ['ACTUAL_WEIGHT', 'FIXED_RATIO', 'SUM_OUTPUTS', 'FORMULA'].includes(value)
      ));

    expect(modeOptionValues).toEqual([]);
    expect(wrapper.find('.conversion-select').exists()).toBe(false);
  });

  it('labels inputs by display order and gives every input its own output-relative quantity', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', materialName: '主料', unit: 'kg', quantityMode: 'FIXED_RATIO', standardQuantity: 1, ordinal: 0 },
      { id: 'in-2', direction: 'INPUT', materialName: '调味料', unit: 'g', quantityMode: 'FIXED_RATIO', standardQuantity: 25, ordinal: 1 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '分割肉', unit: 'kg', quantityMode: 'AUTO_CONVERT', ordinal: 0 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));

    expect(wrapper.text()).toContain('投入1');
    expect(wrapper.text()).toContain('投入2');
    expect(wrapper.text()).not.toContain('主投入');
    expect(wrapper.text()).not.toContain('追加投入');
    expect(wrapper.text()).not.toContain('端口');
    const quantityInputs = wrapper.findAllComponents({ name: 'ElInputNumber' });
    expect(quantityInputs).toHaveLength(2);
    expect(wrapper.findAll('[data-testid="fixed-input-quantity"]')).toHaveLength(2);
    expect(wrapper.findAll('[data-testid="fixed-input-quantity"]')[0].text()).toContain('kg投入=1kg产出');
    expect(wrapper.findAll('[data-testid="fixed-input-quantity"]')[1].text()).toContain('g投入');
    expect(wrapper.text()).toContain('调味料');
  });

  it('switches only an actually long material row to the stacked responsive layout', () => {
    const ports: ProcessPort[] = [
      { id: 'short', direction: 'INPUT', materialName: '原料A', unit: 'kg', standardQuantity: 1, ordinal: 0 },
      {
        id: 'long', direction: 'INPUT', materialName: 'E2E-替代链-处理后超长半成品原料名称',
        unit: 'kg', standardQuantity: 1, ordinal: 1,
      },
      { id: 'out', direction: 'OUTPUT', materialName: '袋装产出', unit: '袋', standardQuantity: 1, ordinal: 0 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));
    const rows = wrapper.findAll('.port-quantity-row');

    expect(rows[0].classes()).not.toContain('is-long-name');
    expect(rows[1].classes()).toContain('is-long-name');
    expect(rows[1].text()).toContain('E2E-替代链-处理后超长半成品原料名称');
  });

  it('shows the finished SKU specification beside the output baseline and keeps the input ratio editable', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', materialName: '包装前产品', unit: 'kg', ordinal: 0 },
      {
        id: 'out-1', direction: 'OUTPUT', skuId: 'SKU-FIN-800', materialName: '成品800g',
        unit: '盒', quantityMode: 'FIXED_RATIO', standardQuantity: 9, ordinal: 0,
      },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));

    expect(wrapper.get('[data-testid="quantity-baseline"]').text()).toContain('1 盒 · 成品800g');
    expect(wrapper.get('[data-testid="quantity-baseline"]').text()).toContain('SKU 规格：1盒 = 800g');
    expect(wrapper.get('[data-testid="fixed-input-quantity"]').text()).toContain('kg投入=1盒产出');
    expect(wrapper.findComponent({ name: 'ElInputNumber' }).exists()).toBe(true);
  });

  it('renders and updates the matching per-output input quantity only', async () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', materialName: '整猪', unit: '只', quantityMode: 'FIXED_RATIO', standardQuantity: 1, ordinal: 0 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '半只分割', unit: '件', quantityMode: 'FIXED_RATIO', standardQuantity: 2, ordinal: 0 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));

    wrapper.findComponent({ name: 'ElInputNumber' }).vm.$emit('change', 3);
    await wrapper.vm.$nextTick();

    const patch = wrapper.emitted('update')?.[0]?.[0] as Partial<ProcessNodeData>;
    expect(patch.ports?.find((port) => port.id === 'in-1')).toMatchObject({
      quantityMode: 'FIXED_RATIO', standardQuantity: 3,
    });
    expect(patch.ports?.find((port) => port.id === 'out-1')?.standardQuantity).toBe(2);
  });

  it('#4: gracefully leaves the ratio unset (returns null, does not crash) for legacy free-text expressions that are not the canonical shape', () => {
    const wrapper = mountNode(true, withPorts({
      conversionRule: { mode: 'FIXED_RATIO', expression: '1 只 = 1 只 / 100:90 保水后' },
    }));

    // 旧自由文本里第一个 "1<unit>=<n><unit>" 片段仍然可能被解析出一个数字
    // (best-effort 前向兼容), 但绝不能抛错、也不会静默改写原始存量 expression
    // (只有用户真的动了数字输入框才会写回新的规范格式)。
    expect(wrapper.find('[data-testid="fixed-ratio-row"]').exists()).toBe(false);
    expect(wrapper.emitted('update')).toBeUndefined();
  });

  it('does not expose legacy FORMULA configuration', () => {
    const wrapper = mountNode(true, withPorts({
      conversionRule: { mode: 'FORMULA', expression: '(产出1 + 产出2) * 0.95' },
    }));

    expect(wrapper.find('[data-testid="legacy-mode-hint"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="fixed-ratio-row"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="conversion-expression-input"]').exists()).toBe(false);
  });

  it('does not expose legacy SUM_OUTPUTS configuration', () => {
    const wrapper = mountNode(true, withPorts({
      conversionRule: { mode: 'SUM_OUTPUTS', expression: null },
    }));

    expect(wrapper.find('[data-testid="legacy-mode-hint"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="conversion-expression-input"]').exists()).toBe(false);
  });

  it('no longer renders a separate 样例 example line (trimmed per config-driven UX simplification)', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', unit: 'kg', ordinal: 0 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '瘦肉出品', unit: 'kg', ordinal: 0 },
      { id: 'out-2', direction: 'OUTPUT', materialName: '肥肉出品', unit: 'kg', ordinal: 1 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));

    expect(wrapper.find('[data-testid="conversion-sample"]').exists()).toBe(false);
    expect(wrapper.find('.conversion-example').exists()).toBe(false);
  });

  it('P3 (was "P1 guarantees"): output SKU picker now lives on the process Cell too; units stay read-only chips', () => {
    const wrapper = mountNode();

    // 反转过去 P1 的保证: SKU 选择器现在可以出现在工序 Cell 上 (产出行), 不再是
    // "只能在右侧物料 Cell 上选" —— 这是本轮改造的核心需求 #1。
    expect(wrapper.findComponent(WorkflowSkuPicker).exists()).toBe(true);
    // 单位仍然是自动跟随所选 SKU 的只读 chip, 不是手动可编辑的下拉/输入框。
    expect(wrapper.find('.unit-select').exists()).toBe(false);
    expect(wrapper.get('[data-testid="input-unit-chip"]').text()).toBe('kg');
    expect(wrapper.get('[data-testid="output-unit-chip"]').text()).toBe('kg');
  });

  it('fixes multi-inputs to per-batch free choice while retaining the output relationship selector', async () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', unit: 'kg', ordinal: 0 },
      { id: 'in-2', direction: 'INPUT', unit: 'kg', ordinal: 1 },
      { id: 'out-1', direction: 'OUTPUT', unit: 'kg', ordinal: 0 },
      { id: 'out-2', direction: 'OUTPUT', unit: 'kg', ordinal: 1 },
    ];
    const wrapper = mountNode(true, withPorts({ ports, portGroups: [] }));

    expect(wrapper.get('[data-testid="input-free-choice-mode"]').text()).toBe('批次自由选择（至少1个）');
    expect(wrapper.find('[data-testid="input-relation-select"]').exists()).toBe(false);
    expect(wrapper.get('[data-testid="output-port-relation"]').text()).toContain('全部产出');
    expect(wrapper.get('[data-testid="input-relation-help"]').text()).toBe('每个批次至少选择1种，可选择1种、多种或全部。');
    expect(wrapper.get('[data-testid="output-relation-help"]').text()).toBe('所有产出都会生成。');
    wrapper.get('[data-testid="output-port-relation"]')
      .findComponent({ name: 'ElSelect' }).vm.$emit('change', 'OPTIONAL');
    await wrapper.vm.$nextTick();

    const patch = wrapper.emitted('update')?.[0]?.[0] as Partial<ProcessNodeData>;
    expect(patch.portGroups).toEqual([{
      id: 'port-group:output:all', direction: 'OUTPUT', label: '产出关系', mode: 'OPTIONAL',
      minSelections: 0, maxSelections: 2, portIds: ['out-1', 'out-2'],
    }]);
  });
});
