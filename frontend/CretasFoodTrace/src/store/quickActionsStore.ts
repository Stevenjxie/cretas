/**
 * 快捷操作配置 Store
 *
 * 支持:
 * - 每角色默认快捷操作
 * - 用户自定义显隐 (AsyncStorage 持久化)
 * - 排序 (拖拽调整顺序)
 * - 后续: Web-Admin 下发配置覆盖默认
 */
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';

export interface QuickAction {
  id: string;
  label: string;
  icon: string;
  iconColor: string;
  iconBg: string;
  screen: string;
  params?: Record<string, unknown>;
}

export const WORKSHOP_SUP_DEFAULT_HIDDEN = ['process-tasks', 'yield-report', 'checkin', 'team-report', 'equipment'];

interface QuickActionsState {
  // Per-role hidden action IDs
  hiddenActions: Record<string, string[]>; // { 'workshop_supervisor': ['drafts', ...] }
  // Per-role custom order
  actionOrder: Record<string, string[]>; // { 'workshop_supervisor': ['team-report', 'checkin', ...] }

  // Actions
  toggleAction: (role: string, actionId: string) => void;
  isActionVisible: (role: string, actionId: string) => boolean;
  reorderActions: (role: string, orderedIds: string[]) => void;
  resetToDefault: (role: string) => void;
  getVisibleActions: (role: string, allActions: QuickAction[]) => QuickAction[];
}

export const useQuickActionsStore = create<QuickActionsState>()(
  persist(
    (set, get) => ({
      hiddenActions: {},
      actionOrder: {},

      toggleAction: (role, actionId) => {
        set((state) => {
          const hidden = state.hiddenActions[role] || [];
          const isHidden = hidden.includes(actionId);
          return {
            hiddenActions: {
              ...state.hiddenActions,
              [role]: isHidden
                ? hidden.filter(id => id !== actionId)
                : [...hidden, actionId],
            },
          };
        });
      },

      isActionVisible: (role, actionId) => {
        const hidden = get().hiddenActions[role] || [];
        return !hidden.includes(actionId);
      },

      reorderActions: (role, orderedIds) => {
        set((state) => ({
          actionOrder: {
            ...state.actionOrder,
            [role]: orderedIds,
          },
        }));
      },

      resetToDefault: (role) => {
        set((state) => ({
          hiddenActions: { ...state.hiddenActions, [role]: [] },
          actionOrder: { ...state.actionOrder, [role]: [] },
        }));
      },

      getVisibleActions: (role, allActions) => {
        let hidden = get().hiddenActions[role];

        // First time: apply default hidden actions
        if (hidden === undefined) {
          const defaults: Record<string, string[]> = {
            'workshop_supervisor': WORKSHOP_SUP_DEFAULT_HIDDEN,
          };
          hidden = defaults[role] || [];
          // Persist the defaults
          set((state) => ({
            hiddenActions: { ...state.hiddenActions, [role]: hidden as string[] },
          }));
        }

        const order = get().actionOrder[role] || [];

        let visible = allActions.filter(a => !hidden.includes(a.id));

        // Apply custom order if exists
        if (order.length > 0) {
          visible.sort((a, b) => {
            const ai = order.indexOf(a.id);
            const bi = order.indexOf(b.id);
            if (ai === -1 && bi === -1) return 0;
            if (ai === -1) return 1;
            if (bi === -1) return -1;
            return ai - bi;
          });
        }

        return visible;
      },
    }),
    {
      name: 'quick-actions-config',
      storage: createJSONStorage(() => AsyncStorage),
    }
  )
);

// ==================== Default Quick Actions Per Role ====================

export const WORKSHOP_SUP_ACTIONS: QuickAction[] = [
  // 默认显示 — 核心2个
  { id: 'process-operation', label: '工序操作', icon: 'cog-play', iconColor: '#4F46E5', iconBg: '#EEF2FF', screen: 'ProcessOperation' },
  { id: 'my-reports', label: '我的报工', icon: 'clipboard-check', iconColor: '#059669', iconBg: '#F0FDF4', screen: 'MyWorkReports' },
  // 默认隐藏 — 用户可通过编辑模式开启
  { id: 'process-tasks', label: '工序任务', icon: 'format-list-checks', iconColor: '#EA580C', iconBg: '#FFF7ED', screen: 'ProcessTaskList' },
  { id: 'yield-report', label: '逐道报工', icon: 'clipboard-edit-outline', iconColor: '#7C3AED', iconBg: '#F5F3FF', screen: 'YieldBatchSelect' },
  { id: 'checkin', label: '扫码签到', icon: 'qrcode-scan', iconColor: '#059669', iconBg: '#ECFDF5', screen: 'NfcCheckin' },
  { id: 'team-report', label: '班组报工', icon: 'account-group', iconColor: '#059669', iconBg: '#ECFDF5', screen: 'TeamBatchReport' },
  { id: 'equipment', label: '设备监控', icon: 'tools', iconColor: '#0891B2', iconBg: '#ECFEFF', screen: 'EquipmentMonitoring' },
];

export const DISPATCHER_ACTIONS: QuickAction[] = [
  { id: 'create-plan', label: '创建计划', icon: 'plus-circle', iconColor: '#7C3AED', iconBg: '#F5F3FF', screen: 'PlanCreate' },
  { id: 'plan-list', label: '计划列表', icon: 'clipboard-list', iconColor: '#1890ff', iconBg: '#EFF6FF', screen: 'PlanList' },
  { id: 'personnel', label: '人员管理', icon: 'account-group', iconColor: '#7C3AED', iconBg: '#F5F3FF', screen: 'PersonnelSchedule' },
  { id: 'smartbi', label: '智能分析', icon: 'chart-timeline-variant', iconColor: '#1890ff', iconBg: '#EFF6FF', screen: 'SmartBI' },
  { id: 'production-line', label: '产线状态', icon: 'factory', iconColor: '#52c41a', iconBg: '#F6FFED', screen: 'ProductionLine' },
];

export const WAREHOUSE_MGR_ACTIONS: QuickAction[] = [
  { id: 'manual-receipt', label: '手动入库', icon: 'package-down', iconColor: '#059669', iconBg: '#ECFDF5', screen: 'MaterialReceipt' },
  { id: 'ai-receipt', label: 'AI入库', icon: 'robot', iconColor: '#7C3AED', iconBg: '#F5F3FF', screen: 'MaterialReceiptAI' },
  { id: 'inventory', label: '库存查看', icon: 'warehouse', iconColor: '#1890ff', iconBg: '#EFF6FF', screen: 'MaterialBatchManagement' },
  { id: 'outbound', label: '出库管理', icon: 'package-up', iconColor: '#EA580C', iconBg: '#FFF7ED', screen: 'WHOutboundList' },
];
