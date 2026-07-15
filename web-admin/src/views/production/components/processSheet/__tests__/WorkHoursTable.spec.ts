import { describe, it, expect } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import WorkHoursTable from '../WorkHoursTable.vue';
import type { LaborSegment } from '@/api/processSheet';

function mountTable(modelValue: LaborSegment[]) {
  return mount(WorkHoursTable, {
    props: { modelValue },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

describe('WorkHoursTable', () => {
  it('shows start, end and worker count as the only editable values', async () => {
    const wrapper = mountTable([{ startTime: '08:00', endTime: '09:00', workerCount: 2 }]);
    await flushPromises();
    expect(wrapper.findAllComponents({ name: 'ElTimeSelect' })).toHaveLength(2);
    expect(wrapper.findAllComponents({ name: 'ElInputNumber' })).toHaveLength(1);
    expect(wrapper.text()).toContain('1.00 h');
    expect(wrapper.text()).toContain('2.00 h');
  });

  it('allows direct end-time entry and treats an earlier clock time as next day', async () => {
    const wrapper = mountTable([{ startTime: '23:00', endTime: '01:00', workerCount: 2 }]);
    await flushPromises();
    const timeInputs = wrapper.findAllComponents({ name: 'ElTimeSelect' });
    expect(timeInputs).toHaveLength(2);
    expect(wrapper.text()).toContain('次日');
    expect(wrapper.text()).toContain('2.00 h');
    expect(wrapper.text()).toContain('4.00 h');

    timeInputs[1].vm.$emit('update:model-value', '02:30');
    await flushPromises();
    const emitted = wrapper.emitted('update:modelValue');
    const segments = emitted![emitted!.length - 1][0] as LaborSegment[];
    expect(segments[0]).toEqual({ startTime: '23:00', endTime: '02:30', workerCount: 2 });
  });

  it('adds a one-hour segment after the previous end time', async () => {
    const wrapper = mountTable([{ startTime: '08:00', endTime: '09:00', workerCount: 1 }]);
    await flushPromises();
    const button = wrapper.findAll('button').find((item) => item.text().includes('工时段'))!;
    await button.trigger('click');
    const emitted = wrapper.emitted('update:modelValue');
    const segments = emitted![emitted!.length - 1][0] as LaborSegment[];
    expect(segments[1]).toEqual({ startTime: '09:00', endTime: '10:00', workerCount: 1 });
  });
});
