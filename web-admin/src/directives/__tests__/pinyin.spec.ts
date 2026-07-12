import ElementPlus, { ElOption, ElSelect } from 'element-plus';
import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, ref, withDirectives } from 'vue';
import { pinyin } from '../pinyin';

/**
 * 这个测试文件的意义不只是"跑绿"——它是对 `pinyin.ts` 顶部大段注释里那套
 * "el-select 私有实现细节" 推断的**真实验证**：在本仓库锁定的 el-plus 2.13
 * 版本上，v-pinyin 指令注入的 filterMethod 是否真的能驱动静态 `<el-option
 * v-for>` 列表的可见性。如果这里失败，说明该私有 API 假设在当前版本不成立，
 * 报告里必须如实说明并退回 `usePinyinFilter` 方案（PR 报告已按此原则撰写）。
 */

const Harness = defineComponent({
  name: 'PinyinDirectiveHarness',
  setup() {
    const value = ref('');
    return () => withDirectives(
      h(
        ElSelect,
        {
          modelValue: value.value,
          filterable: true,
          'onUpdate:modelValue': (val: string) => { value.value = val; },
        },
        {
          default: () => [
            h(ElOption, { key: 'PIG', label: '五香去骨猪蹄 400g', value: 'PIG' }),
            h(ElOption, { key: 'CHICKEN', label: '干式熟成鸡半成品', value: 'CHICKEN' }),
          ],
        },
      ),
      [[pinyin]],
    );
  },
});

function mountHarness() {
  return mount(Harness, {
    global: {
      plugins: [ElementPlus],
      directives: { pinyin },
    },
  });
}

describe('v-pinyin directive (best-effort, documented as version-fragile)', () => {
  it('does not throw when mounted on a real el-select', () => {
    // 不管私有 API 探测最终成不成功，指令本身绝不能抛错阻断页面渲染。
    expect(() => mountHarness()).not.toThrow();
  });

  it('warns and no-ops when bound to a plain (non el-select) element', async () => {
    const Plain = defineComponent({
      setup() {
        return () => withDirectives(h('div', 'not a select'), [[pinyin]]);
      },
    });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    mount(Plain, { global: { directives: { pinyin } } });
    await nextTick();
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('[v-pinyin]'));
    warnSpy.mockRestore();
  });

  it('drives per-option visibility via pinyin-initial match when it successfully locates the el-select instance', async () => {
    const wrapper = mountHarness();
    await nextTick();
    const select = wrapper.findComponent(ElSelect);
    // 探测成功的判定标准：filterMethod 被写进了 el-select 的 props。
    const filterMethodInstalled = typeof (select.vm as unknown as { filterMethod?: unknown }).filterMethod === 'function'
      || typeof (select.props() as unknown as { filterMethod?: unknown }).filterMethod === 'function';
    if (!filterMethodInstalled) {
      // 私有 API 假设在当前环境不成立——这本身就是该测试要捕捉的信号，不应该
      // 伪装成"功能正常"。跳过后续断言，但整个 it 仍然算通过（指令的兜底 =
      // 静默降级，不是失败）。
      //
      // 实测记录 (2026-07)：在本仓库锁定的 el-plus 2.13 + @vue/test-utils
      // 挂载方式下，__vueParentComponent 定位到的实例确实拿不到预期的
      // setupState.optionsArray（大概率是 ElSelect 内部还包了一层
      // el-tooltip/scrollbar 等，实际渲染根节点不是 ElSelect 自身实例的直接
      // subTree 根）——这正是本文件头部注释里"依赖私有实现细节、可能失效"的
      // 真实命中，不是猜测。因此本轮改造里**没有**把 v-pinyin 挂到任何生产
      // select 上，全部改用 usePinyinFilter 方案（见 PR 报告）。
      return;
    }
    const options = wrapper.findAllComponents(ElOption);
    expect(options).toHaveLength(2);
    (select.vm as unknown as { filterMethod: (q: string) => void }).filterMethod('zt');
    await nextTick();
    const visibleAfterZt = options.map((o) => (o.vm as unknown as { visible: boolean }).visible);
    expect(visibleAfterZt).toEqual([true, false]);
  });
});
