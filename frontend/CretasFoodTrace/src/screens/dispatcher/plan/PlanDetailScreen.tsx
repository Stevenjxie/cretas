/**
 * 生产计划详情屏幕
 *
 * 功能:
 * - 计划基本信息展示
 * - 生产进度显示
 * - 原料匹配状态
 * - 已分配员工列表
 * - 关联生产批次
 * - 暂停/完成计划操作
 *
 * @version 1.0.0
 * @since 2025-12-28
 */

import React, { useState, useCallback, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  Alert,
  Modal,
  TextInput,
  Switch,
  ActivityIndicator,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { LinearGradient } from 'expo-linear-gradient';
import { useNavigation, useRoute } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Ionicons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';
import { isAxiosError } from 'axios';
import { schedulingApiClient } from '../../../services/api/schedulingApiClient';
import { productionPlanApiClient } from '../../../services/api/productionPlanApiClient';
import { processTaskApiClient } from '../../../services/api/processTaskApiClient';
import { safePrint } from '../../../services/api/printApiClient';
import type { DispatcherStackParamList } from '../../../types/dispatcher';

type NavigationProp = NativeStackNavigationProp<DispatcherStackParamList, 'PlanDetail'>;

// 主题颜色
const DISPATCHER_THEME = {
  primary: '#722ed1',
  secondary: '#a18cd1',
  accent: '#fbc2eb',
  success: '#52c41a',
  warning: '#fa8c16',
  danger: '#ff4d4f',
  info: '#1890ff',
  background: '#f5f5f5',
  cardBackground: '#ffffff',
};

// 计划详情类型 (对接 production-plans API)
interface PlanDetail {
  id: string;
  planNumber: string;
  product: string;
  productTypeId: string;
  productUnit: string;
  sourceCustomerName: string;
  quantity: number;
  actualQuantity: number;
  status: string;
  statusDisplay: string;
  priority: number;
  progress: number;
  remainingQuantity: number;
  plannedDate: string;
  expectedCompletion: string;
  planType: string;
  sourceType: string;
  processName: string;
  notes: string;
  createdAt: string;
  createdBy: number | null;
  createdByName: string;
  assignedSupervisorName: string;
  suggestedProductionLineName: string;
}

// 可重排状态列表
const RESCHEDULABLE_STATUSES = ['draft', 'pending', 'confirmed', 'paused'];

// 原料匹配类型
interface MaterialMatch {
  id: string;
  name: string;
  batchNumber: string;
  required: string;
  available: string;
  matched: boolean;
}

// 员工类型
interface AssignedWorker {
  id: string;
  name: string;
  avatar: string;
}

// 生产批次类型
interface ProductionBatch {
  id: string;
  batchNumber: string;
  status: 'pending' | 'in_progress' | 'completed';
  progress: number;
}

// Data transformation helpers
const transformPlanToDetail = (apiPlan: Record<string, unknown> | object): PlanDetail => {
  const plan = apiPlan as Record<string, unknown>;
  const plannedQty = Number(plan.plannedQuantity || 0);
  const actualQty = Number(plan.actualQuantity || 0);
  const progress = plannedQty > 0 ? Math.round((actualQty / plannedQty) * 100) : 0;
  const unit = String(plan.productUnit || 'kg');

  return {
    id: String(plan.id || ''),
    planNumber: String(plan.planNumber || ''),
    product: String(plan.productTypeName || plan.productName || ''),
    productTypeId: String(plan.productTypeId || ''),
    productUnit: unit,
    sourceCustomerName: String(plan.sourceCustomerName || ''),
    quantity: plannedQty,
    actualQuantity: actualQty,
    status: String(plan.status || 'PENDING'),
    statusDisplay: String(plan.statusDisplayName || plan.status || ''),
    priority: Number(plan.priority || 5),
    progress,
    remainingQuantity: Number(plan.remainingQuantity || Math.max(0, plannedQty - actualQty)),
    plannedDate: String(plan.plannedDate || ''),
    expectedCompletion: String(plan.expectedCompletionDate || ''),
    planType: String(plan.planTypeDisplayName || plan.planType || ''),
    sourceType: String(plan.sourceTypeDisplayName || plan.sourceType || ''),
    processName: String(plan.processName || ''),
    notes: String(plan.notes || ''),
    createdAt: String(plan.createdAt || ''),
    createdBy: plan.createdBy == null ? null : Number(plan.createdBy),
    createdByName: String(plan.createdByName || ''),
    assignedSupervisorName: String(plan.assignedSupervisorName || ''),
    suggestedProductionLineName: String(plan.suggestedProductionLineName || ''),
  };
};

const transformMaterials = (apiMaterials: unknown[]): MaterialMatch[] => {
  return (apiMaterials || []).map((item, index) => {
    const m = item as Record<string, unknown>;
    return {
    id: String(m.id || index + 1),
    name: String(m.materialTypeName || m.name || ''),
    batchNumber: String(m.batchNumber || ''),
    required: `${Number(m.requiredQuantity || m.required || 0)}${String(m.unit || 'kg')}`,
    available: `${Number(m.availableQuantity || m.available || 0)}${String(m.unit || 'kg')}`,
    matched: (m.matched as boolean) ?? (Number(m.availableQuantity || 0) >= Number(m.requiredQuantity || 0)),
  };
  });
};

const transformWorkers = (apiWorkers: unknown[]): AssignedWorker[] => {
  return (apiWorkers || []).map((item, index) => {
    const w = item as Record<string, unknown>;
    const name = String(w.name || w.workerName || w.userName || '');
    return {
      id: String(w.id || w.userId || index + 1),
      name,
      avatar: name.charAt(0),
    };
  });
};

const transformBatches = (apiBatches: unknown[]): ProductionBatch[] => {
  return (apiBatches || []).map((item, index) => {
    const b = item as Record<string, unknown>;
    const status = String(b.status || 'pending').toLowerCase();
    return {
      id: String(b.id || index + 1),
      batchNumber: String(b.batchNumber || ''),
      status: status === 'in_progress' ? 'in_progress' :
              status === 'completed' ? 'completed' : 'pending',
      progress: Number(b.progress || b.completionPercent || 0),
    };
  });
};

export default function PlanDetailScreen() {
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute();
  const { t } = useTranslation('dispatcher');
  const params = route.params as { planId?: string; planData?: Record<string, unknown> } | undefined;
  const planId = params?.planId || '';

  // Use pre-passed data for instant render, then refresh from API
  const initialPlan = params?.planData ? transformPlanToDetail(params.planData) : null;

  const [loading, setLoading] = useState(!initialPlan);
  const [refreshing, setRefreshing] = useState(false);
  const [plan, setPlan] = useState<PlanDetail | null>(initialPlan);
  const [materials, setMaterials] = useState<MaterialMatch[]>([]);
  const [workers, setWorkers] = useState<AssignedWorker[]>([]);
  const [batches, setBatches] = useState<ProductionBatch[]>([]);

  // 重排相关状态
  const [showRescheduleModal, setShowRescheduleModal] = useState(false);
  const [rescheduleReason, setRescheduleReason] = useState('');
  const [keepAssignments, setKeepAssignments] = useState(true);
  const [isRescheduling, setIsRescheduling] = useState(false);

  // Load plan data from API
  const loadPlanData = useCallback(async () => {
    if (!planId) {
      setLoading(false);
      return;
    }

    try {
      // Fetch plan details from production-plans
      const planResponse = await productionPlanApiClient.getProductionPlanById(planId);
      if (planResponse.success && planResponse.data) {
        // Cast to extended type that may include additional fields from API
        const planData = planResponse.data as typeof planResponse.data & {
          materials?: unknown[];
          workers?: unknown[];
          assignedWorkers?: unknown[];
          batches?: unknown[];
          schedules?: unknown[];
        };
        setPlan(transformPlanToDetail(planData as unknown as Record<string, unknown>));

        // Extract materials from plan if available
        if (planData.materials) {
          setMaterials(transformMaterials(planData.materials));
        }

        // Extract workers from plan if available
        if (planData.workers || planData.assignedWorkers) {
          setWorkers(transformWorkers(planData.workers || planData.assignedWorkers || []));
        }

        // Extract batches from plan if available
        if (planData.batches || planData.schedules) {
          setBatches(transformBatches(planData.batches || planData.schedules || []));
        }
      }
    } catch (error) {
      if (isAxiosError(error)) {
        const status = error.response?.status;
        if (status === 401) {
          Alert.alert(t('errors.unauthorized'), t('errors.loginExpired'));
        } else if (status === 404) {
          Alert.alert(t('errors.notFound'), t('plan.notFound'));
          navigation.goBack();
        } else {
          Alert.alert(t('errors.loadFailed'), error.response?.data?.message || t('plan.loadError'));
        }
      } else if (error instanceof Error) {
        Alert.alert(t('errors.error'), error.message);
      }
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [planId, navigation, t]);

  // Load data on mount
  React.useEffect(() => {
    loadPlanData();
  }, [loadPlanData]);

  // 判断是否可以重排
  const canReschedule = plan ? RESCHEDULABLE_STATUSES.includes(plan.status) : false;

  // 下拉刷新
  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadPlanData();
  }, [loadPlanData]);

  // 获取状态标签样式
  const getStatusStyle = (status: string) => {
    switch (status?.toUpperCase()) {
      case 'DRAFT': return { backgroundColor: '#f5f5f5', color: '#999' };
      case 'PENDING': case 'PLANNED': return { backgroundColor: '#fff7e6', color: '#fa8c16' };
      case 'CONFIRMED': return { backgroundColor: '#f0f5ff', color: '#597ef7' };
      case 'IN_PROGRESS': return { backgroundColor: '#e6f7ff', color: '#1890ff' };
      case 'COMPLETED': return { backgroundColor: '#f6ffed', color: '#52c41a' };
      case 'PAUSED': return { backgroundColor: '#fff1f0', color: '#ff4d4f' };
      case 'CANCELLED': return { backgroundColor: '#fafafa', color: '#bfbfbf' };
      default: return { backgroundColor: '#f5f5f5', color: '#666' };
    }
  };

  const getStatusText = (status: string) => {
    switch (status?.toUpperCase()) {
      case 'DRAFT': return '草稿';
      case 'PENDING': case 'PLANNED': return '待执行';
      case 'CONFIRMED': return '已确认';
      case 'IN_PROGRESS': return '进行中';
      case 'COMPLETED': return '已完成';
      case 'PAUSED': return '已暂停';
      case 'CANCELLED': return '已取消';
      default: return status;
    }
  };

  // 获取优先级样式
  const getPriorityStyle = (priority: string) => {
    switch (priority) {
      case 'high':
        return { backgroundColor: '#fff1f0', color: '#ff4d4f' };
      case 'medium':
        return { backgroundColor: '#fff7e6', color: '#fa8c16' };
      case 'low':
        return { backgroundColor: '#e6f7ff', color: '#1890ff' };
      default:
        return { backgroundColor: '#f5f5f5', color: '#666' };
    }
  };

  const [tasksGenerated, setTasksGenerated] = useState(false);
  const [generatedRunId, setGeneratedRunId] = useState<string | null>(null);

  // 生成工序任务
  const handleGenerateProcessTasks = () => {
    if (!plan?.productTypeId) {
      Alert.alert('提示', '该计划未关联产品，无法生成工序任务');
      return;
    }
    Alert.alert(
      '生成工序任务',
      `确定为「${plan.product}」生成工序任务？\n系统将根据产品关联的工序自动创建任务。`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '确定生成',
          onPress: async () => {
            try {
              const res = await processTaskApiClient.generateTasksFromProduct({
                productTypeId: plan.productTypeId,
                sourceCustomerName: plan.sourceCustomerName || plan.product,
              });
              const data = res as { success?: boolean; data?: Array<{ productionRunId?: string }>; message?: string };
              if (data?.success && Array.isArray(data.data) && data.data.length > 0) {
                const runId = data.data[0]?.productionRunId;
                setTasksGenerated(true);
                setGeneratedRunId(runId || null);
                Alert.alert('成功', `已生成 ${data.data.length} 个工序任务`);
              } else {
                setTasksGenerated(true);
                Alert.alert('提示', data?.message || '工序任务已生成');
              }
            } catch (error) {
              const msg = error instanceof Error ? error.message : '生成失败';
              Alert.alert('错误', msg);
            }
          },
        },
      ]
    );
  };

  // 取消计划
  const handlePausePlan = () => {
    Alert.alert(
      '取消计划',
      '确定要取消该生产计划吗？取消后不可恢复。',
      [
        { text: '返回', style: 'cancel' },
        {
          text: '确定取消',
          style: 'destructive',
          onPress: async () => {
            try {
              await productionPlanApiClient.cancelProductionPlan(planId, '调度员取消');
              Alert.alert('成功', '计划已取消', [
                { text: '返回列表', onPress: () => navigation.goBack() },
              ]);
            } catch (e) {
              Alert.alert('错误', e instanceof Error ? e.message : '取消失败');
            }
          },
        },
      ]
    );
  };

  // 完成计划
  const handleCompletePlan = () => {
    Alert.alert(
      '完成计划',
      `确定将该计划标记为已完成吗？\n计划量: ${plan?.quantity ?? 0} ${plan?.productUnit ?? 'kg'}`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '确定完成',
          onPress: async () => {
            try {
              await productionPlanApiClient.completeProduction(planId, plan?.quantity ?? 0);
              Alert.alert('成功', '计划已完成');
              loadPlanData();
            } catch (e) {
              Alert.alert('错误', e instanceof Error ? e.message : '完成失败');
            }
          },
        },
      ]
    );
  };

  // 打开重排弹窗
  const openRescheduleModal = () => {
    setRescheduleReason('');
    setKeepAssignments(true);
    setShowRescheduleModal(true);
  };

  // 关闭重排弹窗
  const closeRescheduleModal = () => {
    setShowRescheduleModal(false);
    setRescheduleReason('');
  };

  // 重新排程
  const handleReschedule = async () => {
    if (!plan) {
      Alert.alert('错误', '计划信息不存在');
      return;
    }
    if (!rescheduleReason.trim()) {
      Alert.alert('提示', '请输入重排原因');
      return;
    }

    setIsRescheduling(true);
    try {
      const result = await schedulingApiClient.reschedule({
        planId: plan.id,
        reason: rescheduleReason.trim(),
        keepAssignments,
      });

      if (result.success) {
        Alert.alert('成功', '排程已重新生成', [
          {
            text: '确定',
            onPress: () => {
              closeRescheduleModal();
              onRefresh(); // 刷新页面数据
            },
          },
        ]);
      } else {
        Alert.alert('失败', result.message || '重新排程失败');
      }
    } catch (error) {
      if (isAxiosError(error)) {
        const status = error.response?.status;
        if (status === 401) {
          Alert.alert('错误', '登录已过期，请重新登录');
        } else if (status === 403) {
          Alert.alert('权限不足', '您没有权限执行此操作');
        } else {
          Alert.alert('失败', error.response?.data?.message || '重新排程失败');
        }
      } else if (error instanceof Error) {
        Alert.alert('错误', error.message);
      } else {
        Alert.alert('失败', '重新排程失败，请稍后重试');
      }
    } finally {
      setIsRescheduling(false);
    }
  };

  // 渲染原料匹配卡片
  const renderMaterialCard = (material: MaterialMatch) => (
    <View
      key={material.id}
      style={[
        styles.materialCard,
        material.matched ? styles.materialMatched : styles.materialUnmatched,
      ]}
    >
      <View style={styles.materialHeader}>
        <View style={styles.materialNameRow}>
          <View
            style={[
              styles.materialIndicator,
              { backgroundColor: material.matched ? DISPATCHER_THEME.success : DISPATCHER_THEME.danger },
            ]}
          />
          <Text style={styles.materialName}>{material.name}</Text>
        </View>
        <Text
          style={[
            styles.materialStatus,
            { color: material.matched ? DISPATCHER_THEME.success : DISPATCHER_THEME.danger },
          ]}
        >
          {material.matched ? '已匹配' : '未匹配'}
        </Text>
      </View>
      <Text style={styles.materialBatch}>批次: {material.batchNumber}</Text>
      <Text style={styles.materialQuantity}>
        需求: {material.required} | 可用: {material.available}
      </Text>
    </View>
  );

  // 渲染生产批次卡片
  const renderBatchCard = (batch: ProductionBatch) => (
    <TouchableOpacity key={batch.id} style={styles.batchCard} activeOpacity={0.7}>
      <View style={styles.batchHeader}>
        <Text style={styles.batchNumber}>{batch.batchNumber}</Text>
        <View
          style={[
            styles.batchStatus,
            getStatusStyle(batch.status),
          ]}
        >
          <Text style={[styles.batchStatusText, { color: getStatusStyle(batch.status).color }]}>
            {batch.status === 'in_progress' ? '生产中' : batch.status === 'completed' ? '已完成' : '待开始'}
          </Text>
        </View>
      </View>
      <Text style={styles.batchProgress}>进度 {batch.progress}%</Text>
    </TouchableOpacity>
  );

  // Loading state
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <View style={styles.header}>
          <TouchableOpacity
            style={styles.backButton}
            onPress={() => navigation.goBack()}
          >
            <Ionicons name="chevron-back" size={24} color="#333" />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>计划详情</Text>
          <View style={styles.editButton} />
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color={DISPATCHER_THEME.primary} />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  // No plan found
  if (!plan) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <View style={styles.header}>
          <TouchableOpacity
            style={styles.backButton}
            onPress={() => navigation.goBack()}
          >
            <Ionicons name="chevron-back" size={24} color="#333" />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>计划详情</Text>
          <View style={styles.editButton} />
        </View>
        <View style={styles.emptyContainer}>
          <Ionicons name="document-outline" size={64} color="#ccc" />
          <Text style={styles.emptyText}>计划不存在或已被删除</Text>
          <TouchableOpacity style={styles.retryButton} onPress={loadPlanData}>
            <Text style={styles.retryButtonText}>重试</Text>
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.backButton}
          onPress={() => navigation.goBack()}
        >
          <Ionicons name="chevron-back" size={24} color="#333" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>计划详情</Text>
        <View style={{ width: 40 }} />
      </View>

      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            colors={[DISPATCHER_THEME.primary]}
            tintColor={DISPATCHER_THEME.primary}
          />
        }
        showsVerticalScrollIndicator={false}
      >
        {/* 计划信息 */}
        <View style={styles.section}>
          <View style={styles.planHeader}>
            <Text style={styles.planNumber}>{plan.planNumber}</Text>
            <View style={[styles.statusBadge, getStatusStyle(plan.status)]}>
              <Text style={[styles.statusText, { color: getStatusStyle(plan.status).color }]}>
                {getStatusText(plan.status)}
              </Text>
            </View>
          </View>

          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>产品</Text>
            <Text style={styles.detailValue}>{plan.product}</Text>
          </View>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>客户</Text>
            <Text style={styles.detailValue}>{plan.sourceCustomerName || '-'}</Text>
          </View>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>计划数量</Text>
            <Text style={styles.detailValue}>{plan.quantity} {plan.productUnit}</Text>
          </View>
          {plan.processName ? (
            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>工序</Text>
              <Text style={styles.detailValue}>{plan.processName}</Text>
            </View>
          ) : null}
          {plan.plannedDate ? (
            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>计划日期</Text>
              <Text style={styles.detailValue}>{plan.plannedDate}</Text>
            </View>
          ) : null}
          {plan.expectedCompletion ? (
            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>预计完成</Text>
              <Text style={styles.detailValue}>{plan.expectedCompletion}</Text>
            </View>
          ) : null}
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>来源</Text>
            <Text style={styles.detailValue}>{plan.sourceType || '手动创建'}</Text>
          </View>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>优先级</Text>
            <Text style={styles.detailValue}>{plan.priority >= 8 ? '高' : plan.priority >= 5 ? '中' : '低'}</Text>
          </View>
          {plan.createdAt ? (
            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>创建时间</Text>
              <Text style={styles.detailValue}>{plan.createdAt.replace('T', ' ').substring(0, 16)}</Text>
            </View>
          ) : null}
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>创建人</Text>
            <Text style={styles.detailValue}>{plan.createdByName || `用户#${plan.createdBy ?? '-'}`}</Text>
          </View>
          {plan.assignedSupervisorName ? (
            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>主管</Text>
              <Text style={styles.detailValue}>{plan.assignedSupervisorName}</Text>
            </View>
          ) : null}
          {plan.suggestedProductionLineName ? (
            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>产线</Text>
              <Text style={styles.detailValue}>{plan.suggestedProductionLineName}</Text>
            </View>
          ) : null}
        </View>

        {/* 生产进度 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>生产进度</Text>
          <View style={styles.progressInfo}>
            <Text style={styles.progressLabel}>完成进度</Text>
            <Text style={styles.progressValue}>{plan.progress}%</Text>
          </View>
          <View style={styles.progressBar}>
            <LinearGradient
              colors={[DISPATCHER_THEME.secondary, DISPATCHER_THEME.accent]}
              start={{ x: 0, y: 0 }}
              end={{ x: 1, y: 0 }}
              style={[styles.progressFill, { width: `${plan.progress}%` }]}
            />
          </View>
          <View style={styles.quantityRow}>
            <Text style={styles.quantityText}>
              已完成: <Text style={styles.quantityValue}>{plan.actualQuantity} {plan.productUnit}</Text>
            </Text>
            <Text style={styles.quantityText}>
              剩余: <Text style={styles.quantityValue}>{plan.remainingQuantity} {plan.productUnit}</Text>
            </Text>
          </View>
          {plan.expectedCompletion ? (
            <Text style={styles.estimatedCompletion}>
              预计完成: {plan.expectedCompletion}
            </Text>
          ) : null}
        </View>

        {/* 原料匹配 (仅活跃计划显示) */}
        {plan.status?.toUpperCase() !== 'CANCELLED' && plan.status?.toUpperCase() !== 'COMPLETED' && materials.length > 0 && (
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>原料匹配</Text>
          </View>
          {materials.map(renderMaterialCard)}
        </View>
        )}

        {/* 已分配员工 (仅活跃计划显示) */}
        {plan.status?.toUpperCase() !== 'CANCELLED' && plan.status?.toUpperCase() !== 'COMPLETED' && (
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>已分配员工 ({workers.length}人)</Text>
          </View>
          {workers.length > 0 ? (
            <View style={styles.workersContainer}>
              <View style={styles.avatarGroup}>
                {workers.map((worker) => (
                  <View key={worker.id} style={styles.avatar}>
                    <Text style={styles.avatarText}>{worker.avatar}</Text>
                  </View>
                ))}
              </View>
            </View>
          ) : (
            <Text style={{ color: '#999', textAlign: 'center', paddingVertical: 12 }}>暂未分配员工</Text>
          )}
        </View>
        )}

        {/* 关联生产批次 */}
        <View style={[styles.section, { marginBottom: 120 }]}>
          <Text style={styles.sectionTitle}>关联生产批次</Text>
          {batches.map(renderBatchCard)}
        </View>
      </ScrollView>

      {/* 底部操作栏 */}
      <View style={styles.bottomActions}>
        {plan?.productTypeId && plan?.status?.toUpperCase() !== 'CANCELLED' && plan?.status?.toUpperCase() !== 'COMPLETED' ? (
          tasksGenerated && generatedRunId ? (
            <TouchableOpacity
              style={styles.rescheduleButton}
              onPress={() => {
                console.log('[PlanDetail] Navigate to ProcessRunOverview, runId:', generatedRunId);
                if (generatedRunId) {
                  navigation.navigate('ProcessRunOverview', { productionRunId: generatedRunId });
                } else {
                  Alert.alert('提示', '未找到工序任务ID，请重新生成');
                }
              }}
            >
              <Ionicons name="checkmark-circle" size={16} color={DISPATCHER_THEME.primary} />
              <Text style={[styles.rescheduleButtonText, { color: DISPATCHER_THEME.primary }]}>查看工序任务</Text>
            </TouchableOpacity>
          ) : (
            <TouchableOpacity
              style={styles.rescheduleButton}
              onPress={handleGenerateProcessTasks}
            >
              <Ionicons name="list" size={16} color={DISPATCHER_THEME.success} />
              <Text style={[styles.rescheduleButtonText, { color: DISPATCHER_THEME.success }]}>生成工序任务</Text>
            </TouchableOpacity>
          )
        ) : null}
        {plan?.id ? (
          <TouchableOpacity
            style={styles.rescheduleButton}
            onPress={() => { void safePrint('production-work-order', plan.id); }}
          >
            <Ionicons name="print-outline" size={16} color={DISPATCHER_THEME.primary} />
            <Text style={[styles.rescheduleButtonText, { color: DISPATCHER_THEME.primary }]}>打印工单</Text>
          </TouchableOpacity>
        ) : null}
        {canReschedule && (
          <TouchableOpacity
            style={styles.rescheduleButton}
            onPress={openRescheduleModal}
          >
            <Ionicons name="refresh" size={16} color={DISPATCHER_THEME.info} />
            <Text style={styles.rescheduleButtonText}>重新排程</Text>
          </TouchableOpacity>
        )}
        {plan?.status?.toUpperCase() !== 'CANCELLED' && plan?.status?.toUpperCase() !== 'COMPLETED' && (
          <TouchableOpacity
            style={styles.secondaryButton}
            onPress={handlePausePlan}
          >
            <Text style={styles.secondaryButtonText}>取消计划</Text>
          </TouchableOpacity>
        )}
      </View>

      {/* 重排确认弹窗 */}
      <Modal
        visible={showRescheduleModal}
        transparent
        animationType="fade"
        onRequestClose={closeRescheduleModal}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            {/* 弹窗标题 */}
            <View style={styles.modalHeader}>
              <View style={styles.modalTitleRow}>
                <Ionicons name="refresh" size={20} color={DISPATCHER_THEME.primary} />
                <Text style={styles.modalTitle}>重新排程</Text>
              </View>
              <TouchableOpacity onPress={closeRescheduleModal} disabled={isRescheduling}>
                <Ionicons name="close" size={24} color="#999" />
              </TouchableOpacity>
            </View>

            {/* 提示信息 */}
            <Text style={styles.modalHint}>
              重新排程将重新计算生产安排，请说明原因以便追溯。
            </Text>

            {/* 原因输入 */}
            <Text style={styles.inputLabel}>重排原因 <Text style={styles.required}>*</Text></Text>
            <TextInput
              style={styles.reasonInput}
              placeholder="请输入重排原因（如：紧急订单插入、工人变动等）"
              placeholderTextColor="#999"
              value={rescheduleReason}
              onChangeText={setRescheduleReason}
              multiline
              numberOfLines={3}
              textAlignVertical="top"
              editable={!isRescheduling}
            />

            {/* 保留分配选项 */}
            <View style={styles.switchRow}>
              <View style={styles.switchLabel}>
                <Text style={styles.switchTitle}>保留现有工人分配</Text>
                <Text style={styles.switchDesc}>关闭后将重新分配所有工人</Text>
              </View>
              <Switch
                value={keepAssignments}
                onValueChange={setKeepAssignments}
                trackColor={{ false: '#e0e0e0', true: DISPATCHER_THEME.secondary }}
                thumbColor={keepAssignments ? DISPATCHER_THEME.primary : '#f4f3f4'}
                disabled={isRescheduling}
              />
            </View>

            {/* 操作按钮 */}
            <View style={styles.modalActions}>
              <TouchableOpacity
                style={styles.modalCancelButton}
                onPress={closeRescheduleModal}
                disabled={isRescheduling}
              >
                <Text style={styles.modalCancelText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[
                  styles.modalConfirmButton,
                  !rescheduleReason.trim() && styles.modalConfirmButtonDisabled,
                ]}
                onPress={handleReschedule}
                disabled={!rescheduleReason.trim() || isRescheduling}
              >
                {isRescheduling ? (
                  <ActivityIndicator size="small" color="#fff" />
                ) : (
                  <Text style={styles.modalConfirmText}>确认重排</Text>
                )}
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
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
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: '#666',
  },
  emptyContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 24,
  },
  emptyText: {
    marginTop: 16,
    fontSize: 16,
    color: '#999',
    textAlign: 'center',
  },
  retryButton: {
    marginTop: 20,
    paddingVertical: 10,
    paddingHorizontal: 24,
    backgroundColor: DISPATCHER_THEME.primary,
    borderRadius: 8,
  },
  retryButtonText: {
    fontSize: 14,
    fontWeight: '500',
    color: '#fff',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#e0e0e0',
  },
  backButton: {
    padding: 4,
  },
  headerTitle: {
    fontSize: 17,
    fontWeight: '600',
    color: '#333',
  },
  editButton: {
    padding: 4,
  },
  editButtonText: {
    fontSize: 14,
    color: DISPATCHER_THEME.secondary,
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    paddingBottom: 20,
  },
  section: {
    backgroundColor: '#fff',
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 12,
    padding: 16,
  },
  planHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
  },
  planNumber: {
    fontSize: 18,
    fontWeight: '600',
    color: '#333',
  },
  statusBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 4,
  },
  statusText: {
    fontSize: 12,
    fontWeight: '500',
  },
  detailRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#f0f0f0',
  },
  detailLabel: {
    fontSize: 14,
    color: '#999',
  },
  detailValue: {
    fontSize: 14,
    color: '#333',
    fontWeight: '500',
  },
  priorityBadge: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  priorityText: {
    fontSize: 12,
    fontWeight: '500',
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
    marginBottom: 12,
  },
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  sectionLink: {
    fontSize: 12,
    color: DISPATCHER_THEME.secondary,
  },
  progressInfo: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  progressLabel: {
    fontSize: 14,
    color: '#666',
  },
  progressValue: {
    fontSize: 16,
    fontWeight: '600',
    color: DISPATCHER_THEME.secondary,
  },
  progressBar: {
    height: 10,
    backgroundColor: '#f0f0f0',
    borderRadius: 5,
    overflow: 'hidden',
    marginBottom: 12,
  },
  progressFill: {
    height: '100%',
    borderRadius: 5,
  },
  quantityRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  quantityText: {
    fontSize: 14,
    color: '#999',
  },
  quantityValue: {
    color: '#333',
    fontWeight: '500',
  },
  estimatedCompletion: {
    fontSize: 13,
    color: DISPATCHER_THEME.success,
  },
  materialCard: {
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
    borderWidth: 1,
  },
  materialMatched: {
    backgroundColor: '#f6ffed',
    borderColor: '#b7eb8f',
  },
  materialUnmatched: {
    backgroundColor: '#fff1f0',
    borderColor: '#ffccc7',
  },
  materialHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 6,
  },
  materialNameRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  materialIndicator: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  materialName: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
  },
  materialStatus: {
    fontSize: 12,
  },
  materialBatch: {
    fontSize: 13,
    color: '#666',
    marginBottom: 2,
  },
  materialQuantity: {
    fontSize: 13,
    color: '#666',
  },
  workersContainer: {
    marginBottom: 12,
  },
  avatarGroup: {
    flexDirection: 'row',
    gap: 8,
  },
  avatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: DISPATCHER_THEME.secondary,
    justifyContent: 'center',
    alignItems: 'center',
  },
  avatarText: {
    fontSize: 16,
    fontWeight: '500',
    color: '#fff',
  },
  workerStats: {
    flexDirection: 'row',
    gap: 24,
  },
  workerStatText: {
    fontSize: 13,
    color: '#999',
  },
  workerStatValue: {
    color: '#333',
    fontWeight: '500',
  },
  batchCard: {
    backgroundColor: '#fafafa',
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
  },
  batchHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  batchNumber: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
  },
  batchStatus: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  batchStatusText: {
    fontSize: 11,
    fontWeight: '500',
  },
  batchProgress: {
    fontSize: 13,
    color: '#999',
    marginTop: 4,
  },
  bottomActions: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    gap: 12,
    padding: 16,
    backgroundColor: '#fff',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#e0e0e0',
    paddingBottom: 34,
  },
  secondaryButton: {
    flex: 1,
    paddingVertical: 14,
    backgroundColor: '#f5f5f5',
    borderRadius: 8,
    alignItems: 'center',
  },
  secondaryButtonText: {
    fontSize: 15,
    fontWeight: '500',
    color: '#666',
  },
  primaryButton: {
    flex: 1,
    borderRadius: 8,
    overflow: 'hidden',
  },
  primaryButtonGradient: {
    paddingVertical: 14,
    alignItems: 'center',
  },
  primaryButtonText: {
    fontSize: 15,
    fontWeight: '500',
    color: '#fff',
  },
  // 重排按钮样式
  rescheduleButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 4,
    paddingVertical: 14,
    paddingHorizontal: 12,
    backgroundColor: '#e6f7ff',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#91d5ff',
  },
  rescheduleButtonText: {
    fontSize: 14,
    fontWeight: '500',
    color: DISPATCHER_THEME.info,
  },
  smallerButton: {
    flex: 0.8,
  },
  // 弹窗样式
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  modalContent: {
    width: '90%',
    maxWidth: 400,
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 20,
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  modalTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  modalTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#333',
  },
  modalHint: {
    fontSize: 13,
    color: '#666',
    marginBottom: 16,
    lineHeight: 20,
  },
  inputLabel: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
    marginBottom: 8,
  },
  required: {
    color: DISPATCHER_THEME.danger,
  },
  reasonInput: {
    borderWidth: 1,
    borderColor: '#e0e0e0',
    borderRadius: 8,
    padding: 12,
    fontSize: 14,
    color: '#333',
    minHeight: 80,
    marginBottom: 16,
  },
  switchRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 12,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#f0f0f0',
    marginBottom: 16,
  },
  switchLabel: {
    flex: 1,
    marginRight: 12,
  },
  switchTitle: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
    marginBottom: 2,
  },
  switchDesc: {
    fontSize: 12,
    color: '#999',
  },
  modalActions: {
    flexDirection: 'row',
    gap: 12,
  },
  modalCancelButton: {
    flex: 1,
    paddingVertical: 12,
    backgroundColor: '#f5f5f5',
    borderRadius: 8,
    alignItems: 'center',
  },
  modalCancelText: {
    fontSize: 15,
    fontWeight: '500',
    color: '#666',
  },
  modalConfirmButton: {
    flex: 1,
    paddingVertical: 12,
    backgroundColor: DISPATCHER_THEME.primary,
    borderRadius: 8,
    alignItems: 'center',
  },
  modalConfirmButtonDisabled: {
    backgroundColor: '#d9d9d9',
  },
  modalConfirmText: {
    fontSize: 15,
    fontWeight: '500',
    color: '#fff',
  },
});
