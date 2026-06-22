import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';

// vue-router mock: useRoute returns query (already router-decoded, like the real router does);
// useRouter captures push.
const mockPush = vi.fn();
let mockQuery: Record<string, unknown> = {};
vi.mock('vue-router', () => ({
  useRoute: () => ({ get query() { return mockQuery; } }),
  useRouter: () => ({ push: mockPush, replace: vi.fn(), getRoutes: () => [] }),
}));

import ReturnBanner from '../ReturnBanner.vue';

const stubs = {
  'el-icon': { template: '<i><slot/></i>' },
  'el-button': { template: '<button @click="$emit(\'click\')"><slot/></button>' },
  ArrowLeft: { template: '<span/>' },
  Close: { template: '<span/>' },
};

describe('ReturnBanner — no double-decode of nested _returnTo', () => {
  beforeEach(() => { mockPush.mockReset(); mockQuery = {}; });

  it('单级返回: 原样 push router 已解码的 _returnTo', async () => {
    // vue-router 已把 query value 解码 → 组件拿到的就是完整可用路径
    mockQuery = { _returnTo: '/production/plans?reopenPlan=1&planSO=ID' };
    const wrapper = mount(ReturnBanner, { global: { stubs } });
    await wrapper.find('button').trigger('click');
    expect(mockPush).toHaveBeenCalledWith('/production/plans?reopenPlan=1&planSO=ID');
  });

  it('多级嵌套: 内层 _returnTo 的编码必须原样保留 (不得再 decodeURIComponent)', async () => {
    // 产品页返回订单页这一跳: router 解码外层一次后, 内层 _returnTo 仍为单次编码态。
    // 组件若再 decodeURIComponent, 内层 ?/& 会被解成字面量 → 下一跳丢 planSO。
    const routerDecodedNested =
      '/sales/orders/ID?editItems=1&_returnTo=%2Fproduction%2Fplans%3FreopenPlan%3D1%26planSO%3DID';
    mockQuery = { _returnTo: routerDecodedNested };
    const wrapper = mount(ReturnBanner, { global: { stubs } });
    await wrapper.find('button').trigger('click');
    // 必须原样 push (内层保持 %2F.. 编码), 让下一次 router.push 再解码恰好一次
    expect(mockPush).toHaveBeenCalledWith(routerDecodedNested);
  });

  it('无 _returnTo 时不渲染 banner', () => {
    mockQuery = {};
    const wrapper = mount(ReturnBanner, { global: { stubs } });
    expect(wrapper.find('.return-banner').exists()).toBe(false);
  });
});
