import { flushPromises, shallowMount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { describe, expect, it, vi } from 'vitest';

const routerHarness = vi.hoisted(() => ({ route: null as any }));
const canvasHarness = vi.hoisted(() => ({ state: null as any }));

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue');
  routerHarness.route = reactive({
    query: { tab: 'approval', decisionType: 'SALES_ORDER_APPROVAL' } as Record<string, string | undefined>,
  });
  return { useRoute: () => routerHarness.route };
});

vi.mock('../composables/useCanvasEditor', async () => {
  const { ref } = await import('vue');
  canvasHarness.state = {
    factoryId: ref('F006'),
    selectedModule: ref(''),
    activeTab: ref('workflow'),
    dirtyCount: ref(0),
    leftCollapsed: ref(false),
    rightCollapsed: ref(false),
    isOnboarding: ref(false),
    inFlightAction: ref(null),
    toggleLeft: vi.fn(),
    toggleRight: vi.fn(),
    enterFocusMode: vi.fn(),
    exitFocusMode: vi.fn(),
    loadVersion: vi.fn().mockResolvedValue(undefined),
    applyResponsive: vi.fn(),
    clearDirty: vi.fn(),
  };
  return { useCanvasEditor: () => canvasHarness.state };
});

import CanvasEditor from '../index.vue';

describe('Canvas editor route query synchronization', () => {
  it('reacts to approval deep links and browser-style query changes without remounting', async () => {
    const wrapper = shallowMount(CanvasEditor);
    await flushPromises();

    expect(canvasHarness.state.activeTab.value).toBe('approval');
    expect(canvasHarness.state.isOnboarding.value).toBe(false);

    routerHarness.route.query.tab = 'workflow';
    delete routerHarness.route.query.decisionType;
    await nextTick();
    expect(canvasHarness.state.activeTab.value).toBe('workflow');

    routerHarness.route.query.tab = 'approval';
    routerHarness.route.query.decisionType = 'SALES_ORDER_APPROVAL';
    await nextTick();
    expect(canvasHarness.state.activeTab.value).toBe('approval');

    wrapper.unmount();
  });
});
