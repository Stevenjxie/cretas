import {
  DISPATCHER_ACTIONS,
  WAREHOUSE_MGR_ACTIONS,
  WORKSHOP_SUP_ACTIONS,
  WORKSHOP_SUP_DEFAULT_HIDDEN,
  useQuickActionsStore,
  type QuickAction,
} from '../../../store/quickActionsStore';

const actions: QuickAction[] = [
  { id: 'a', label: 'A', icon: 'alpha', iconColor: '#111', iconBg: '#eee', screen: 'A' },
  { id: 'b', label: 'B', icon: 'beta', iconColor: '#222', iconBg: '#eee', screen: 'B' },
  { id: 'c', label: 'C', icon: 'gamma', iconColor: '#333', iconBg: '#eee', screen: 'C' },
];

beforeEach(() => {
  useQuickActionsStore.setState({
    hiddenActions: {},
    actionOrder: {},
  });
});

/**
 * 已注册的路由名 —— 取自**导航器**里的 `name="X"`, 不是 types/navigation.ts。
 *
 * ⚠️ 类型文件不是真相: 实测 PlanCreate / PlanList / PersonnelSchedule / SmartBI /
 * ProductionLine 这 5 个在导航器里注册了, 却根本没写进 navigation.ts。
 * 按类型文件判会得到 5 个假阳性。
 */
function registeredRouteNames(): Set<string> {
  const fs = require('fs');
  const path = require('path');
  const root = path.join(__dirname, '..', '..', '..', 'navigation');
  const names = new Set<string>();
  const walk = (dir: string) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) walk(full);
      else if (e.name.endsWith('.tsx') || e.name.endsWith('.ts')) {
        const src: string = fs.readFileSync(full, 'utf-8');
        for (const m of src.matchAll(/name=["']([A-Za-z][A-Za-z0-9_]*)["']/g)) { if (m[1]) names.add(m[1]); }
      }
    }
  };
  walk(root);
  return names;
}

describe('quickActionsStore', () => {
  it('toggles hidden actions per role without affecting other roles', () => {
    useQuickActionsStore.getState().toggleAction('warehouse', 'a');

    expect(useQuickActionsStore.getState().isActionVisible('warehouse', 'a')).toBe(false);
    expect(useQuickActionsStore.getState().isActionVisible('dispatcher', 'a')).toBe(true);

    useQuickActionsStore.getState().toggleAction('warehouse', 'a');
    expect(useQuickActionsStore.getState().isActionVisible('warehouse', 'a')).toBe(true);
  });

  it('filters hidden actions and persists first-time workshop supervisor defaults', () => {
    const visible = useQuickActionsStore.getState().getVisibleActions('workshop_supervisor', WORKSHOP_SUP_ACTIONS);
    const visibleIds = visible.map(action => action.id);

    for (const hiddenId of WORKSHOP_SUP_DEFAULT_HIDDEN) {
      expect(visibleIds).not.toContain(hiddenId);
    }
    expect(useQuickActionsStore.getState().hiddenActions.workshop_supervisor).toEqual(WORKSHOP_SUP_DEFAULT_HIDDEN);
  });

  it('orders configured actions first and keeps unconfigured actions after them', () => {
    useQuickActionsStore.getState().reorderActions('dispatcher', ['c', 'a']);

    const visible = useQuickActionsStore.getState().getVisibleActions('dispatcher', actions);

    expect(visible.map(action => action.id)).toEqual(['c', 'a', 'b']);
  });

  it('resetToDefault clears custom hidden and ordered actions for a role', () => {
    useQuickActionsStore.getState().toggleAction('warehouse', 'a');
    useQuickActionsStore.getState().reorderActions('warehouse', ['b', 'a']);

    useQuickActionsStore.getState().resetToDefault('warehouse');

    expect(useQuickActionsStore.getState().hiddenActions.warehouse).toEqual([]);
    expect(useQuickActionsStore.getState().actionOrder.warehouse).toEqual([]);
    expect(useQuickActionsStore.getState().getVisibleActions('warehouse', actions).map(action => action.id)).toEqual([
      'a',
      'b',
      'c',
    ]);
  });

  it('exports role action sets with real navigation targets', () => {
    expect(WORKSHOP_SUP_ACTIONS.every(action => action.screen.length > 0)).toBe(true);
    expect(DISPATCHER_ACTIONS.map(action => action.id)).toContain('create-plan');
    // manual-receipt / ai-receipt 已移除: 它们指向 MaterialReceipt / MaterialReceiptAI,
    // 两个屏都提交到已停用的 POST /material-batches(409「普通批次页面已关闭无来源入库与续入」),
    // 屏已删。入库改从仓储角色的收货流程进。
    expect(WAREHOUSE_MGR_ACTIONS.map(action => action.id)).toEqual([
      'inventory',
      'outbound',
    ]);

    // 这条用例叫「real navigation targets」, 那就真的验一下 ——
    // 光断言 screen 非空是恒真式(谁都不会写空字符串), 它守不住「指向一个已删的屏」。
    const registered = registeredRouteNames();
    // 仪器自检: 扫不到路由时下面的断言恒真
    expect(registered.size).toBeGreaterThan(100);
    for (const set of [WORKSHOP_SUP_ACTIONS, DISPATCHER_ACTIONS, WAREHOUSE_MGR_ACTIONS]) {
      for (const action of set) {
        expect(registered.has(action.screen)).toBe(true);
      }
    }
  });
});
