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
      skuOptions: [],
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
  });

  it('does not expose a manual output material kind selector', () => {
    const wrapper = mountNode();

    expect(wrapper.find('[data-testid="output-kind-select"]').exists()).toBe(false);
    expect(wrapper.find('.kind-select').exists()).toBe(false);
  });

  it('does not emit addOutput when a workflow handle is clicked', async () => {
    const wrapper = mountNode();

    await wrapper.get('[data-testid="workflow-handle"]').trigger('click');

    expect(wrapper.emitted('addOutput')).toBeUndefined();
  });

  it('keeps the edge action outside the single source handle hit area', () => {
    const wrapper = mountNode();
    const sourceHandle = wrapper.get('[data-handle-type="source"]');
    const edgeAction = wrapper.get('[data-testid="add-output-edge"]');

    expect(sourceHandle.attributes('data-position')).toBe('right');
    expect((sourceHandle.element as HTMLElement).style.top).toBe('50%');

    const edgeRight = Number.parseFloat((edgeAction.element as HTMLElement).style.right);
    const edgeCenterFromNodeRight = -edgeRight - 14;
    const minimumCenterDistance = (28 + 10) / 2;
    expect(edgeCenterFromNodeRight).toBeGreaterThan(minimumCenterDistance);
  });
});
