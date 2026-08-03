// web-admin/src/components/common/__tests__/ConceptDisambiguationAlert.spec.ts
import { describe, it, expect, vi } from 'vitest';
import { mount } from '@vue/test-utils';

let mockFactoryId = 'F006';
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({
    get factoryId() {
      return mockFactoryId;
    },
  }),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

import ConceptDisambiguationAlert from '../ConceptDisambiguationAlert.vue';

const props = {
  here: '我们向供应商下的订单（进货方向、应付账款）',
  hereName: '采购订单',
  other: '客户向我们下的订单（出货方向、应收账款）',
  otherName: '销售管理 → 销售订单',
  otherPath: '/sales/orders',
};

const global = {
  stubs: {
    'el-alert': { template: '<div class="alert"><slot name="title"/><slot/></div>' },
    'el-link': { template: '<a><slot/></a>' },
  },
};

describe('ConceptDisambiguationAlert', () => {
  it('默认租户仍然显示概念辨析引导', () => {
    mockFactoryId = 'F006';
    const wrapper = mount(ConceptDisambiguationAlert, { props, global });
    expect(wrapper.find('.alert').exists()).toBe(true);
    expect(wrapper.text()).toContain('这里是「采购订单」管理');
  });

  it('LIUSHANMEN 已要求关闭，整条横幅不渲染', () => {
    mockFactoryId = 'LIUSHANMEN';
    const wrapper = mount(ConceptDisambiguationAlert, { props, global });
    expect(wrapper.find('.alert').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('这里是');
  });

  it('拿不到 factoryId 时按显示处理，不误伤其他租户', () => {
    mockFactoryId = '';
    const wrapper = mount(ConceptDisambiguationAlert, { props, global });
    expect(wrapper.find('.alert').exists()).toBe(true);
  });
});
