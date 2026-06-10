/**
 * 教程引导状态管理
 *
 * 精确高亮模式：每步高亮实际按钮/区域
 * 支持跨页面教程流：首页高亮按钮 → 导航进入 → 子页面教程继续
 * "我的"页面可重置所有教程
 *
 * TODO: 教程功能暂时关闭，后续优化后打开此开关
 */

/** 总开关 — 设为 true 启用教程引导 */
export const TUTORIAL_ENABLED = false;
import { useRef, useCallback, useEffect } from 'react';
import { View } from 'react-native';
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';

// ==================== Types ====================

export interface TargetLayout {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface TutorialStep {
  icon: string;
  iconColor?: string;
  iconBg?: string;
  title: string;
  text: string;
  /** 高亮目标的注册 key */
  targetKey?: string;
  /** 气泡位置: 'top' | 'bottom' | 'auto' */
  tooltipPosition?: 'top' | 'bottom' | 'auto';
  /** 高亮区域额外 padding (默认 8) */
  highlightPadding?: number;
  /** 高亮区域圆角 (默认 12) */
  highlightBorderRadius?: number;
  /** 点击"下一步"后导航到此页面 (暂停当前教程) */
  navigateTo?: string;
  /** 自定义下一步按钮文字 (默认 "下一步"/"开始使用") */
  nextButtonText?: string;
}

export interface TutorialConfig {
  id: string;
  steps: TutorialStep[];
}

// ==================== Target Layout Registry ====================

const targetRegistry = new Map<string, TargetLayout>();

export function registerTutorialTarget(key: string, layout: TargetLayout) {
  targetRegistry.set(key, layout);
}

export function getTutorialTarget(key: string): TargetLayout | undefined {
  return targetRegistry.get(key);
}

export function clearTutorialTargets() {
  targetRegistry.clear();
}

// ==================== Hook: 为页面元素注册高亮目标 ====================

export function useTutorialTarget(targetKey: string) {
  const ref = useRef<View>(null);
  const measure = useCallback(() => {
    ref.current?.measureInWindow((x: number, y: number, width: number, height: number) => {
      if (width > 0 && height > 0) {
        registerTutorialTarget(targetKey, { x, y, width, height });
      }
    });
  }, [targetKey]);
  const onLayout = useCallback(() => {
    setTimeout(measure, 100);
  }, [measure]);
  return { ref, onLayout, measure };
}

// ==================== Hook: 一行接入教程 (auto-start + state + callbacks) ====================

const NOOP = () => {};
const DISABLED_RESULT = { visible: false, step: 0, onNext: NOOP, onSkip: NOOP };

export function useTutorial(config: TutorialConfig, remeasure?: () => void) {
  const remeasureRef = useRef(remeasure);
  remeasureRef.current = remeasure;

  const activeTutorial = useTutorialStore(s => s.activeTutorial);
  const activeStep = useTutorialStore(s => s.activeStep);
  const completed = useTutorialStore(s => s.completedTutorials[config.id]);
  const visible = TUTORIAL_ENABLED && activeTutorial === config.id;

  // Auto-start on first visit — re-check at timer time to avoid races after reset
  useEffect(() => {
    if (!TUTORIAL_ENABLED) return undefined;
    if (!completed && activeTutorial === null) {
      const timer = setTimeout(() => {
        const s = useTutorialStore.getState();
        if (!s.activeTutorial && !s.completedTutorials[config.id]) {
          s.startTutorial(config.id);
        }
      }, 800);
      return () => clearTimeout(timer);
    }
    return undefined;
  }, [completed]);

  // Re-measure when tutorial becomes visible
  useEffect(() => {
    if (visible && remeasureRef.current) {
      setTimeout(remeasureRef.current, 200);
    }
  }, [visible]);

  const onNext = useCallback(() => {
    useTutorialStore.getState().nextStep(config.steps.length);
  }, [config.steps.length]);

  const onSkip = useCallback(() => {
    useTutorialStore.getState().skipTutorial();
  }, []);

  return { visible, step: activeStep, onNext, onSkip };
}

// ==================== Store ====================

interface TutorialState {
  completedTutorials: Record<string, boolean>;
  activeTutorial: string | null;
  activeStep: number;
  /** 被暂停的教程 (tutorialId → 暂停时的步骤号) */
  pausedTutorials: Record<string, number>;

  startTutorial: (tutorialId: string) => void;
  nextStep: (totalSteps: number) => void;
  skipTutorial: () => void;
  completeTutorial: () => void;
  /** 暂停当前教程 (导航到子页面时用) */
  pauseTutorial: () => void;
  /** 恢复暂停的教程 */
  resumeTutorial: (tutorialId: string) => void;
  resetAllTutorials: () => void;
  isTutorialCompleted: (tutorialId: string) => boolean;
  shouldAutoStart: (tutorialId: string) => boolean;
}

export const useTutorialStore = create<TutorialState>()(
  persist(
    (set, get) => ({
      completedTutorials: {},
      activeTutorial: null,
      activeStep: 0,
      pausedTutorials: {},

      startTutorial: (tutorialId) => {
        set({ activeTutorial: tutorialId, activeStep: 0 });
      },

      nextStep: (totalSteps) => {
        const { activeStep, activeTutorial } = get();
        if (activeStep + 1 >= totalSteps) {
          set((state) => ({
            activeTutorial: null,
            activeStep: 0,
            completedTutorials: {
              ...state.completedTutorials,
              [activeTutorial!]: true,
            },
          }));
        } else {
          set({ activeStep: activeStep + 1 });
        }
      },

      skipTutorial: () => {
        const { activeTutorial } = get();
        if (activeTutorial) {
          set((state) => ({
            activeTutorial: null,
            activeStep: 0,
            completedTutorials: {
              ...state.completedTutorials,
              [activeTutorial]: true,
            },
          }));
        }
      },

      completeTutorial: () => {
        const { activeTutorial } = get();
        if (activeTutorial) {
          set((state) => ({
            activeTutorial: null,
            activeStep: 0,
            completedTutorials: {
              ...state.completedTutorials,
              [activeTutorial]: true,
            },
          }));
        }
      },

      pauseTutorial: () => {
        const { activeTutorial, activeStep } = get();
        if (activeTutorial) {
          set((state) => ({
            activeTutorial: null,
            activeStep: 0,
            pausedTutorials: {
              ...state.pausedTutorials,
              [activeTutorial]: activeStep,
            },
          }));
        }
      },

      resumeTutorial: (tutorialId) => {
        const step = get().pausedTutorials[tutorialId];
        if (step !== undefined) {
          const { [tutorialId]: _, ...rest } = get().pausedTutorials;
          set({
            activeTutorial: tutorialId,
            activeStep: step,
            pausedTutorials: rest,
          });
        }
      },

      resetAllTutorials: () => {
        set({ completedTutorials: {}, activeTutorial: null, activeStep: 0, pausedTutorials: {} });
      },

      isTutorialCompleted: (tutorialId) => {
        return get().completedTutorials[tutorialId] === true;
      },

      shouldAutoStart: (tutorialId) => {
        return !get().completedTutorials[tutorialId] && get().activeTutorial === null;
      },
    }),
    {
      name: 'tutorial-state',
      storage: createJSONStorage(() => AsyncStorage),
    }
  )
);

// ==================== Tutorial Configs ====================

export const TUTORIAL_HOME: TutorialConfig = {
  id: 'ws-home',
  steps: [
    {
      icon: 'cog-play',
      iconColor: '#4F46E5',
      iconBg: '#EEF2FF',
      title: '工序操作',
      text: '最常用的功能！选工序后可以给员工扫码签到/签退，点击「进入三阶段报工」按分投入→时段→产出三步完整记录产量。点击下一步进去看看。',
      targetKey: 'ws-home-qa-process-operation',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 14,
      navigateTo: 'ProcessOperation',
      nextButtonText: '进入体验 →',
    },
    {
      icon: 'clipboard-check',
      iconColor: '#059669',
      iconBg: '#F0FDF4',
      title: '我的报工',
      text: '查看你提交过的所有报工记录，包括每条的审批状态（待审批/已通过/已驳回）。',
      targetKey: 'ws-home-qa-my-reports',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 14,
    },
    {
      icon: 'format-list-checks',
      iconColor: '#EA580C',
      iconBg: '#FFF7ED',
      title: '工序任务',
      text: '按工序维度查看所有任务列表和完成进度，方便统一管理。',
      targetKey: 'ws-home-qa-process-tasks',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 14,
    },
    {
      icon: 'clipboard-list',
      iconColor: '#2563EB',
      iconBg: '#EFF6FF',
      title: '下一批任务',
      text: '蓝色卡片显示你下一个需要处理的生产任务。点击"开始任务"进入执行流程。',
      targetKey: 'ws-home-next-task',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 12,
    },
    {
      icon: 'view-dashboard',
      iconColor: '#7C3AED',
      iconBg: '#F5F3FF',
      title: '底部导航',
      text: '底部Tab可以切换到：批次管理、人员管理、我的（设置和教程重置）。',
      targetKey: 'ws-home-tab-bar',
      tooltipPosition: 'top',
      highlightBorderRadius: 0,
    },
  ],
};

export const TUTORIAL_PROCESS_OPERATION: TutorialConfig = {
  id: 'ws-process-operation',
  steps: [
    {
      icon: 'format-list-checks',
      iconColor: '#EA580C',
      iconBg: '#FFF7ED',
      title: '选择工序',
      text: '进入后先选择要操作的工序。每张卡片显示工序名称、计划量和完成进度。',
      targetKey: 'po-task-list',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 12,
    },
    {
      icon: 'login',
      iconColor: '#059669',
      iconBg: '#ECFDF5',
      title: '员工签到/签退',
      text: '选好工序后，用绿色按钮扫描员工工牌签到，红色按钮签退。系统自动记录工时。',
      targetKey: 'po-attendance',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 12,
    },
    {
      icon: 'counter',
      iconColor: '#2563EB',
      iconBg: '#EFF6FF',
      title: '报产量',
      text: '输入产出数量并提交。可以先扫描员工工牌指定报工人，不扫则默认是你自己。',
      targetKey: 'po-report',
      tooltipPosition: 'top',
      highlightBorderRadius: 12,
    },
  ],
};

export const TUTORIAL_BATCHES: TutorialConfig = {
  id: 'ws-batches',
  steps: [
    {
      icon: 'filter-variant',
      iconColor: '#1890ff',
      iconBg: '#E6F7FF',
      title: '筛选批次',
      text: '顶部标签按状态筛选：全部、进行中、待开始、已完成。快速找到你要管理的批次。',
      targetKey: 'batches-filter',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 20,
    },
    {
      icon: 'package-variant',
      iconColor: '#059669',
      iconBg: '#ECFDF5',
      title: '批次卡片',
      text: '点击任意批次卡片查看详情，包括生产进度、原料消耗和报工记录。',
      targetKey: 'batches-first-card',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 12,
    },
    {
      icon: 'format-list-checks',
      iconColor: '#EA580C',
      iconBg: '#FFF7ED',
      title: '工序视图',
      text: '点击右上角按钮可切换到工序任务视图，按工序查看进度和报工。',
      targetKey: 'batches-process-btn',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 20,
    },
  ],
};

export const TUTORIAL_THREE_STEP: TutorialConfig = {
  id: 'ws-three-step',
  steps: [
    {
      icon: 'shoe-print',
      iconColor: '#7C3AED',
      iconBg: '#F5F3FF',
      title: '三步流程',
      text: '按顺序操作：扫描员工 → 选工序 → 输数量提交。顶部圆点显示当前进度。',
      targetKey: 'tsr-step-indicator',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 20,
    },
    {
      icon: 'qrcode-scan',
      iconColor: '#059669',
      iconBg: '#ECFDF5',
      title: '扫描或跳过',
      text: '扫描员工工牌确认报工人。主管自己报工可以直接点"跳过"。',
      targetKey: 'tsr-scan-area',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 12,
    },
  ],
};

export const TUTORIAL_MY_REPORTS: TutorialConfig = {
  id: 'ws-my-reports',
  steps: [
    {
      icon: 'filter-variant',
      iconColor: '#e6a23c',
      iconBg: '#FFF7ED',
      title: '筛选状态',
      text: '按审批状态筛选：全部、待审批、已批准、已拒绝。快速查看进度。',
      targetKey: 'mwr-filter',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 8,
    },
    {
      icon: 'clipboard-text',
      iconColor: '#1890ff',
      iconBg: '#E6F7FF',
      title: '报工记录',
      text: '每条记录显示工序、产量和审批状态。下拉可刷新最新数据。',
      targetKey: 'mwr-list',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 12,
    },
  ],
};

export const TUTORIAL_WORKERS: TutorialConfig = {
  id: 'ws-workers',
  steps: [
    {
      icon: 'filter-variant',
      iconColor: '#1890ff',
      iconBg: '#E6F7FF',
      title: '筛选人员',
      text: '按状态筛选：全部、在岗、请假、临时工。快速找到目标员工。',
      targetKey: 'workers-filter',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 20,
    },
    {
      icon: 'account-details',
      iconColor: '#059669',
      iconBg: '#ECFDF5',
      title: '人员卡片',
      text: '显示员工当日工时和效率。点击卡片查看详细出勤记录。',
      targetKey: 'workers-list',
      tooltipPosition: 'bottom',
      highlightBorderRadius: 12,
    },
  ],
};
