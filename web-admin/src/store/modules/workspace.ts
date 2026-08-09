import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

export interface WorkspaceRouteSnapshot {
  fullPath: string;
  path: string;
  name?: string | symbol | null;
  title?: string;
  query?: Record<string, unknown>;
}

export interface WorkspaceTab {
  key: string;
  fullPath: string;
  path: string;
  title: string;
  dirty: boolean;
  openedAt: number;
}

const STORAGE_PREFIX = 'cretas:web-workspace:v1:';
const MAX_TABS = 10;

function text(value: unknown): string {
  return String(value ?? '').trim();
}

export function workspaceRouteKey(route: Pick<WorkspaceRouteSnapshot, 'path' | 'query'>): string {
  const explicitTask = text(route.query?._task);
  return explicitTask ? `${route.path}?_task=${explicitTask}` : route.path;
}

export function workspaceRouteTitle(route: WorkspaceRouteSnapshot): string {
  const explicit = text(route.query?._taskLabel);
  if (explicit) return explicit;
  if (route.title) return text(route.title);
  const last = route.path.split('/').filter(Boolean).pop();
  return last || '工作台';
}

function isDirtyRoute(route: WorkspaceRouteSnapshot): boolean {
  const title = workspaceRouteTitle(route);
  return route.path.endsWith('/create')
    || ['1', 'true'].includes(text(route.query?.create).toLowerCase())
    || ['1', 'true'].includes(text(route.query?.edit).toLowerCase())
    || /新建|编辑/.test(title);
}

function safeParseTabs(raw: string | null): WorkspaceTab[] {
  if (!raw) return [];
  try {
    const value = JSON.parse(raw) as unknown;
    if (!Array.isArray(value)) return [];
    return value
      .filter((item): item is WorkspaceTab => Boolean(
        item && typeof item === 'object'
        && typeof (item as WorkspaceTab).key === 'string'
        && typeof (item as WorkspaceTab).fullPath === 'string'
        && typeof (item as WorkspaceTab).path === 'string'
        && typeof (item as WorkspaceTab).title === 'string',
      ))
      .slice(-MAX_TABS);
  } catch {
    return [];
  }
}

export const useWorkspaceStore = defineStore('workspace', () => {
  const scope = ref('anonymous');
  const tabs = ref<WorkspaceTab[]>([]);
  const activeKey = ref('');
  const draggingKey = ref('');
  const referenceKey = ref('');

  const activeTab = computed(() => tabs.value.find((tab) => tab.key === activeKey.value) || null);
  const referenceTab = computed(() => tabs.value.find((tab) => tab.key === referenceKey.value) || null);

  function storageKey(): string {
    return `${STORAGE_PREFIX}${scope.value}`;
  }

  function persist(): void {
    if (typeof sessionStorage === 'undefined') return;
    sessionStorage.setItem(storageKey(), JSON.stringify(tabs.value));
  }

  function setScope(nextScope: string): void {
    const normalized = text(nextScope) || 'anonymous';
    if (scope.value === normalized && tabs.value.length) return;
    scope.value = normalized;
    tabs.value = typeof sessionStorage === 'undefined'
      ? []
      : safeParseTabs(sessionStorage.getItem(storageKey()));
    activeKey.value = '';
    draggingKey.value = '';
    referenceKey.value = '';
  }

  function openRoute(route: WorkspaceRouteSnapshot): void {
    if (text(route.query?._workspaceReference) === '1') return;
    const key = workspaceRouteKey(route);
    const existing = tabs.value.find((tab) => tab.key === key);
    if (existing) {
      existing.fullPath = route.fullPath;
      existing.title = workspaceRouteTitle(route);
      existing.dirty ||= isDirtyRoute(route);
    } else {
      tabs.value.push({
        key,
        fullPath: route.fullPath,
        path: route.path,
        title: workspaceRouteTitle(route),
        dirty: isDirtyRoute(route),
        openedAt: Date.now(),
      });
      if (tabs.value.length > MAX_TABS) {
        const removable = tabs.value.find((tab) => tab.key !== referenceKey.value && tab.key !== key);
        if (removable) tabs.value.splice(tabs.value.indexOf(removable), 1);
      }
    }
    activeKey.value = key;
    persist();
  }

  function closeTab(key: string): WorkspaceTab | null {
    if (tabs.value.length <= 1) return null;
    const index = tabs.value.findIndex((tab) => tab.key === key);
    if (index < 0) return null;
    tabs.value.splice(index, 1);
    if (referenceKey.value === key) referenceKey.value = '';
    const fallback = tabs.value[Math.min(index, tabs.value.length - 1)] || null;
    if (activeKey.value === key) activeKey.value = fallback?.key || '';
    persist();
    return fallback;
  }

  function markDirty(key: string, dirty: boolean): void {
    const tab = tabs.value.find((item) => item.key === key);
    if (!tab) return;
    tab.dirty = dirty;
    persist();
  }

  function beginDrag(key: string): void {
    draggingKey.value = key;
  }

  function endDrag(): void {
    draggingKey.value = '';
  }

  function pinReference(key = draggingKey.value): void {
    if (!tabs.value.some((tab) => tab.key === key)) return;
    referenceKey.value = key;
    draggingKey.value = '';
  }

  function closeReference(): void {
    referenceKey.value = '';
  }

  function duplicateRoute(): { path: string; query: Record<string, string> } | null {
    const tab = activeTab.value;
    if (!tab) return null;
    const taskId = Date.now().toString(36);
    const url = new URL(tab.fullPath, 'https://workspace.local');
    const query: Record<string, string> = {};
    url.searchParams.forEach((value, key) => {
      if (!key.startsWith('_task')) query[key] = value;
    });
    query._task = taskId;
    query._taskLabel = `${tab.title} ${tabs.value.filter((item) => item.path === tab.path).length + 1}`;
    return { path: tab.path, query };
  }

  return {
    tabs,
    activeKey,
    activeTab,
    draggingKey,
    referenceKey,
    referenceTab,
    setScope,
    openRoute,
    closeTab,
    markDirty,
    beginDrag,
    endDrag,
    pinReference,
    closeReference,
    duplicateRoute,
  };
});
