import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, nextTick, ref } from 'vue';
import { ElInputNumber } from 'element-plus';
import ariaDisabledSync, { ariaDisabledSyncMixin, syncAriaDisabled } from '../ariaDisabledSync';

/** 与逐道报工里那处同形: 勾「选用」才允许填投料数量 */
const Host = defineComponent({
  components: { ElInputNumber },
  setup() {
    const selected = ref(false);
    const qty = ref(0);
    return { selected, qty };
  },
  template: `<el-input-number v-model="qty" :disabled="!selected" :min="0" size="small" />`,
});

function mountHost(withFix: boolean) {
  return mount(Host, withFix ? { global: { mixins: [ariaDisabledSyncMixin] } } : undefined);
}

describe('el-input-number aria-disabled 同步', () => {
  // 阳性对照: 证明这个 plugin 修的是一个真实存在的上游缺陷, 而不是防御一个不存在的问题。
  // 若哪天 Element Plus 自己修了, 这条会变红 —— 那时该删掉 plugin, 而不是删这条测试。
  it('阳性对照: 不装本 plugin 时, 上游缺陷确实存在 (aria-disabled 陈旧)', async () => {
    const wrapper = mountHost(false);
    const input = wrapper.find('input').element;

    expect(input.getAttribute('aria-disabled')).toBe('true');

    wrapper.vm.selected = true;
    await nextTick();
    await nextTick();

    // 原生 disabled 是响应式的, 正确翻了
    expect(input.disabled).toBe(false);
    // 而 aria-disabled 因为只在 onMounted 写过一次, 停在 true —— 这就是缺陷
    expect(input.getAttribute('aria-disabled')).toBe('true');
  });

  it('装上 plugin 后: disabled true→false, aria-disabled 跟着变 false', async () => {
    const wrapper = mountHost(true);
    const input = wrapper.find('input').element;

    expect(input.getAttribute('aria-disabled')).toBe('true');

    wrapper.vm.selected = true;
    await nextTick();
    await nextTick();

    expect(input.disabled).toBe(false);
    expect(input.getAttribute('aria-disabled')).toBe('false');
  });

  it('反方向也要对: disabled false→true, aria-disabled 变回 true', async () => {
    const wrapper = mountHost(true);
    const input = wrapper.find('input').element;

    wrapper.vm.selected = true;
    await nextTick();
    await nextTick();
    expect(input.getAttribute('aria-disabled')).toBe('false');

    wrapper.vm.selected = false;
    await nextTick();
    await nextTick();

    expect(input.disabled).toBe(true);
    expect(input.getAttribute('aria-disabled')).toBe('true');
  });

  it('install() 把 mixin 装进 app (整装路径, 不只是 mixin 对象)', async () => {
    const wrapper = mount(Host, {
      global: { plugins: [ariaDisabledSync] },
    });
    const input = wrapper.find('input').element;

    wrapper.vm.selected = true;
    await nextTick();
    await nextTick();

    expect(input.getAttribute('aria-disabled')).toBe('false');
  });

  describe('syncAriaDisabled 边界', () => {
    it('$el 不是元素节点 (v-if 时的注释节点) 不抛错', () => {
      expect(() => syncAriaDisabled(document.createComment('v-if'))).not.toThrow();
      expect(() => syncAriaDisabled(null)).not.toThrow();
      expect(() => syncAriaDisabled(undefined)).not.toThrow();
    });

    it('容器内没有 input 时不抛错', () => {
      expect(() => syncAriaDisabled(document.createElement('div'))).not.toThrow();
    });
  });
});
