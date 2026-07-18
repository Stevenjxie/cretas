import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import WorkflowRoutePreview from '../components/WorkflowRoutePreview.vue';

describe('WorkflowRoutePreview', () => {
  it('renders raw, process, semi-finished and finished Cells with their links', () => {
    const wrapper = mount(WorkflowRoutePreview, {
      props: {
        nodes: [
          { id: 'raw', kind: 'RAW_MATERIAL', label: '整鸡', unit: 'kg' },
          { id: 'prep', kind: 'PROCESS', label: '原料处理', unit: 'kg' },
          { id: 'semi', kind: 'SEMI_FINISHED', label: '处理后半成品', unit: 'kg' },
          { id: 'pack', kind: 'PROCESS', label: '定量包装', unit: 'kg → 袋' },
          { id: 'finished', kind: 'FINISHED_GOOD', label: '熟成鸡 400g', unit: '袋' },
        ],
        edges: [
          { id: 'e1', source: 'raw', target: 'prep' },
          { id: 'e2', source: 'prep', target: 'semi' },
          { id: 'e3', source: 'semi', target: 'pack' },
          { id: 'e4', source: 'pack', target: 'finished' },
        ],
      },
    });

    expect(wrapper.findAll('.preview-cell')).toHaveLength(5);
    expect(wrapper.findAll('.preview-edge')).toHaveLength(4);
    expect(wrapper.text()).toContain('原料处理');
    expect(wrapper.text()).toContain('定量包装');
    expect(wrapper.text()).toContain('熟成鸡 400g');
  });

  it('fails gracefully when an old candidate has no preview payload', () => {
    const wrapper = mount(WorkflowRoutePreview);

    expect(wrapper.text()).toContain('暂无可预览的 Cell 数据');
    expect(wrapper.findAll('.preview-edge')).toHaveLength(0);
  });
});
