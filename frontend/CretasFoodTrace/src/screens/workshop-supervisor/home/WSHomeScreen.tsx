/**
 * Workshop Supervisor 首页 Dashboard
 * 任务导向设计: 下一批任务最醒目 + 任务统计 + 进行中批次 + 人员/设备状态
 */
import React, { useEffect, useState, useCallback, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  Dimensions,
  ActivityIndicator,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';
import { WSHomeStackParamList } from '../../../types/navigation';
import { useAuthStore } from '../../../store/authStore';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';
import { OfflineIndicator } from '../../../components/common/OfflineIndicator';
import { QuickActionsGrid } from '../../../components/common/QuickActionsGrid';
import { TutorialOverlay } from '../../../components/common/TutorialOverlay';
import { WORKSHOP_SUP_ACTIONS } from '../../../store/quickActionsStore';
import { useTutorialStore, TUTORIAL_HOME, TUTORIAL_ENABLED, useTutorialTarget, registerTutorialTarget } from '../../../store/tutorialStore';
import { useDraftReportStore } from '../../../store/draftReportStore';
import { WorkflowVisualizer } from '../../../components/workflow';
import { getBucketPrimaryStatus } from '../../../types/workflow';
import type { WorkflowModule } from '../../../types/workflow';

const WS_WORKFLOW_MODULES: WorkflowModule[] = ['production'];
import { dashboardAPI } from '../../../services/api/dashboardApiClient';
import { processingApiClient } from '../../../services/api/processingApiClient';
import { schedulingApiClient, SupervisorTaskDTO } from '../../../services/api/schedulingApiClient';
import { isAxiosError } from 'axios';

type NavigationProp = NativeStackNavigationProp<WSHomeStackParamList, 'WSHome'>;

// 下一批任务数据类型
interface NextTask {
  batchId: string;
  batchNumber: string;
  productName: string;
  targetQuantity: number;
  plannedStartTime: string;
  workshopLocation: string;
  assignedWorkers: number;
  equipment: string;
  isUrgent: boolean;
}

// 任务统计
interface TaskStats {
  assigned: number;
  inProgress: number;
  completed: number;
}

// 进行中批次
interface InProgressBatch {
  batchId: string;
  batchNumber: string;
  productName: string;
  stage: string;
  progress: number;
  currentOutput: number;
  targetOutput: number;
  estimatedTime: string;
}

// 人员状态
interface PersonnelStatus {
  onDuty: number;
  onLeave: number;
  absent: number;
  total: number;
}

// 设备状态
interface EquipmentStatus {
  running: number;
  idle: number;
  needMaintenance: number;
  total: number;
}

export function WSHomeScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { user } = useAuthStore();
  const { isScreenEnabled, getBenchmarks } = useFactoryFeatureStore();
  const { t } = useTranslation('workshop');
  const insets = useSafeAreaInsets();
  const draftCount = useDraftReportStore(s => s.drafts.length);
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Tutorial
  const activeTutorial = useTutorialStore(s => s.activeTutorial);
  const activeStep = useTutorialStore(s => s.activeStep);
  const completedHome = useTutorialStore(s => s.completedTutorials[TUTORIAL_HOME.id]);
  const showTutorial = activeTutorial === TUTORIAL_HOME.id;

  // Spotlight targets
  const tgtNextTask = useTutorialTarget('ws-home-next-task');

  // Quick action button → target key mapping (for QuickActionsGrid)
  const tutorialTargets = {
    'process-operation': 'ws-home-qa-process-operation',
    'my-reports': 'ws-home-qa-my-reports',
    'process-tasks': 'ws-home-qa-process-tasks',
    'three-step': 'ws-home-qa-three-step',
  };

  // Auto-start or resume tutorial — only when screen is focused
  useFocusEffect(useCallback(() => {
    if (!TUTORIAL_ENABLED || loading || completedHome) return undefined;

    const state = useTutorialStore.getState();

    // Case 1: Resume a paused tutorial (returning from sub-screen)
    const paused = state.pausedTutorials[TUTORIAL_HOME.id];
    if (paused !== undefined) {
      const t = setTimeout(() => useTutorialStore.getState().resumeTutorial(TUTORIAL_HOME.id), 600);
      return () => clearTimeout(t);
    }

    // Case 2: Fresh start — re-check at timer time to avoid races after reset
    if (state.activeTutorial === null) {
      const t = setTimeout(() => {
        const s = useTutorialStore.getState();
        if (!s.activeTutorial && !s.completedTutorials[TUTORIAL_HOME.id]) {
          s.startTutorial(TUTORIAL_HOME.id);
        }
      }, 1200);
      return () => clearTimeout(t);
    }

    return undefined;
  }, [loading, completedHome]));

  // Register tab bar position (estimated from screen bottom)
  useEffect(() => {
    const { width, height } = Dimensions.get('window');
    registerTutorialTarget('ws-home-tab-bar', {
      x: 0, y: height - 56 - insets.bottom, width, height: 56 + insets.bottom,
    });
  }, [insets.bottom]);

  // ScrollView ref + scroll tracking
  const scrollRef = useRef<ScrollView>(null);
  const scrollOffsetRef = useRef(0);

  // Re-measure when tutorial starts/resumes
  useEffect(() => {
    if (showTutorial) {
      setTimeout(() => tgtNextTask.measure(), 200);
    }
  }, [showTutorial]);

  const navigateQuickAction = useCallback((screen: string) => {
    switch (screen) {
      case 'ProcessOperation':
        navigation.navigate('ProcessOperation');
        return;
      case 'MyWorkReports':
        navigation.navigate('MyWorkReports');
        return;
      case 'ProcessTaskList':
        navigation.navigate('ProcessTaskList');
        return;
      case 'YieldBatchSelect':
        navigation.navigate('YieldBatchSelect');
        return;
      case 'NfcCheckin':
        navigation.navigate('NfcCheckin');
        return;
      case 'TeamBatchReport':
        navigation.navigate('TeamBatchReport');
        return;
      case 'EquipmentMonitoring':
        navigation.navigate('EquipmentMonitoring');
        return;
      default:
        return;
    }
  }, [navigation]);

  // Auto-scroll if target off-screen (e.g. next-task card below fold)
  useEffect(() => {
    if (!showTutorial) return;
    const step = TUTORIAL_HOME.steps[activeStep];
    if (!step?.targetKey || step.targetKey === 'ws-home-tab-bar') return;

    // Only auto-scroll for targets we own a ref to (next-task)
    if (step.targetKey === 'ws-home-next-task' && tgtNextTask.ref.current) {
      tgtNextTask.ref.current.measureInWindow((_x: number, y: number, _w: number, h: number) => {
        const screenH = Dimensions.get('window').height;
        if (y + h > screenH - 100 || y < 0) {
          const contentY = y + scrollOffsetRef.current;
          scrollRef.current?.scrollTo({ y: Math.max(0, contentY - 80), animated: true });
          setTimeout(() => tgtNextTask.measure(), 500);
        }
      });
    }
  }, [showTutorial, activeStep]);

  // Tutorial next handler — supports navigateTo for cross-screen flow
  const handleTutorialNext = useCallback(() => {
    const { activeStep: step } = useTutorialStore.getState();
    const currentStep = TUTORIAL_HOME.steps[step];

    // Advance the step first
    useTutorialStore.getState().nextStep(TUTORIAL_HOME.steps.length);

    // If this step triggers navigation, pause and navigate
    if (currentStep?.navigateTo) {
      setTimeout(() => {
        useTutorialStore.getState().pauseTutorial();
        navigation.navigate(currentStep.navigateTo as never);
      }, 100);
    }
  }, [navigation]);

  // 状态
  // 状态数据 - 从API加载
  const [nextTask, setNextTask] = useState<NextTask | null>(null);
  const [taskStats, setTaskStats] = useState<TaskStats>({
    assigned: 0,
    inProgress: 0,
    completed: 0,
  });
  const [inProgressBatches, setInProgressBatches] = useState<InProgressBatch[]>([]);
  const [personnelStatus, setPersonnelStatus] = useState<PersonnelStatus>({
    onDuty: 0,
    onLeave: 0,
    absent: 0,
    total: 0,
  });
  const [equipmentStatus, setEquipmentStatus] = useState<EquipmentStatus>({
    running: 0,
    idle: 0,
    needMaintenance: 0,
    total: 0,
  });
  const [scheduleTasks, setScheduleTasks] = useState<SupervisorTaskDTO[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);

  // 获取问候语
  const getGreeting = () => {
    const hour = new Date().getHours();
    if (hour < 6) return t('home.greeting.earlyMorning');
    if (hour < 9) return t('home.greeting.morning');
    if (hour < 12) return t('home.greeting.forenoon');
    if (hour < 14) return t('home.greeting.noon');
    if (hour < 18) return t('home.greeting.afternoon');
    if (hour < 22) return t('home.greeting.evening');
    return t('home.greeting.lateNight');
  };

  // 加载数据
  const loadData = useCallback(async () => {
    try {
      setError(null);
      // 获取Dashboard概览数据
      const overviewRes = await dashboardAPI.getDashboardOverview('today');
      if (overviewRes.success && overviewRes.data) {
        const { summary, todayStats } = overviewRes.data;

        // 更新任务统计
        setTaskStats({
          assigned: summary?.totalBatches || 0,
          inProgress: summary?.activeBatches || 0,
          completed: summary?.completedBatches || 0,
        });

        // 更新人员状态
        setPersonnelStatus({
          onDuty: summary?.onDutyWorkers || todayStats?.activeWorkers || 0,
          onLeave: 0, // TODO: P2 - Backend needs to provide leave count
          absent: (summary?.totalWorkers || 0) - (summary?.onDutyWorkers || 0),
          total: summary?.totalWorkers || todayStats?.totalWorkers || 0,
        });

        // 更新设备状态
        setEquipmentStatus({
          running: todayStats?.activeEquipment || 0,
          idle: (todayStats?.totalEquipment || 0) - (todayStats?.activeEquipment || 0),
          needMaintenance: 0, // TODO: P2 - Backend needs to provide maintenance count
          total: todayStats?.totalEquipment || 0,
        });
      }

      // 获取进行中批次
      const inProgressRes = await processingApiClient.getBatches({ status: 'IN_PROGRESS', page: 1, size: 5 });
      if (inProgressRes.success && inProgressRes.data?.content) {
        const batches = inProgressRes.data.content.map(batch => {
          const progress = batch.actualQuantity && batch.targetQuantity
            ? Math.round((batch.actualQuantity / batch.targetQuantity) * 100)
            : 0;
          return {
            batchId: String(batch.id),
            batchNumber: batch.batchNumber,
            productName: batch.productType,
            stage: batch.status === 'IN_PROGRESS' ? '加工中' : batch.status,
            progress,
            currentOutput: batch.actualQuantity || 0,
            targetOutput: batch.targetQuantity,
            estimatedTime: batch.endTime ? new Date(batch.endTime).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) : '--:--',
          };
        });
        setInProgressBatches(batches);
      }

      // 获取下一批任务（待开始的批次）
      const pendingRes = await processingApiClient.getBatches({ status: 'PLANNED', page: 1, size: 1 });
      if (pendingRes.success && pendingRes.data?.content?.length > 0) {
        const batch = pendingRes.data.content[0];
        if (batch) {
          setNextTask({
            batchId: String(batch.id),
            batchNumber: batch.batchNumber,
            productName: batch.productType,
            targetQuantity: batch.targetQuantity,
            plannedStartTime: batch.startTime ? new Date(batch.startTime).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) : '--:--',
            workshopLocation: 'A区', // Note: Workshop location not in ProcessingBatch, using default
            assignedWorkers: 0, // Note: Assigned workers not in ProcessingBatch
            equipment: '', // Note: Equipment not in ProcessingBatch
            isUrgent: false, // Note: Urgency not in ProcessingBatch
          });
        } else {
          setNextTask(null);
        }
      } else {
        // 无待处理任务
        setNextTask(null);
      }

      // 获取未读通知数
      try {
        const { notificationApiClient } = await import('../../../services/api/notificationApiClient');
        const notifRes = await notificationApiClient.getUnreadCount();
        if (notifRes?.success && notifRes.data) {
          setUnreadCount(notifRes.data.count ?? 0);
        }
      } catch { /* silent */ }

      // 获取排程任务
      try {
        const tasksRes = await schedulingApiClient.getSupervisorTasks('pending,in_progress');
        if (tasksRes.success && tasksRes.data) {
          setScheduleTasks(tasksRes.data);
        }
      } catch (scheduleError) {
        console.error('获取排程任务失败:', scheduleError);
      }
    } catch (err) {
      console.error('加载车间主管首页数据失败:', err);
      setError('加载失败');
      if (isAxiosError(err)) {
        if (err.response?.status === 401) {
          // TODO: Handle auth expired
          console.error('认证过期，请重新登录');
        }
      }
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  // 开始任务
  const handleStartTask = () => {
    if (nextTask) {
      navigation.navigate('TaskGuide', { batchId: nextTask.batchId, batchNumber: nextTask.batchNumber });
    }
  };

  if (loading) {
    return (
      <View style={styles.container}>
        <View style={[styles.header, { paddingTop: insets.top + 8 }]} />
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#667eea" />
          <Text style={styles.loadingText}>{t('common.loading')}</Text>
        </View>
      </View>
    );
  }

  // 错误状态
  if (error && taskStats.assigned === 0 && taskStats.completed === 0 && inProgressBatches.length === 0) {
    return (
      <View style={styles.container}>
        <View style={[styles.header, { paddingTop: insets.top + 8 }]} />
        <View style={styles.loadingContainer}>
          <MaterialCommunityIcons name="cloud-off-outline" size={48} color="#C0C4CC" />
          <Text style={[styles.loadingText, { color: '#606266', marginTop: 12 }]}>加载失败，请检查网络</Text>
          <TouchableOpacity
            style={{ marginTop: 16, paddingHorizontal: 20, paddingVertical: 8, backgroundColor: '#059669', borderRadius: 6 }}
            onPress={() => loadData()}
          >
            <Text style={{ color: '#fff', fontSize: 14, fontWeight: '500' }}>重试</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <ScrollView
        ref={scrollRef}
        onScroll={(e) => { scrollOffsetRef.current = e.nativeEvent.contentOffset.y; }}
        scrollEventThrottle={100}
        style={styles.scrollView}
        showsVerticalScrollIndicator={false}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            colors={['#667eea']}
            tintColor="#667eea"
          />
        }
      >
        {/* 离线状态指示器 */}
        <OfflineIndicator />

        {/* 头部欢迎区 */}
        <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
          <View style={styles.headerLeft}>
            <Text style={styles.greeting}>
              {getGreeting()}，{user?.username ?? t('profile.role')}
            </Text>
            <Text style={styles.subTitle}>{t('home.subtitle')}</Text>
          </View>
          <TouchableOpacity
            style={styles.notificationBtn}
            onPress={() => navigation.navigate('Notifications')}
          >
            <Icon source="bell-outline" size={24} color="#fff" />
            {unreadCount > 0 && (
            <View style={styles.notificationBadge}>
              <Text style={styles.notificationBadgeText}>{unreadCount > 99 ? '99+' : unreadCount}</Text>
            </View>
            )}
          </TouchableOpacity>
        </View>

        {/* U-NAV-1 业务流程图导航 — Sprint 2 Track G (WS: production) */}
        <View style={{ marginHorizontal: 16, marginTop: 12 }}>
          <WorkflowVisualizer
            modules={WS_WORKFLOW_MODULES}
            factoryId={user?.factoryId}
            aiTriggerEnabled
            onNodePress={(module, nodeId) =>
              navigation.navigate('ProductionPlanManagement', { statusFilter: getBucketPrimaryStatus(module, nodeId) })
            }
            onNodeLongPress={(_module, ctx) =>
              navigation.navigate('AIChat', { entryContext: ctx })
            }
            onAITrigger={(_module, ctx) =>
              navigation.navigate('AIChat', { entryContext: ctx })
            }
          />
        </View>

        {/* 快捷操作 */}
        <View testID="quick-actions-section" style={styles.quickActionsSection}>
          <QuickActionsGrid
            role="workshop_supervisor"
            allActions={WORKSHOP_SUP_ACTIONS}
            onActionPress={(action) => {
              navigateQuickAction(action.screen);
            }}
            tutorialTargets={tutorialTargets}
          />
        </View>

        {/* 下一批任务卡片 - 最醒目 */}
        {nextTask && (
          <View ref={tgtNextTask.ref} onLayout={tgtNextTask.onLayout} style={[styles.nextTaskCard, nextTask.isUrgent && styles.urgentCard]}>
            <View style={styles.nextTaskHeader}>
              <View>
                <View style={styles.nextTaskTitleRow}>
                  <Icon source="clipboard-list" size={20} color="#fff" />
                  <Text style={styles.nextTaskTitle}>{t('home.nextTask.title')}</Text>
                </View>
                <Text style={styles.nextTaskBatchNo}>{nextTask.batchNumber}</Text>
              </View>
              {nextTask.isUrgent && (
                <View style={styles.urgentBadge}>
                  <Text style={styles.urgentBadgeText}>{t('home.nextTask.urgent')}</Text>
                </View>
              )}
            </View>

            <View style={styles.nextTaskInfo}>
              <View style={styles.nextTaskInfoRow}>
                <Icon source="fish" size={16} color="rgba(255,255,255,0.8)" />
                <Text style={styles.nextTaskInfoText}>{nextTask.productName} · {nextTask.targetQuantity}kg</Text>
              </View>
              <View style={styles.nextTaskInfoRow}>
                <Icon source="map-marker" size={16} color="rgba(255,255,255,0.8)" />
                <Text style={styles.nextTaskInfoText}>{nextTask.workshopLocation}</Text>
              </View>
              <View style={styles.nextTaskInfoRow}>
                <Icon source="account-group" size={16} color="rgba(255,255,255,0.8)" />
                <Text style={styles.nextTaskInfoText}>{t('home.nextTask.assignedWorkers', { count: nextTask.assignedWorkers })}</Text>
              </View>
              <View style={styles.nextTaskInfoRow}>
                <Icon source="clock-outline" size={16} color="rgba(255,255,255,0.8)" />
                <Text style={styles.nextTaskInfoText}>{t('home.nextTask.plannedStart', { time: nextTask.plannedStartTime })}</Text>
              </View>
            </View>

            <TouchableOpacity style={styles.startTaskBtn} onPress={handleStartTask}>
              <Text style={styles.startTaskBtnText}>{t('home.nextTask.startTask')}</Text>
              <Icon source="arrow-right" size={20} color="#667eea" />
            </TouchableOpacity>
          </View>
        )}

        {/* 排程任务区域 */}
        {isScreenEnabled('ScheduleManagement') && scheduleTasks.length > 0 && (
          <View style={styles.section}>
            <View style={styles.sectionHeader}>
              <Text style={styles.sectionTitle}>待执行排程</Text>
              <Text style={styles.viewMoreText}>{scheduleTasks.length}个任务</Text>
            </View>
            {scheduleTasks.slice(0, 3).map((task) => (
              <TouchableOpacity
                key={task.scheduleId}
                style={[styles.scheduleTaskCard, task.isUrgent && styles.urgentScheduleCard]}
                onPress={() => {
                  // 跳转到任务引导
                  if (task.batchNumber) {
                    navigation.navigate('TaskGuide', {
                      batchId: String(task.batchId),
                      batchNumber: task.batchNumber
                    });
                  }
                }}
              >
                <View style={styles.scheduleTaskHeader}>
                  <View>
                    <Text style={styles.scheduleLineName}>{task.productionLineName}</Text>
                    {task.batchNumber && (
                      <Text style={styles.scheduleBatchNo}>{task.batchNumber}</Text>
                    )}
                  </View>
                  {task.isUrgent && (
                    <View style={styles.urgentBadge}>
                      <Text style={styles.urgentBadgeText}>紧急</Text>
                    </View>
                  )}
                </View>
                <View style={styles.scheduleTaskInfo}>
                  <Text style={styles.scheduleTaskInfoText}>
                    {task.productName || '待排产'} · 计划{task.plannedQuantity}kg
                  </Text>
                  <Text style={styles.scheduleTaskTime}>
                    {new Date(task.plannedStartTime).toLocaleTimeString('zh-CN', {
                      hour: '2-digit', minute: '2-digit', hour12: false
                    })} - {new Date(task.plannedEndTime).toLocaleTimeString('zh-CN', {
                      hour: '2-digit', minute: '2-digit', hour12: false
                    })}
                  </Text>
                </View>
              </TouchableOpacity>
            ))}
          </View>
        )}

        {/* 今日任务概览 */}
        <View style={styles.statsSection}>
          <Text style={styles.sectionTitle}>{t('home.todayOverview')}</Text>
          <View style={styles.statsRow}>
            <View style={[styles.statCard, { backgroundColor: '#e6f7ff' }]}>
              <Text style={[styles.statValue, { color: '#1890ff' }]}>{taskStats.assigned}</Text>
              <Text style={styles.statLabel}>{t('home.stats.assigned')}</Text>
            </View>
            <View style={[styles.statCard, { backgroundColor: '#fff7e6' }]}>
              <Text style={[styles.statValue, { color: '#fa8c16' }]}>{taskStats.inProgress}</Text>
              <Text style={styles.statLabel}>{t('home.stats.inProgress')}</Text>
            </View>
            <View style={[styles.statCard, { backgroundColor: '#f6ffed' }]}>
              <Text style={[styles.statValue, { color: '#52c41a' }]}>{taskStats.completed}</Text>
              <Text style={styles.statLabel}>{t('home.stats.completed')}</Text>
            </View>
          </View>
          {(() => {
            const benchmarks = getBenchmarks();
            const yieldBenchmark = benchmarks['yield_rate'];
            if (yieldBenchmark && taskStats.assigned > 0) {
              const completionRate = Math.round((taskStats.completed / taskStats.assigned) * 100);
              const benchColor = completionRate >= (yieldBenchmark.excellent ?? 98)
                ? '#52c41a'
                : completionRate >= (yieldBenchmark.qualified ?? 95)
                  ? '#fa8c16'
                  : '#ff4d4f';
              return (
                <View style={{ marginTop: 8, alignItems: 'center' }}>
                  <Text style={{ fontSize: 12, color: benchColor }}>
                    完成率 {completionRate}% (目标: {yieldBenchmark.qualified ?? 95}%)
                  </Text>
                </View>
              );
            }
            return null;
          })()}
        </View>

        {/* 进行中批次 */}
        {isScreenEnabled('BatchManagement') && (
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>{t('home.inProgressBatches')}</Text>
            <TouchableOpacity onPress={() => navigation.getParent()?.navigate('WSBatchesTab')}>
              <Text style={styles.viewMoreText}>{t('common.viewAll')}</Text>
            </TouchableOpacity>
          </View>

          {inProgressBatches.slice(0, 2).map((batch) => (
            <TouchableOpacity
              key={batch.batchId}
              style={styles.batchCard}
              onPress={() => navigation.navigate('BatchDetail', { batchId: batch.batchId })}
            >
              <View style={styles.batchHeader}>
                <Text style={styles.batchNumber}>{batch.batchNumber}</Text>
                <View style={styles.stageBadge}>
                  <Text style={styles.stageBadgeText}>{batch.stage}</Text>
                </View>
              </View>
              <Text style={styles.batchProduct}>{batch.productName}</Text>
              <View style={styles.progressContainer}>
                <View style={styles.progressBar}>
                  <View style={[styles.progressFill, { width: `${batch.progress}%` }]} />
                </View>
                <Text style={styles.progressText}>{batch.progress}%</Text>
              </View>
              <View style={styles.batchFooter}>
                <Text style={styles.batchOutput}>{t('home.batch.output', { current: batch.currentOutput, target: batch.targetOutput })}</Text>
                <Text style={styles.batchTime}>{t('home.batch.estimated', { time: batch.estimatedTime })}</Text>
              </View>
            </TouchableOpacity>
          ))}
        </View>
        )}

        {/* 人员状态 */}
        {isScreenEnabled('WorkerManagement') && (
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>{t('home.personnelStatus')}</Text>
            <TouchableOpacity onPress={() => navigation.getParent()?.navigate('WSWorkersTab')}>
              <Text style={styles.viewMoreText}>{t('common.viewAll')}</Text>
            </TouchableOpacity>
          </View>
          <View style={styles.statusCard}>
            <View style={styles.statusRow}>
              <View style={[styles.statusItem, { backgroundColor: '#f6ffed' }]}>
                <Text style={[styles.statusValue, { color: '#52c41a' }]}>{personnelStatus.onDuty}</Text>
                <Text style={styles.statusLabel}>{t('home.personnel.onDuty')}</Text>
              </View>
              <View style={[styles.statusItem, { backgroundColor: '#fff7e6' }]}>
                <Text style={[styles.statusValue, { color: '#fa8c16' }]}>{personnelStatus.onLeave}</Text>
                <Text style={styles.statusLabel}>{t('home.personnel.onLeave')}</Text>
              </View>
              <View style={[styles.statusItem, { backgroundColor: '#fff1f0' }]}>
                <Text style={[styles.statusValue, { color: '#ff4d4f' }]}>{personnelStatus.absent}</Text>
                <Text style={styles.statusLabel}>{t('home.personnel.absent')}</Text>
              </View>
            </View>
          </View>
        </View>
        )}

        {/* 设备状态 */}
        {isScreenEnabled('EquipmentMonitoring') && (
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>{t('home.equipmentStatus')}</Text>
            <TouchableOpacity onPress={() => navigation.getParent()?.navigate('WSEquipmentTab')}>
              <Text style={styles.viewMoreText}>{t('common.viewAll')}</Text>
            </TouchableOpacity>
          </View>
          <View style={styles.statusCard}>
            <View style={styles.statusRow}>
              <View style={[styles.statusItem, { backgroundColor: '#f6ffed' }]}>
                <Text style={[styles.statusValue, { color: '#52c41a' }]}>{equipmentStatus.running}</Text>
                <Text style={styles.statusLabel}>{t('home.equipment.running')}</Text>
              </View>
              <View style={[styles.statusItem, { backgroundColor: '#f0f5ff' }]}>
                <Text style={[styles.statusValue, { color: '#1890ff' }]}>{equipmentStatus.idle}</Text>
                <Text style={styles.statusLabel}>{t('home.equipment.idle')}</Text>
              </View>
              <View style={[styles.statusItem, { backgroundColor: '#fff1f0' }]}>
                <Text style={[styles.statusValue, { color: '#ff4d4f' }]}>{equipmentStatus.needMaintenance}</Text>
                <Text style={styles.statusLabel}>{t('home.equipment.needMaintenance')}</Text>
              </View>
            </View>
          </View>
        </View>
        )}

        <View style={{ height: 32 }} />
      </ScrollView>

      {/* Tutorial Overlay */}
      <TutorialOverlay
        visible={showTutorial}
        steps={TUTORIAL_HOME.steps}
        currentStep={activeStep}
        onNext={handleTutorialNext}
        onSkip={() => useTutorialStore.getState().skipTutorial()}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f7fa',
  },
  scrollView: {
    flex: 1,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: '#666',
  },

  // 头部
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 16,
    backgroundColor: '#667eea',
  },
  headerLeft: {
    flex: 1,
  },
  greeting: {
    fontSize: 22,
    fontWeight: '600',
    color: '#fff',
  },
  subTitle: {
    fontSize: 14,
    color: 'rgba(255,255,255,0.8)',
    marginTop: 4,
  },
  notificationBtn: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: 'rgba(255,255,255,0.2)',
    justifyContent: 'center',
    alignItems: 'center',
    position: 'relative',
  },
  notificationBadge: {
    position: 'absolute',
    top: 6,
    right: 6,
    minWidth: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: '#ff4d4f',
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 4,
  },
  notificationBadgeText: {
    fontSize: 10,
    fontWeight: 'bold',
    color: '#fff',
  },

  // 快捷操作
  quickActionsSection: {
    paddingHorizontal: 16,
    marginTop: 16,
  },
  quickActionsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
    marginTop: 12,
  },
  quickActionBtn: {
    width: '21%',
    alignItems: 'center',
  },
  quickActionIcon: {
    width: 48,
    height: 48,
    borderRadius: 14,
    justifyContent: 'center',
    alignItems: 'center',
    position: 'relative',
  },
  quickActionLabel: {
    fontSize: 12,
    color: '#4B5563',
    marginTop: 6,
    fontWeight: '500',
  },
  draftBadge: {
    position: 'absolute',
    top: -4,
    right: -4,
    minWidth: 16,
    height: 16,
    borderRadius: 8,
    backgroundColor: '#EF4444',
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 3,
  },
  draftBadgeText: {
    color: '#fff',
    fontSize: 10,
    fontWeight: '700',
  },

  // 下一批任务卡片
  nextTaskCard: {
    margin: 16,
    padding: 16,
    borderRadius: 12,
    backgroundColor: '#667eea',
  },
  urgentCard: {
    backgroundColor: '#ff4d4f',
  },
  nextTaskHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 12,
  },
  nextTaskTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  nextTaskTitle: {
    fontSize: 14,
    color: 'rgba(255,255,255,0.8)',
    marginLeft: 6,
  },
  nextTaskBatchNo: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#fff',
    marginTop: 4,
  },
  urgentBadge: {
    backgroundColor: 'rgba(255,255,255,0.2)',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  urgentBadgeText: {
    fontSize: 12,
    color: '#fff',
    fontWeight: '500',
  },
  nextTaskInfo: {
    marginBottom: 16,
  },
  nextTaskInfoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 8,
  },
  nextTaskInfoText: {
    fontSize: 14,
    color: 'rgba(255,255,255,0.9)',
    marginLeft: 8,
  },
  startTaskBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#fff',
    paddingVertical: 12,
    borderRadius: 12,
  },
  startTaskBtnText: {
    fontSize: 16,
    fontWeight: '600',
    color: '#667eea',
    marginRight: 8,
  },

  // 统计区
  statsSection: {
    paddingHorizontal: 16,
    marginTop: 8,
  },
  statsRow: {
    flexDirection: 'row',
    gap: 12,
  },
  statCard: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 16,
    borderRadius: 12,
  },
  statValue: {
    fontSize: 28,
    fontWeight: 'bold',
  },
  statLabel: {
    fontSize: 13,
    color: '#666',
    marginTop: 4,
  },

  // 通用区块
  section: {
    paddingHorizontal: 16,
    marginTop: 24,
  },
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1a202c',
  },
  viewMoreText: {
    fontSize: 14,
    color: '#667eea',
  },

  // 批次卡片
  batchCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  batchHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  batchNumber: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  stageBadge: {
    backgroundColor: '#e6f7ff',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  stageBadgeText: {
    fontSize: 12,
    color: '#1890ff',
    fontWeight: '500',
  },
  batchProduct: {
    fontSize: 14,
    color: '#666',
    marginBottom: 12,
  },
  progressContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  progressBar: {
    flex: 1,
    height: 6,
    backgroundColor: '#f0f0f0',
    borderRadius: 3,
    marginRight: 8,
  },
  progressFill: {
    height: '100%',
    backgroundColor: '#667eea',
    borderRadius: 3,
  },
  progressText: {
    fontSize: 12,
    color: '#667eea',
    fontWeight: '500',
    width: 40,
    textAlign: 'right',
  },
  batchFooter: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  batchOutput: {
    fontSize: 13,
    color: '#666',
  },
  batchTime: {
    fontSize: 13,
    color: '#999',
  },

  // 状态卡片
  statusCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
  },
  statusRow: {
    flexDirection: 'row',
    gap: 12,
  },
  statusItem: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 12,
    borderRadius: 8,
  },
  statusValue: {
    fontSize: 24,
    fontWeight: 'bold',
  },
  statusLabel: {
    fontSize: 12,
    color: '#666',
    marginTop: 4,
  },

  // 排程任务卡片
  scheduleTaskCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
    borderLeftWidth: 4,
    borderLeftColor: '#667eea',
  },
  urgentScheduleCard: {
    borderLeftColor: '#ff4d4f',
  },
  scheduleTaskHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 8,
  },
  scheduleLineName: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  scheduleBatchNo: {
    fontSize: 13,
    color: '#666',
    marginTop: 2,
  },
  scheduleTaskInfo: {
    marginTop: 4,
  },
  scheduleTaskInfoText: {
    fontSize: 14,
    color: '#666',
  },
  scheduleTaskTime: {
    fontSize: 13,
    color: '#999',
    marginTop: 4,
  },
});

export default WSHomeScreen;
