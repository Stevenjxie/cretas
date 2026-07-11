import ElementPlus from 'element-plus';
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { defineComponent, h } from 'vue';
import WorkflowProcessNode from '../WorkflowProcessNode.vue';
import type { ProcessNodeData } from '../types';

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

function mountNode(selected = true) {
  return mount(WorkflowProcessNode, {
    props: {
      data: processData,
      selected,
      canWrite: true,
    },
    global: {
      plugins: [ElementPlus],
      stubs: { Handle: PositionedHandleStub },
    },
  });
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
