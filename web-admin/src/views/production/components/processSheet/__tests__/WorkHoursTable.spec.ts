// Bug1 regression coverage: 工时录入必须能用直接数字(分钟)可靠提交, 不依赖脆弱的
// 滚轮 el-time-picker (点选易脱靶 / 打字不按 Enter 会静默丢失, 见 fool-proof-design.md
// Rule 1/3, 张权低文化仓管员 headed 测试实拍抓到)。
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { nextTick } from 'vue';
import ElementPlus from 'element-plus';
import WorkHoursTable from '../WorkHoursTable.vue';
import type { LaborSegment } from '@/api/processSheet';

function mountTable(modelValue: LaborSegment[]) {
  return mount(WorkHoursTable, {
    props: { modelValue },
    global: {
      plugins: [ElementPlus],
      stubs: { teleport: true, transition: false },
    },
  });
}

describe('WorkHoursTable (labor entry, low-literacy fool-proof)', () => {
  beforeEach(() => {
    vi.useRealTimers();
  });

  it('renders 时长(分钟) as a direct-number input derived from startTime/endTime, plus a read-only 结束时间', async () => {
    const wrapper = mountTable([{ startTime: '08:00', endTime: '09:00', workerCount: 1 }]);
    await flushPromises();
    await nextTick();

    // 时长(分钟) 主输入必须显示 60 (08:00→09:00), 不是 0 或空
    const durationInput = wrapper.find('.el-input-number input');
    expect(durationInput.exists()).toBe(true);
    expect((durationInput.element as HTMLInputElement).value).toBe('60');

    // 结束时间只读展示, 与 startTime+duration 一致
    expect(wrapper.text()).toContain('09:00');
    // 工时(h) = 1h × 1人 = 1.00
    expect(wrapper.text()).toContain('1.00');
    // 合计工时 footer
    expect(wrapper.text()).toMatch(/合计工时[:：]?\s*1\.00\s*h/);

    // 开始时间不再是滚轮 el-time-picker, 而是简单下拉列表 el-time-select
    expect(wrapper.findComponent({ name: 'ElTimeSelect' }).exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'ElTimePicker' }).exists()).toBe(false);
  });

  it('直接点「时长(分钟)」+ 按钮可靠提交新工时, 不需要滚轮/打字回车 (Bug1 核心修复)', async () => {
    const wrapper = mountTable([{ startTime: '08:00', endTime: '09:00', workerCount: 1 }]);
    await flushPromises();
    await nextTick();

    // el-input-number 的 +/- 按钮走 v-repeat-click 自定义指令 (监听 mousedown, 非 click)。
    // 真实用法是 v-model (ProcessDataTable.vue: <WorkHoursTable v-model="row.laborSegments" />),
    // 每次 emit 后父层会把新值传回 props — 这里手动 setProps 模拟真实 v-model 回环,
    // 否则 el-input-number 内部值会被"未更新的 props"每次冲回原值 (纯 test-harness 假象, 非真 bug)。
    for (let i = 0; i < 5; i++) {
      const increaseBtn = wrapper.findAll('.el-input-number__increase')[0];
      expect(increaseBtn.exists()).toBe(true);
      await increaseBtn.trigger('mousedown', { button: 0 });
      await flushPromises();
      const latest = wrapper.emitted('update:modelValue')!.slice(-1)[0][0] as LaborSegment[];
      await wrapper.setProps({ modelValue: latest });
      await flushPromises();
    }

    const emitted = wrapper.emitted('update:modelValue');
    expect(emitted).toBeTruthy();
    const lastEmit = emitted![emitted!.length - 1][0] as LaborSegment[];
    expect(lastEmit).toHaveLength(1);
    expect(lastEmit[0].startTime).toBe('08:00'); // 开始时间不受影响
    expect(lastEmit[0].endTime).toBe('09:25');   // 08:00 + 85min 直接推算, 无需用户碰结束时间滚轮
  });

  it('新增工时段自动衔接上一段结束时间 (减少操作员需要手动设置开始时间的次数)', async () => {
    const wrapper = mountTable([{ startTime: '08:00', endTime: '09:00', workerCount: 1 }]);
    await flushPromises();
    await nextTick();

    const addRowBtn = wrapper.findAll('button').filter((b) => b.text().includes('工时段'))[0];
    expect(addRowBtn.exists()).toBe(true);
    await addRowBtn.trigger('click'); // "+ 工时段" (不是行内删除按钮)
    await flushPromises();

    const emitted = wrapper.emitted('update:modelValue');
    expect(emitted).toBeTruthy();
    const segs = emitted![emitted!.length - 1][0] as LaborSegment[];
    expect(segs).toHaveLength(2);
    expect(segs[1].startTime).toBe('09:00'); // 衔接上一段 endTime, 默认时长 60min
    expect(segs[1].endTime).toBe('10:00');
    expect(segs[1].workerCount).toBe(1);
  });
});
