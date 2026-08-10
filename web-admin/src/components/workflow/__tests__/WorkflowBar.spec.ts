import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import WorkflowBar from '../WorkflowBar.vue';

const nodes = [
  { id: 'pending', label: '待处理', status: 'PENDING' as const, count: 2 },
  { id: 'in_progress', label: '进行中', status: 'IN_PROGRESS' as const, count: 11 },
  { id: 'done', label: '已完成', status: 'DONE' as const, count: 39 },
];

describe('WorkflowBar compact status summary', () => {
  it('renders rectangular status items without circles or flow arrows', () => {
    const wrapper = mount(WorkflowBar, {
      props: { title: '生产计划状态', nodes },
      global: { stubs: { 'el-icon': { template: '<i><slot /></i>' } } },
    });

    expect(wrapper.text()).toContain('生产计划状态');
    expect(wrapper.findAll('button.status-summary-item')).toHaveLength(3);
    expect(wrapper.find('.circle').exists()).toBe(false);
    expect(wrapper.find('.connector').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('→');
    expect(wrapper.text()).toContain('待处理');
    expect(wrapper.text()).toContain('39');
  });

  it('keeps every status item keyboard-clickable through its native button', async () => {
    const wrapper = mount(WorkflowBar, {
      props: { nodes },
      global: { stubs: { 'el-icon': { template: '<i><slot /></i>' } } },
    });

    const first = wrapper.find('button.status-summary-item');
    expect(first.attributes('aria-label')).toBe('待处理, 2 项');
    await first.trigger('click');
    expect(wrapper.emitted('node-click')).toEqual([['pending']]);
  });
});
