import { defineAsyncComponent } from 'vue';

const BOM_PANEL_IDLE_PRELOAD_TIMEOUT_MS = 1200;
const BOM_PANEL_LOAD_TIMEOUT_MS = 20_000;

let bomUnifiedPanelPromise: ReturnType<typeof importBomUnifiedPanel> | null = null;

function importBomUnifiedPanel() {
  return import('@/views/production/bom-unified/index.vue');
}

export function preloadBomUnifiedPanel() {
  if (!bomUnifiedPanelPromise) {
    bomUnifiedPanelPromise = importBomUnifiedPanel();
  }
  return bomUnifiedPanelPromise;
}

export function scheduleBomUnifiedPanelPreload(): () => void {
  const idleWindow = window as Window & {
    requestIdleCallback?: (callback: () => void, options?: { timeout: number }) => number;
    cancelIdleCallback?: (handle: number) => void;
  };

  if (idleWindow.requestIdleCallback) {
    const handle = idleWindow.requestIdleCallback(
      () => { void preloadBomUnifiedPanel(); },
      { timeout: BOM_PANEL_IDLE_PRELOAD_TIMEOUT_MS },
    );
    return () => idleWindow.cancelIdleCallback?.(handle);
  }

  const handle = window.setTimeout(() => {
    void preloadBomUnifiedPanel();
  }, 0);
  return () => window.clearTimeout(handle);
}

export const BomUnifiedPanel = defineAsyncComponent({
  loader: preloadBomUnifiedPanel,
  suspensible: true,
  delay: 0,
  timeout: BOM_PANEL_LOAD_TIMEOUT_MS,
});
