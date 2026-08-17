/**
 * 报工审批页的 30s 轮询, 必须跟着 keep-alive 的 activate/deactivate 走。
 *
 * 背景(2026-08-17 实测): 这一页挂在 AppLayout 的 <keep-alive :max="10"> 下。
 * keep-alive 的语义是「离开时不卸载, 只 deactivate」—— 所以 onUnmounted 在
 * 用户离开本页时【根本不会触发】。原来的实现把 clearInterval 放在 onUnmounted 里,
 * 于是那个 30 秒定时器在用户走了之后【一直在后台跑】。
 *
 * 症状读起来像「一次加载重复请求 pending-approval 五次」, 实际是
 * 「一个永远不停的后台轮询」—— 在别的页面待 2.5 分钟就攒够 5 次。
 * 两者修法完全不同: 前者要去查重复挂载, 后者要修生命周期钩子。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { defineComponent, h, KeepAlive, ref, nextTick } from 'vue';
import { mount } from '@vue/test-utils';

const getPendingApprovalsMock = vi.fn();

vi.mock('@/api/processProduction', () => ({
  getPendingApprovals: (...args: unknown[]) => getPendingApprovalsMock(...args),
  approveReport: vi.fn(),
  rejectReport: vi.fn(),
  batchApproveReports: vi.fn(),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));
vi.mock('@/utils/errorToast', () => ({ handleCatchError: vi.fn() }));
vi.mock('@/utils/processSheetUnits', () => ({ displayProcessUnit: (v: unknown) => String(v ?? '') }));
vi.mock('@/components/approval/OpinionInputDialog.vue', () => ({
  default: defineComponent({ name: 'OpinionInputDialog', render: () => h('div') }),
}));

import ApprovalList from '../list.vue';

/** 只数「打给 pending-approval 的次数」 */
const calls = () => getPendingApprovalsMock.mock.calls.length;

describe('报工审批页 — 轮询跟随 keep-alive 生命周期', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    getPendingApprovalsMock.mockReset().mockResolvedValue({
      success: true,
      data: { content: [] },
    });
    // jsdom 默认 visibilityState 就是 'visible', 这里显式钉住, 免得依赖默认值
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** 把组件挂在 keep-alive 里, 用一个开关模拟「切走 / 切回」 */
  function mountInKeepAlive() {
    const active = ref(true);
    const wrapper = mount(defineComponent({
      setup() {
        return () => h(KeepAlive, null, {
          default: () => (active.value ? h(ApprovalList) : h('div', { key: 'other' })),
        });
      },
    }), { global: { stubs: { 'el-card': false } } });
    return { wrapper, active };
  }

  it('切走(deactivate)之后不再轮询 —— 这是 5 次重复请求的成因', async () => {
    const { active } = mountInKeepAlive();
    await nextTick();
    const afterMount = calls();
    expect(afterMount).toBeGreaterThan(0); // 阳性对照: 挂载时确实拉了一次

    // 切到别的页面 —— keep-alive 只 deactivate, 不 unmount
    active.value = false;
    await nextTick();

    const atLeave = calls();
    // 在别处待 2.5 分钟 (5 个 30s 周期)
    await vi.advanceTimersByTimeAsync(30_000 * 5);

    expect(calls()).toBe(atLeave); // ⛔ 后台一次都不许再打
  });

  it('切回(activate)之后恢复: 立刻刷新一次, 并重新开始轮询', async () => {
    const { active } = mountInKeepAlive();
    await nextTick();

    active.value = false;
    await nextTick();
    const atLeave = calls();

    active.value = true;
    await nextTick();
    expect(calls()).toBe(atLeave + 1); // 回来先刷新一次

    const atReturn = calls();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(calls()).toBe(atReturn + 1); // 轮询恢复
  });

  it('重复 activate 不会叠加出两个定时器', async () => {
    const { active } = mountInKeepAlive();
    await nextTick();

    for (let i = 0; i < 3; i += 1) {
      active.value = false;
      await nextTick();
      active.value = true;
      await nextTick();
    }

    const base = calls();
    await vi.advanceTimersByTimeAsync(30_000);
    // 一个周期只许多【一次】。定时器叠加的话这里会是 +3。
    expect(calls()).toBe(base + 1);
  });

  it('标签页不可见时不轮询', async () => {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'hidden',
    });

    mountInKeepAlive();
    await nextTick();
    const base = calls();

    await vi.advanceTimersByTimeAsync(30_000 * 3);
    expect(calls()).toBe(base);
  });
});
