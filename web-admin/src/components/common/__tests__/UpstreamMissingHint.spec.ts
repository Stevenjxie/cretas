// web-admin/src/components/common/__tests__/UpstreamMissingHint.spec.ts
import { describe, it, expect, vi } from 'vitest';
import { mount } from '@vue/test-utils';

let mockCanReach = false;
vi.mock('@/composables/useCreateAndReturn', () => ({
  useCreateAndReturn: () => ({ canReach: () => mockCanReach, goCreate: vi.fn() }),
}));

import UpstreamMissingHint from '../UpstreamMissingHint.vue';

const baseProps = {
  description: '该订单暂无产品行',
  targetModule: 'sales',
  actionText: '去添加产品行',
  contactText: '请联系销售补充',
};

describe('UpstreamMissingHint', () => {
  it('有权限 → 渲染按钮且点击 emit action', async () => {
    mockCanReach = true;
    const wrapper = mount(UpstreamMissingHint, {
      props: { ...baseProps, requireWrite: true },
      global: { stubs: { 'el-button': { template: '<button @click="$emit(\'click\')"><slot/></button>' } } },
    });
    expect(wrapper.text()).toContain('去添加产品行');
    expect(wrapper.text()).not.toContain('请联系销售补充');
    await wrapper.find('button').trigger('click');
    expect(wrapper.emitted('action')).toBeTruthy();
  });

  it('无权限 → 渲染联系文案，无按钮', () => {
    mockCanReach = false;
    const wrapper = mount(UpstreamMissingHint, {
      props: baseProps,
      global: { stubs: { 'el-button': { template: '<button><slot/></button>' } } },
    });
    expect(wrapper.text()).toContain('请联系销售补充');
    expect(wrapper.find('button').exists()).toBe(false);
  });
});
