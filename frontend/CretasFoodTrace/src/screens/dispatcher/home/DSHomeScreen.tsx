/**
 * 调度工作台首页
 *
 * 核心功能:
 * - AI智能调度中心入口
 * - AI风险预警提示
 * - 今日待处理任务
 * - 车间实时状态
 * - 人员配置概览
 * - 待审批事项
 *
 * @version 1.0.0
 * @since 2025-12-28
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  Alert,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { LinearGradient } from 'expo-linear-gradient';
import { useTranslation } from 'react-i18next';
import { DISPATCHER_THEME, SchedulingDashboard, WorkshopStatus } from '../../../types/dispatcher';
import { schedulingApiClient } from '../../../services/api/schedulingApiClient';
import { productionPlanApiClient } from '../../../services/api/productionPlanApiClient';
import { useAuthStore } from '../../../store/authStore';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';
import { WorkflowVisualizer } from '../../../components/workflow';
import { getBucketPrimaryStatus, type WorkflowModule } from '../../../types/workflow';

const DS_WORKFLOW_MODULES: WorkflowModule[] = ['production', 'sales'];
type FlexibleNavigation = {
  navigate: (screen: string, params?: Record<string, unknown>) => void;
};

export default function DSHomeScreen() {
  const navigation = useNavigation<FlexibleNavigation>();
  const { user } = useAuthStore();
  const { isScreenEnabled } = useFactoryFeatureStore();
  const { t } = useTranslation('dispatcher');
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [dashboard, setDashboard] = useState<SchedulingDashboard | null>(null);
  const [workshops, setWorkshops] = useState<WorkshopStatus[]>([]);
  const [planStats, setPlanStats] = useState({ pending: 0, inProgress: 0, completed: 0, total: 0 });

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      // P0 Fix: Parallel API requests to eliminate waterfall
      // Each request catches independently so one failure doesn't block the rest
      const [dashboardRes, linesRes, plansRes] = await Promise.all([
        schedulingApiClient.getDashboard().catch(() => ({ success: false, data: null })),
        schedulingApiClient.getProductionLines().catch(() => ({ success: false, data: [] })),
        productionPlanApiClient.getProductionPlans({ page: 1, size: 200 }).catch(() => null),
      ]);

      if (dashboardRes.success && dashboardRes.data) {
        setDashboard(dashboardRes.data);
      }

      // Calculate plan stats from production-plans
      if (plansRes?.success !== false) {
        const plans = plansRes?.data?.content || [];
        if (Array.isArray(plans)) {
          const counts = { pending: 0, inProgress: 0, completed: 0, total: plans.length };
          for (const p of plans) {
            const s = (p.status || '').toUpperCase();
            if (s === 'PENDING' || s === 'PLANNED') counts.pending++;
            else if (s === 'IN_PROGRESS') counts.inProgress++;
            else if (s === 'COMPLETED') counts.completed++;
          }
          setPlanStats(counts);
        }
      }

      // Transform production lines to workshop status format
      if (linesRes.success && linesRes.data && linesRes.data.length > 0) {
        // Transform production lines to workshop status format
        const workshopMap = new Map<string, WorkshopStatus>();
        for (const line of linesRes.data) {
          const workshopId = line.workshopId || 'default';
          if (!workshopMap.has(workshopId)) {
            workshopMap.set(workshopId, {
              workshopId,
              workshopName: line.workshopName || line.name || '未命名车间',
              totalWorkers: 0,
              activeWorkers: 0,
              idleWorkers: 0,
              temporaryWorkers: 0,
              utilization: 0,
              currentOutput: 0,
              targetOutput: line.capacity || 0,
              efficiency: 0,
              activeTaskGroups: [],
              alerts: [],
            });
          }
        }
        if (workshopMap.size > 0) {
          setWorkshops(Array.from(workshopMap.values()));
        } else {
          // Fallback when no workshops found from production lines
          setWorkshops([]);
        }
      } else {
        // Fallback when API returns no data
        setWorkshops([]);
      }
    } catch (err) {
      console.error('加载调度首页数据失败:', err);
      setError('加载失败');
      // Fallback when API call fails
      setWorkshops([]);
    } finally {
      setLoading(false);
    }
  }, []);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadData();
    setRefreshing(false);
  }, [loadData]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const completionProbability = dashboard?.aiInsights?.completionProbability ?? 0;
  const riskLevel = dashboard?.aiInsights?.riskLevel ?? 'low';
  const optimizationPotential = dashboard?.aiInsights?.optimizationPotential;

  // 错误状态
  if (error && !dashboard) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <View style={styles.loadingContainer}>
          <MaterialCommunityIcons name="cloud-off-outline" size={48} color="#C0C4CC" />
          <Text style={{ color: '#606266', marginTop: 12, fontSize: 14 }}>{t('error.loadFailed', '加载失败，请检查网络')}</Text>
          <TouchableOpacity
            style={{ marginTop: 16, paddingHorizontal: 20, paddingVertical: 8, backgroundColor: DISPATCHER_THEME.primary, borderRadius: 6 }}
            onPress={() => loadData()}
          >
            <Text style={{ color: '#fff', fontSize: 14, fontWeight: '500' }}>{t('error.retry', '重试')}</Text>
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <ScrollView
        style={styles.scrollView}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
        showsVerticalScrollIndicator={false}
      >
        {/* Header */}
        <LinearGradient
          colors={[DISPATCHER_THEME.gradientStart, DISPATCHER_THEME.secondary]}
          style={styles.header}
        >
          <View style={styles.headerTop}>
            <View style={{ width: 24 }} />
            <Text style={styles.headerTitle}>调度工作台</Text>
            <TouchableOpacity
              style={styles.notificationBtn}
              onPress={() => navigation.navigate('NotificationList')}
            >
              <MaterialCommunityIcons name="bell-outline" size={24} color="white" />
              {(dashboard?.alerts?.unresolved ?? 0) > 0 && (
                <View style={styles.notificationBadge}>
                  <Text style={styles.notificationBadgeText}>
                    {dashboard?.alerts?.unresolved}
                  </Text>
                </View>
              )}
            </TouchableOpacity>
          </View>
          <View style={styles.headerInfo}>
            <View>
              <Text style={styles.headerSubtitle}>
                调度员 · {new Date().toLocaleDateString('zh-CN')}
              </Text>
              <Text style={styles.headerName}>{user?.fullName ?? '调度员'}</Text>
            </View>
            <View style={styles.avatarContainer}>
              <Text style={styles.avatarText}>
                {(user?.fullName ?? '调').charAt(0)}
              </Text>
            </View>
          </View>
        </LinearGradient>

        {/* U-NAV-1 业务流程图导航 — Sprint 2 Track G (DS: production + sales) */}
        <View style={{ marginHorizontal: 16, marginTop: 12 }}>
          <WorkflowVisualizer
            modules={DS_WORKFLOW_MODULES}
            factoryId={user?.factoryId}
            aiTriggerEnabled
            onNodePress={(module, nodeId) => {
              const statusFilter = getBucketPrimaryStatus(module, nodeId);
              if (module === 'production') {
                navigation.navigate('ProductionPlanManagement' as never, { statusFilter } as never);
              } else if (module === 'sales') {
                navigation.navigate('SalesOrderList' as never, { statusFilter } as never);
              }
            }}
            onNodeLongPress={(module, ctx) =>
              navigation.navigate('AIChat' as never, { entryContext: ctx } as never)
            }
            onAITrigger={(_module, ctx) =>
              navigation.navigate('AIChat' as never, { entryContext: ctx } as never)
            }
          />
        </View>

        {/* AI智能调度中心入口 */}
        {/* AI调度卡片: 暂时隐藏，scheduling API 未完成 */}
        {false && isScreenEnabled('AISchedule') && (
        <TouchableOpacity
          onPress={() => navigation.navigate('AIScheduleAnalysis')}
          activeOpacity={0.9}
        >
          <LinearGradient
            colors={[DISPATCHER_THEME.primary, DISPATCHER_THEME.secondary, DISPATCHER_THEME.accent]}
            start={{ x: 0, y: 0 }}
            end={{ x: 1, y: 1 }}
            style={styles.aiCenterCard}
          >
            <View style={styles.aiCenterHeader}>
              <View style={styles.aiCenterIcon}>
                <MaterialCommunityIcons name="robot" size={28} color="white" />
              </View>
              <View>
                <Text style={styles.aiCenterTitle}>AI 智能调度中心</Text>
                <Text style={styles.aiCenterSubtitle}>Monte Carlo + OR-Tools + LLM</Text>
              </View>
            </View>

            <View style={styles.aiProbabilitySection}>
              <View style={styles.aiGaugeMini}>
                <View style={styles.aiGaugeCircle}>
                  <Text style={styles.aiGaugeValue}>{completionProbability}%</Text>
                </View>
              </View>
              <View style={styles.aiStatsList}>
                <View style={styles.aiStatRow}>
                  <Text style={styles.aiStatLabel}>今日完成概率</Text>
                  <Text style={styles.aiStatValue}>{completionProbability}%</Text>
                </View>
                <View style={styles.aiStatRow}>
                  <Text style={styles.aiStatLabel}>待排产批次</Text>
                  <Text style={styles.aiStatValue}>{dashboard?.overview?.delayedPlans ?? 0} 个</Text>
                </View>
                <View style={styles.aiStatRow}>
                  <Text style={styles.aiStatLabel}>优化空间</Text>
                  <Text style={styles.aiStatValue}>{optimizationPotential != null ? `+${optimizationPotential}%` : '-'}</Text>
                </View>
              </View>
            </View>

            <View style={styles.aiActionBtn}>
              <MaterialCommunityIcons name="flash" size={18} color="white" />
              <Text style={styles.aiActionBtnText}>一键 AI 智能排产</Text>
            </View>
          </LinearGradient>
        </TouchableOpacity>
        )}

        {/* AI风险提示 */}
        {isScreenEnabled('AICompletionProb') && riskLevel !== 'low' && (
          <View style={styles.aiRiskAlert}>
            <View style={styles.aiRiskIcon}>
              <MaterialCommunityIcons name="alert" size={16} color="white" />
            </View>
            <View style={styles.aiRiskContent}>
              <Text style={styles.aiRiskTitle}>AI 风险预警</Text>
              <Text style={styles.aiRiskDesc}>
                检测到 {dashboard?.overview?.delayedPlans ?? 0} 个批次完成概率低于70%，建议调整人员配置或交期
              </Text>
            </View>
            <TouchableOpacity
              style={styles.aiRiskAction}
              onPress={() => navigation.navigate('AICompletionProb')}
            >
              <Text style={styles.aiRiskActionText}>查看</Text>
            </TouchableOpacity>
          </View>
        )}

        {/* 今日待处理任务 */}
        <View style={styles.pendingTasksCard}>
          <View style={styles.pendingTasksHeader}>
            <Text style={styles.pendingTasksTitle}>今日待处理</Text>
            <Text style={styles.pendingTasksSubtitle}>工作优先级排序</Text>
          </View>
          <View style={styles.pendingTasksGrid}>
            <TouchableOpacity
              style={styles.pendingTaskItem}
              onPress={() => navigation.navigate('TaskAssignment', { scheduleId: 'new' })}
            >
              <Text style={[styles.pendingTaskCount, styles.danger]}>
                {planStats.pending}
              </Text>
              <Text style={styles.pendingTaskLabel}>待执行</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.pendingTaskItem}
              onPress={() => navigation.navigate('PlanTab')}
            >
              <Text style={[styles.pendingTaskCount, styles.info]}>
                {planStats.inProgress}
              </Text>
              <Text style={styles.pendingTaskLabel}>进行中</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.pendingTaskItem}
              onPress={() => navigation.navigate('PlanTab')}
            >
              <Text style={[styles.pendingTaskCount, { color: '#52c41a' }]}>
                {planStats.completed}
              </Text>
              <Text style={styles.pendingTaskLabel}>已完成</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* 快捷入口 */}
        <View style={styles.pendingTasksCard}>
          <View style={styles.pendingTasksGrid}>
            <TouchableOpacity
              style={styles.pendingTaskItem}
              onPress={() => navigation.navigate('PersonnelSchedule')}
            >
              <MaterialCommunityIcons name="account-group" size={24} color={DISPATCHER_THEME.primary} />
              <Text style={styles.pendingTaskLabel}>人员管理</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.pendingTaskItem}
              onPress={() => navigation.navigate('SmartBI')}
            >
              <MaterialCommunityIcons name="chart-timeline-variant" size={24} color="#1890ff" />
              <Text style={styles.pendingTaskLabel}>智能分析</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.pendingTaskItem}
              onPress={() => navigation.navigate('ProductionLine')}
            >
              <MaterialCommunityIcons name="factory" size={24} color="#52c41a" />
              <Text style={styles.pendingTaskLabel}>产线状态</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* 车间实时状态 */}
        {isScreenEnabled('WorkshopStatus') && (
        <>
        <View style={styles.sectionHeader}>
          <Text style={styles.sectionTitle}>车间实时状态</Text>
          <TouchableOpacity onPress={() => navigation.navigate('WorkshopStatus', { workshopId: 'all' })}>
            <Text style={styles.sectionAction}>查看全部 {'>'}</Text>
          </TouchableOpacity>
        </View>

        <View style={styles.workshopMiniList}>
          {workshops.slice(0, 3).map((workshop, index) => (
            <TouchableOpacity
              key={workshop.workshopId || index}
              style={styles.workshopMiniCard}
              onPress={() => navigation.navigate('WorkshopStatus', { workshopId: workshop.workshopId })}
            >
              <View style={[
                styles.workshopMiniIndicator,
                workshop.utilization > 0 ? styles.indicatorRunning : styles.indicatorIdle
              ]} />
              <View style={styles.workshopMiniInfo}>
                <Text style={styles.workshopMiniName}>{workshop.workshopName}</Text>
                <Text style={styles.workshopMiniMeta}>
                  {workshop.activeWorkers}/{workshop.totalWorkers}人 · {workshop.activeTaskGroups?.length ?? 0}个批次
                </Text>
              </View>
              <View style={styles.workshopMiniProgress}>
                <Text style={[
                  styles.workshopMiniPercent,
                  workshop.utilization === 0 && styles.percentIdle
                ]}>
                  {Math.round((workshop.utilization ?? 0) * 100)}%
                </Text>
              </View>
            </TouchableOpacity>
          ))}
        </View>
        </>
        )}

        {/* 人员配置 */}
        <View style={styles.quickPersonnel}>
          <View style={styles.quickPersonnelHeader}>
            <Text style={styles.quickPersonnelTitle}>人员配置</Text>
            {isScreenEnabled('PersonnelTransfer') && (
            <TouchableOpacity onPress={() => navigation.navigate('PersonnelTransfer')}>
              <Text style={styles.quickPersonnelAction}>调动人员 {'>'}</Text>
            </TouchableOpacity>
            )}
          </View>
          {workshops.slice(0, 3).map((workshop, index) => (
            <View key={workshop.workshopId || index} style={styles.quickPersonnelRow}>
              <Text style={styles.quickPersonnelWorkshop}>{workshop.workshopName}</Text>
              <View style={styles.quickPersonnelStats}>
                <Text style={styles.quickPersonnelCount}>
                  {workshop.activeWorkers}/{workshop.totalWorkers}
                </Text>
                <View style={styles.quickPersonnelBar}>
                  <View
                    style={[
                      styles.quickPersonnelBarFill,
                      { width: `${workshop.totalWorkers > 0 ? (workshop.activeWorkers / workshop.totalWorkers) * 100 : 0}%` }
                    ]}
                  />
                </View>
              </View>
            </View>
          ))}
          <View style={styles.quickPersonnelRow}>
            <Text style={styles.quickPersonnelWorkshop}>机动人员</Text>
            <View style={styles.quickPersonnelStats}>
              <Text style={[styles.quickPersonnelCount, { color: DISPATCHER_THEME.success }]}>
                {dashboard?.workers?.idle ?? 0}人
              </Text>
              {isScreenEnabled('AIWorkerOptimize') && (
              <TouchableOpacity onPress={() => navigation.navigate('AIWorkerOptimize')}>
                <Text style={styles.aiOptimizeLink}>AI优化 →</Text>
              </TouchableOpacity>
              )}
            </View>
          </View>
        </View>

        {/* 待审批事项 */}
        <View style={styles.sectionHeader}>
          <Text style={styles.sectionTitle}>待审批事项</Text>
          <TouchableOpacity onPress={() => navigation.navigate('PlanTab', { screen: 'ApprovalList' })}>
            <Text style={styles.sectionAction}>{dashboard?.alerts?.unresolved ?? 0}项待处理 {'>'}</Text>
          </TouchableOpacity>
        </View>

        <View style={styles.approvalList}>
          {(dashboard?.alerts?.unresolved ?? 0) === 0 ? (
            <View style={styles.approvalCard}>
              <Text style={{ color: '#999', textAlign: 'center', paddingVertical: 12 }}>暂无待审批事项</Text>
            </View>
          ) : (
            <TouchableOpacity
              style={styles.approvalCard}
              onPress={() => navigation.navigate('PlanTab', { screen: 'ApprovalList' })}
            >
              <View style={styles.approvalHeader}>
                <View style={[styles.approvalType, styles.approvalTypePlan]}>
                  <MaterialCommunityIcons name="calendar" size={14} color="#1890ff" />
                  <Text style={styles.approvalTypeText}>审批</Text>
                </View>
                <View style={styles.approvalStatus}>
                  <Text style={styles.approvalStatusText}>待处理</Text>
                </View>
              </View>
              <Text style={styles.approvalContent}>{dashboard?.alerts?.unresolved} 项待审批</Text>
              <Text style={styles.approvalMeta}>点击查看详情</Text>
            </TouchableOpacity>
          )}
        </View>

        <View style={{ height: 100 }} />
      </ScrollView>
    </SafeAreaView>
  );
}


const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: DISPATCHER_THEME.background,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  scrollView: {
    flex: 1,
  },
  header: {
    padding: 16,
    paddingBottom: 20,
  },
  headerTop: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
  },
  headerTitle: {
    color: 'white',
    fontSize: 16,
    fontWeight: '500',
  },
  notificationBtn: {
    position: 'relative',
  },
  notificationBadge: {
    position: 'absolute',
    top: -4,
    right: -4,
    backgroundColor: '#ff4d4f',
    borderRadius: 8,
    paddingHorizontal: 5,
    paddingVertical: 2,
  },
  notificationBadgeText: {
    color: 'white',
    fontSize: 10,
    fontWeight: '600',
  },
  headerInfo: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  headerSubtitle: {
    color: 'rgba(255,255,255,0.8)',
    fontSize: 14,
  },
  headerName: {
    color: 'white',
    fontSize: 20,
    fontWeight: '600',
    marginTop: 4,
  },
  avatarContainer: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: 'rgba(255,255,255,0.3)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: {
    color: 'white',
    fontSize: 18,
    fontWeight: '600',
  },

  // AI Center Card
  aiCenterCard: {
    borderRadius: 12,
    padding: 20,
    margin: 16,
    position: 'relative',
    overflow: 'hidden',
  },
  aiCenterHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginBottom: 16,
  },
  aiCenterIcon: {
    width: 48,
    height: 48,
    backgroundColor: 'rgba(255,255,255,0.2)',
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  aiCenterTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: 'white',
  },
  aiCenterSubtitle: {
    fontSize: 12,
    color: 'rgba(255,255,255,0.8)',
    marginTop: 2,
  },
  aiProbabilitySection: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 20,
    marginBottom: 16,
  },
  aiGaugeMini: {
    width: 80,
    height: 80,
  },
  aiGaugeCircle: {
    width: 80,
    height: 80,
    borderRadius: 40,
    borderWidth: 8,
    borderColor: 'rgba(255,255,255,0.3)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  aiGaugeValue: {
    fontSize: 18,
    fontWeight: '700',
    color: 'white',
  },
  aiStatsList: {
    flex: 1,
  },
  aiStatRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 6,
  },
  aiStatLabel: {
    fontSize: 13,
    color: 'rgba(255,255,255,0.8)',
  },
  aiStatValue: {
    fontSize: 13,
    fontWeight: '600',
    color: 'white',
  },
  aiActionBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    backgroundColor: 'rgba(255,255,255,0.2)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.3)',
    borderRadius: 24,
    paddingVertical: 12,
    paddingHorizontal: 24,
  },
  aiActionBtnText: {
    color: 'white',
    fontSize: 14,
    fontWeight: '500',
  },

  // AI Risk Alert
  aiRiskAlert: {
    backgroundColor: '#fff2e8',
    borderWidth: 1,
    borderColor: '#ffbb96',
    borderRadius: 12,
    padding: 12,
    marginHorizontal: 16,
    marginBottom: 16,
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 12,
  },
  aiRiskIcon: {
    width: 32,
    height: 32,
    backgroundColor: '#fa8c16',
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  aiRiskContent: {
    flex: 1,
  },
  aiRiskTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#d46b08',
    marginBottom: 4,
  },
  aiRiskDesc: {
    fontSize: 12,
    color: '#ad6800',
    lineHeight: 18,
  },
  aiRiskAction: {
    backgroundColor: '#fa8c16',
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  aiRiskActionText: {
    color: 'white',
    fontSize: 12,
    fontWeight: '500',
  },

  // Pending Tasks
  pendingTasksCard: {
    backgroundColor: 'white',
    borderRadius: 12,
    padding: 16,
    marginHorizontal: 16,
    marginBottom: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.04,
    shadowRadius: 8,
    elevation: 2,
  },
  pendingTasksHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
  },
  pendingTasksTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: DISPATCHER_THEME.textPrimary,
  },
  pendingTasksSubtitle: {
    fontSize: 12,
    color: DISPATCHER_THEME.textMuted,
  },
  pendingTasksGrid: {
    flexDirection: 'row',
    gap: 12,
  },
  pendingTaskItem: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 8,
    backgroundColor: '#f8f9fa',
    borderRadius: 12,
  },
  pendingTaskCount: {
    fontSize: 24,
    fontWeight: '700',
    marginBottom: 4,
  },
  pendingTaskLabel: {
    fontSize: 12,
    color: DISPATCHER_THEME.textSecondary,
  },
  danger: {
    color: '#ff4d4f',
  },
  warning: {
    color: '#fa8c16',
  },
  info: {
    color: '#1890ff',
  },

  // Section Header
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    marginBottom: 8,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: DISPATCHER_THEME.textPrimary,
  },
  sectionAction: {
    fontSize: 13,
    color: DISPATCHER_THEME.primary,
  },

  // Workshop Mini List
  workshopMiniList: {
    paddingHorizontal: 16,
    marginBottom: 16,
  },
  workshopMiniCard: {
    backgroundColor: 'white',
    borderRadius: 12,
    padding: 14,
    marginBottom: 10,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.04,
    shadowRadius: 8,
    elevation: 2,
  },
  workshopMiniIndicator: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  indicatorRunning: {
    backgroundColor: '#52c41a',
    shadowColor: 'rgba(82, 196, 26, 0.4)',
    shadowOffset: { width: 0, height: 0 },
    shadowOpacity: 1,
    shadowRadius: 8,
  },
  indicatorIdle: {
    backgroundColor: '#d9d9d9',
  },
  workshopMiniInfo: {
    flex: 1,
  },
  workshopMiniName: {
    fontSize: 14,
    fontWeight: '500',
    color: DISPATCHER_THEME.textPrimary,
  },
  workshopMiniMeta: {
    fontSize: 12,
    color: DISPATCHER_THEME.textMuted,
    marginTop: 2,
  },
  workshopMiniProgress: {
    width: 60,
    alignItems: 'flex-end',
  },
  workshopMiniPercent: {
    fontSize: 16,
    fontWeight: '600',
    color: '#52c41a',
  },
  percentIdle: {
    color: DISPATCHER_THEME.textMuted,
  },

  // Quick Personnel
  quickPersonnel: {
    backgroundColor: 'white',
    borderRadius: 12,
    padding: 16,
    marginHorizontal: 16,
    marginBottom: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.04,
    shadowRadius: 8,
    elevation: 2,
  },
  quickPersonnelHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  quickPersonnelTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: DISPATCHER_THEME.textPrimary,
  },
  quickPersonnelAction: {
    fontSize: 13,
    color: DISPATCHER_THEME.primary,
  },
  quickPersonnelRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  quickPersonnelWorkshop: {
    fontSize: 14,
    color: DISPATCHER_THEME.textPrimary,
  },
  quickPersonnelStats: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  quickPersonnelCount: {
    fontSize: 14,
    fontWeight: '600',
    color: DISPATCHER_THEME.primary,
  },
  quickPersonnelBar: {
    width: 60,
    height: 4,
    backgroundColor: '#f0f0f0',
    borderRadius: 2,
    overflow: 'hidden',
  },
  quickPersonnelBarFill: {
    height: '100%',
    backgroundColor: DISPATCHER_THEME.secondary,
    borderRadius: 2,
  },
  aiOptimizeLink: {
    fontSize: 12,
    color: DISPATCHER_THEME.primary,
  },

  // Approval List
  approvalList: {
    paddingHorizontal: 16,
    marginBottom: 16,
  },
  approvalCard: {
    backgroundColor: 'white',
    borderRadius: 12,
    padding: 12,
    marginBottom: 10,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.04,
    shadowRadius: 8,
    elevation: 2,
  },
  approvalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  approvalType: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 4,
  },
  approvalTypePlan: {
    backgroundColor: '#e6f7ff',
  },
  approvalTypeTransfer: {
    backgroundColor: '#f9f0ff',
  },
  approvalTypeText: {
    fontSize: 12,
    fontWeight: '500',
    color: '#1890ff',
  },
  approvalStatus: {
    backgroundColor: '#fff7e6',
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 4,
  },
  approvalStatusText: {
    fontSize: 11,
    color: '#fa8c16',
    fontWeight: '500',
  },
  approvalContent: {
    fontSize: 14,
    color: DISPATCHER_THEME.textPrimary,
    fontWeight: '500',
    marginBottom: 4,
  },
  approvalMeta: {
    fontSize: 12,
    color: DISPATCHER_THEME.textMuted,
  },
});
