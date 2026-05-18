/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 (Phase F) — PlaceholderTab spec.
 * 防呆 R5 verification: action button shown only when actionText present + click routes properly.
 */
import { describe, it, expect, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import PlaceholderTab from '../PlaceholderTab.vue';

const routerReplace = vi.fn();

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: routerReplace, push: vi.fn() }),
  useRoute: () => ({
    params: { id: 'cust-1' },
    query: { tab: 'wechat' },
  }),
}));

const globalStubs = {
  'el-empty': {
    props: ['imageSize'],
    template: '<div class="el-empty"><slot name="description" /><slot /></div>',
  },
  'el-button': {
    props: ['type'],
    template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>',
    emits: ['click'],
  },
};

describe('PlaceholderTab', () => {
  it('renders tab name + status text', () => {
    const w = mount(PlaceholderTab, {
      props: { tabName: '微信记录', status: 'Sprint 5+ 上线' },
      global: { stubs: globalStubs },
    });
    expect(w.text()).toContain('微信记录');
    expect(w.text()).toContain('Sprint 5+');
  });

  it('renders workaround hint when provided', () => {
    const w = mount(PlaceholderTab, {
      props: {
        tabName: '微信',
        status: 'TBD',
        workaroundHint: '当前请用跟踪记录 tab',
      },
      global: { stubs: globalStubs },
    });
    expect(w.text()).toContain('当前请用跟踪记录');
  });

  it('NO action button when actionText missing', () => {
    const w = mount(PlaceholderTab, {
      props: { tabName: '微信', status: 'TBD' },
      global: { stubs: globalStubs },
    });
    expect(w.find('button.el-button').exists()).toBe(false);
  });

  it('shows action button + routes to actionTabKey on click (R5)', async () => {
    routerReplace.mockClear();
    const w = mount(PlaceholderTab, {
      props: {
        tabName: '微信',
        status: 'TBD',
        actionText: '去跟踪记录',
        actionTabKey: 'tracking',
      },
      global: { stubs: globalStubs },
    });
    const btn = w.find('button.el-button');
    expect(btn.exists()).toBe(true);
    expect(btn.text()).toContain('去跟踪记录');
    await btn.trigger('click');
    expect(routerReplace).toHaveBeenCalledWith({
      name: 'SalesCustomerDetail',
      params: { id: 'cust-1' },
      query: { tab: 'tracking' },
    });
  });
});
