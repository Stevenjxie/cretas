/**
 * PeriodSelector「全部历史」option (fix/fu-pbi follow-up).
 *
 * 财务 PBI 看板需要一个「全部历史」时间选项: 映射为后端 period_type='year'
 * (normalizer 对 'year' 不做任何月份过滤 → 返回该数据源全部数据)。本组件原先只有
 * 本月/上月/本季/上季/本年/上年 + 自定义, 没有「全部历史」。
 *
 * 这些测试断言:
 *  1. showAllHistory=true 时渲染「全部历史」按钮; 默认 (false) 不渲染。
 *  2. 点「全部历史」→ emit { type:'year', allHistory:true } + 显示文案「全部历史」。
 *  3. 改选其他类型 (本年/单月) → allHistory 清除 (不再带 allHistory:true)。
 *  4. modelValue 带 allHistory:true 时, 初始化即进入全部历史模式。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import PeriodSelector, { type PeriodSelection } from '../PeriodSelector.vue';

const globalStubs = {
  'el-radio-group': {
    props: ['modelValue'],
    emits: ['update:modelValue', 'change'],
    template: '<div class="el-radio-group"><slot /></div>',
  },
  'el-radio-button': { props: ['value'], template: '<button class="el-radio-button"><slot /></button>' },
  'el-select': {
    props: ['modelValue'],
    emits: ['update:modelValue', 'change'],
    template: '<div class="el-select"><slot /></div>',
  },
  'el-option': { props: ['value', 'label'], template: '<div class="el-option" />' },
  'el-button': { emits: ['click'], template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-checkbox': { props: ['modelValue'], emits: ['update:modelValue'], template: '<label class="el-checkbox"><slot /></label>' },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  Calendar: { template: '<i />' },
  Clock: { template: '<i />' },
};

function mountSelector(props: Record<string, unknown> = {}) {
  return mount(PeriodSelector, { props, global: { stubs: globalStubs } });
}

/** 找文本为 label 的按钮 wrapper。 */
function btn(wrapper: ReturnType<typeof mountSelector>, label: string) {
  return wrapper.findAll('button.el-button').find((b) => b.text() === label);
}

/** 最近一次 emit 的 PeriodSelection。 */
function lastSelection(wrapper: ReturnType<typeof mountSelector>): PeriodSelection | undefined {
  const events = wrapper.emitted('change') as PeriodSelection[][] | undefined;
  if (!events?.length) return undefined;
  return events[events.length - 1][0];
}

describe('PeriodSelector 全部历史', () => {
  beforeEach(() => vi.clearAllMocks());

  it('showAllHistory=false (默认) 不渲染「全部历史」按钮', async () => {
    const wrapper = mountSelector({ showQuickSelect: true });
    await flushPromises();
    expect(btn(wrapper, '全部历史')).toBeUndefined();
  });

  it('showAllHistory=true 渲染「全部历史」按钮', async () => {
    const wrapper = mountSelector({ showQuickSelect: true, showAllHistory: true });
    await flushPromises();
    expect(btn(wrapper, '全部历史')).toBeDefined();
  });

  it('点「全部历史」→ emit type=year + allHistory=true', async () => {
    const wrapper = mountSelector({ showQuickSelect: true, showAllHistory: true, defaultType: 'year' });
    await flushPromises();
    await btn(wrapper, '全部历史')!.trigger('click');
    const sel = lastSelection(wrapper);
    expect(sel?.type).toBe('year');
    expect(sel?.allHistory).toBe(true);
  });

  it('全部历史模式下显示文案「全部历史」', async () => {
    const wrapper = mountSelector({ showQuickSelect: true, showAllHistory: true, defaultType: 'year' });
    await flushPromises();
    await btn(wrapper, '全部历史')!.trigger('click');
    expect(wrapper.text()).toContain('全部历史');
  });

  it('点「本年」后退出全部历史 (allHistory 不再为 true)', async () => {
    const wrapper = mountSelector({ showQuickSelect: true, showAllHistory: true, defaultType: 'year' });
    await flushPromises();
    await btn(wrapper, '全部历史')!.trigger('click');
    expect(lastSelection(wrapper)?.allHistory).toBe(true);
    await btn(wrapper, '本年')!.trigger('click');
    const sel = lastSelection(wrapper);
    expect(sel?.type).toBe('year');
    expect(sel?.allHistory).toBeFalsy();
  });

  it('点「本月」后退出全部历史', async () => {
    const wrapper = mountSelector({ showQuickSelect: true, showAllHistory: true, defaultType: 'year' });
    await flushPromises();
    await btn(wrapper, '全部历史')!.trigger('click');
    await btn(wrapper, '本月')!.trigger('click');
    const sel = lastSelection(wrapper);
    expect(sel?.type).toBe('month');
    expect(sel?.allHistory).toBeFalsy();
  });

  it('modelValue 带 allHistory:true → 初始化即进入全部历史', async () => {
    const wrapper = mountSelector({
      showQuickSelect: true,
      showAllHistory: true,
      modelValue: {
        type: 'year',
        year: 2026,
        value: '2026',
        compareEnabled: false,
        allHistory: true,
      } as PeriodSelection,
    });
    await flushPromises();
    const sel = lastSelection(wrapper);
    expect(sel?.allHistory).toBe(true);
    expect(wrapper.text()).toContain('全部历史');
  });
});
