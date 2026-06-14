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
    expect(WAREHOUSE_MGR_ACTIONS.map(action => action.id)).toEqual([
      'manual-receipt',
      'ai-receipt',
      'inventory',
      'outbound',
    ]);
  });
});
