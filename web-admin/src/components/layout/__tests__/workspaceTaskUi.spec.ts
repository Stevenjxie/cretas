import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { menuConfig } from '../menuConfig';
import { countActionableSalesOrders } from '@/store/modules/taskBadges';
import {
  useWorkspaceStore,
  workspaceRouteKey,
  workspaceRouteTitle,
} from '@/store/modules/workspace';

function flatten(items: typeof menuConfig): typeof menuConfig {
  return items.flatMap((item) => [item, ...(item.children ? flatten(item.children) : [])]);
}

describe('multi-task workspace and menu badges', () => {
  beforeEach(() => {
    sessionStorage.clear();
    setActivePinia(createPinia());
  });

  it('keeps query filters in one tab while explicit task ids create separate tabs', () => {
    expect(workspaceRouteKey({ path: '/sales/orders', query: { status: 'DRAFT' } }))
      .toBe('/sales/orders');
    expect(workspaceRouteKey({ path: '/sales/orders', query: { _task: 'two' } }))
      .toBe('/sales/orders?_task=two');
  });

  it('opens, persists, duplicates and pins real route tabs', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    store.openRoute({
      path: '/sales/orders', fullPath: '/sales/orders?create=1', title: '销售订单',
      query: { create: '1' },
    });
    expect(store.tabs).toHaveLength(1);
    expect(store.tabs[0].dirty).toBe(true);
    const duplicate = store.duplicateRoute();
    expect(duplicate?.path).toBe('/sales/orders');
    expect(duplicate?.query._task).toBeTruthy();
    store.pinReference(store.tabs[0].key);
    expect(store.referenceTab?.title).toBe('销售订单');
  });

  it('prefers an explicit task label over the generic route title', () => {
    expect(workspaceRouteTitle({
      path: '/sales/orders', fullPath: '/sales/orders', title: '销售订单',
      query: { _taskLabel: '新建销售单 2' },
    })).toBe('新建销售单 2');
  });

  it('wires red badges only to the three requested actionable menus', () => {
    const items = flatten(menuConfig);
    expect(items.find((item) => item.path === '/sales/orders')?.badgeKey).toBe('salesOrders');
    expect(items.find((item) => item.path === '/warehouse/materials')?.badgeKey).toBe('warehouseReceiving');
    expect(items.find((item) => item.path === '/warehouse/unordered-inbound-applications')?.badgeKey)
      .toBe('unorderedInbound');
  });

  it('excludes terminal sales orders from the actionable red count', () => {
    expect(countActionableSalesOrders([
      { status: 'DRAFT' }, { status: 'PROCESSING' }, { status: 'COMPLETED' }, { status: 'CLOSED' },
    ])).toBe(2);
  });
});

