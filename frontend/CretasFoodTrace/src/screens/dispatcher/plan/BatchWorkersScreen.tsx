/**
 * 批次人员分配屏幕
 *
 * 功能:
 * - 批次基本信息展示
 * - 已分配员工列表
 * - 可用员工列表 (分组显示: 本车间/机动/可调动)
 * - 员工搜索 (工号/姓名)
 * - 多选分配功能
 * - 临时工特殊标记
 *
 * @version 1.0.0
 * @since 2025-12-28
 */

import React, { useState, useCallback, useMemo, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  TextInput,
  Alert,
  ActivityIndicator,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { LinearGradient } from 'expo-linear-gradient';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { Ionicons } from '@expo/vector-icons';
import { isAxiosError } from 'axios';
import { schedulingApiClient } from '../../../services/api/schedulingApiClient';
import { processingApiClient } from '../../../services/api/processingApiClient';
import { useAuthStore } from '../../../store/authStore';

// 调度员主题色
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

// 类型定义
interface BatchInfo {
  id: string;
  batchNumber: string;
  product: string;
  quantity: string;
  workshop: string;
  suggestedWorkers: string;
  estimatedHours: number;
  status: 'pending' | 'in_progress';
}

interface Worker {
  id: string;
  name: string;
  avatar: string;
  employeeCode: string;
  status: 'idle' | 'working' | 'available';
  efficiency: number;
  skill: string;
  skillLevel: number;
  weeklyHours: number;
  maxWeeklyHours: number;
  workshop?: string;
  currentTask?: string;
  isTemporary?: boolean;
  contractDaysLeft?: number;
  group: 'workshop_idle' | 'workshop_working' | 'mobile' | 'transferable';
}

// Data transformation helpers
const transformBatchInfo = (apiData: Record<string, unknown>): BatchInfo => ({
  id: String(apiData.id || ''),
  batchNumber: String(apiData.batchNumber || ''),
  product: String(apiData.productTypeName || apiData.productName || ''),
  quantity: `${Number(apiData.plannedQuantity || 0)}kg`,
  workshop: String(apiData.workshopName || '生产车间'),
  suggestedWorkers: String(apiData.suggestedWorkers || apiData.recommendedWorkerCount || '4-6'),
  estimatedHours: Number(apiData.estimatedHours || apiData.estimatedDuration || 6),
  status: String(apiData.status || 'pending').toLowerCase() === 'in_progress' ? 'in_progress' : 'pending',
});

const transformWorkers = (apiWorkers: Record<string, unknown>[]): Worker[] => {
  return (apiWorkers || []).map((w, index) => {
    // Determine worker group based on status and attributes
    let group: Worker['group'] = 'workshop_idle';
    const status = String(w.status || 'idle').toLowerCase();

    const name = String(w.name || w.workerName || w.userName || w.fullName || w.username || '');

    if (w.isTransferable || w.workshop !== w.currentWorkshop) {
      group = 'transferable';
    } else if (w.isMobile || w.employeeType === 'mobile') {
      group = 'mobile';
    } else if (status === 'working' || status === 'busy') {
      group = 'workshop_working';
    }

    return {
      id: String(w.id || w.userId || index + 1),
      name,
      avatar: name.charAt(0),
      employeeCode: String(w.employeeCode || w.employeeNumber || w.username || ''),
      status: status === 'working' || status === 'busy' ? 'working' :
              status === 'available' ? 'available' : 'idle',
      efficiency: Number(w.efficiency || w.efficiencyRate || 85),
      skill: String(w.skill || w.skillName || '通用'),
      skillLevel: Number(w.skillLevel || 2),
      weeklyHours: Number(w.weeklyHours || w.currentWeekHours || 0),
      maxWeeklyHours: Number(w.maxWeeklyHours || 40),
      workshop: w.workshop ? String(w.workshop) : w.workshopName ? String(w.workshopName) : undefined,
      currentTask: w.currentTask ? String(w.currentTask) : w.currentBatchNumber ? String(w.currentBatchNumber) : undefined,
      isTemporary: Boolean(w.isTemporary) || w.employeeType === 'temporary',
      contractDaysLeft: w.contractDaysLeft ? Number(w.contractDaysLeft) : w.contractRemaining ? Number(w.contractRemaining) : undefined,
      group,
    };
  });
};

export default function BatchWorkersScreen() {
  const navigation = useNavigation();
  const route = useRoute();
  const params = route.params as { batchId?: string; scheduleId?: string } | undefined;
  const batchId = params?.batchId || params?.scheduleId || '';
  const scheduleId = params?.scheduleId;
  const assignedBy = useAuthStore((state) => state.getUserId());

  const [loading, setLoading] = useState(true);
  const [assigning, setAssigning] = useState(false);
  const [batchInfo, setBatchInfo] = useState<BatchInfo | null>(null);
  const [workers, setWorkers] = useState<Worker[]>([]);
  const [selectedWorkerIds, setSelectedWorkerIds] = useState<string[]>([]);
  const [searchText, setSearchText] = useState('');
  const [showMoreTransferable, setShowMoreTransferable] = useState(false);

  // Load batch info + workers data from API
  const loadBatchWorkers = useCallback(async () => {
    if (!batchId) {
      setLoading(false);
      return;
    }

    try {
      // Fetch batch details and workers in parallel
      const [batchResponse, availableWorkersResponse, batchWorkersResponse] = await Promise.all([
        processingApiClient.getBatchById(batchId).catch(() => null),
        schedulingApiClient.getAvailableWorkers().catch(() => null),
        schedulingApiClient.getBatchWorkers(batchId).catch(() => ({ content: [], totalElements: 0 })),
      ]);

      // Set batch info from processing API
      if (batchResponse?.success && batchResponse.data) {
        const b = batchResponse.data;
        setBatchInfo(transformBatchInfo({
          id: b.id,
          batchNumber: b.batchNumber,
          productTypeName: b.productType,
          plannedQuantity: b.targetQuantity,
          status: b.status,
        }));
      } else {
        // Fallback: create minimal batch info from batchId so the page is usable
        setBatchInfo({
          id: batchId,
          batchNumber: batchId,
          product: '加载中...',
          quantity: '-',
          workshop: '生产车间',
          suggestedWorkers: '4-6',
          estimatedHours: 6,
          status: 'pending',
        });
      }

      const availableWorkers = availableWorkersResponse?.success && Array.isArray(availableWorkersResponse.data)
        ? availableWorkersResponse.data
        : [];

      if (availableWorkers.length > 0) {
        setWorkers(transformWorkers(availableWorkers as unknown as Record<string, unknown>[]));
      } else if (batchWorkersResponse.content && batchWorkersResponse.content.length > 0) {
        const workersList = batchWorkersResponse.content.map((w: {
          id: number;
          userId: number;
          userName: string;
          workType: string;
          workMinutes: number;
          status: string;
        }) => ({
          id: w.userId || w.id,
          name: w.userName,
          workerName: w.userName,
          status: w.status,
          departmentName: w.workType,
          workMinutes: w.workMinutes,
        }));
        setWorkers(transformWorkers(workersList));
      }
    } catch (error) {
      if (isAxiosError(error)) {
        const status = error.response?.status;
        if (status === 401) {
          Alert.alert('会话过期', '请重新登录');
        } else if (status === 404) {
          Alert.alert('未找到', '批次不存在或已被删除');
          navigation.goBack();
        } else {
          Alert.alert('加载失败', error.response?.data?.message || '无法加载员工数据');
        }
      } else if (error instanceof Error) {
        Alert.alert('错误', error.message);
      }
    } finally {
      setLoading(false);
    }
  }, [batchId, navigation]);

  // Load data on mount
  useEffect(() => {
    loadBatchWorkers();
  }, [loadBatchWorkers]);

  const handleGoBack = () => {
    navigation.goBack();
  };

  const toggleWorkerSelection = (workerId: string, worker: Worker) => {
    if (worker.status === 'working') {
      Alert.alert('无法选择', '该员工正在执行其他任务');
      return;
    }

    setSelectedWorkerIds(prev => {
      if (prev.includes(workerId)) {
        return prev.filter(id => id !== workerId);
      } else {
        return [...prev, workerId];
      }
    });
  };

  const handleSelectAll = () => {
    const selectableIds = workers
      .filter(w => w.status !== 'working')
      .map(w => w.id);
    setSelectedWorkerIds(selectableIds);
  };

  const handleClearSelection = () => {
    setSelectedWorkerIds([]);
  };

  const handleConfirm = () => {
    if (!batchInfo || assigning) return;
    const selectedCount = selectedWorkerIds.length;
    if (selectedCount === 0) {
      Alert.alert('请选择员工', '至少选择 1 名员工后才能确认派工。');
      return;
    }
    const parts = batchInfo.suggestedWorkers.split('-').map(Number);
    const min = parts[0] ?? 0;

    if (selectedCount < min) {
      Alert.alert(
        '人数不足',
        `AI建议至少 ${min} 人，当前只选择了 ${selectedCount} 人`,
        [
          { text: '继续选择', style: 'cancel' },
          { text: '强制确认', onPress: () => confirmAssignment() },
        ]
      );
    } else {
      confirmAssignment();
    }
  };

  const confirmAssignment = async () => {
    if (!batchInfo || assigning) return;

    const workerIds = selectedWorkerIds
      .map(id => Number(id))
      .filter(Number.isFinite);

    if (workerIds.length !== selectedWorkerIds.length) {
      Alert.alert('无法提交', '存在无效员工编号，请刷新后重新选择。');
      return;
    }

    setAssigning(true);
    try {
      if (params?.batchId) {
        await processingApiClient.assignWorkersToBatch(params.batchId, {
          workerIds,
          assignedBy: assignedBy ?? undefined,
          notes: 'RN移动端派工确认',
        });
      } else if (scheduleId) {
        await schedulingApiClient.assignWorkers({
          scheduleId,
          workerIds,
          notes: 'RN移动端排程派工确认',
        });
      } else {
        throw new Error('缺少批次或排程编号，无法提交派工。');
      }

      await loadBatchWorkers();
      Alert.alert(
        '分配成功',
        `已为批次 ${batchInfo.batchNumber} 分配 ${workerIds.length} 名员工`,
        [{ text: '确定', onPress: () => navigation.goBack() }]
      );
    } catch (error) {
      if (isAxiosError(error)) {
        Alert.alert('分配失败', error.response?.data?.message || '后端未接受本次派工，请检查权限、员工状态或批次状态。');
      } else if (error instanceof Error) {
        Alert.alert('分配失败', error.message);
      } else {
        Alert.alert('分配失败', '未知错误，请稍后重试。');
      }
    } finally {
      setAssigning(false);
    }
  };

  // 过滤员工
  const filteredWorkers = useMemo(() => {
    if (!searchText.trim()) return workers;
    const search = searchText.toLowerCase().trim();
    return workers.filter(
      w =>
        w.name.toLowerCase().includes(search) ||
        w.employeeCode.toLowerCase().includes(search)
    );
  }, [workers, searchText]);

  // 分组员工
  const groupedWorkers = useMemo(() => {
    const groups = {
      workshop_idle: [] as Worker[],
      workshop_working: [] as Worker[],
      mobile: [] as Worker[],
      transferable: [] as Worker[],
    };

    filteredWorkers.forEach(worker => {
      const groupKey = worker.group as keyof typeof groups;
      if (groups[groupKey]) {
        groups[groupKey].push(worker);
      }
    });

    return groups;
  }, [filteredWorkers]);

  // 临时工数量
  const temporaryCount = useMemo(() => {
    return groupedWorkers.mobile.filter(w => w.isTemporary).length;
  }, [groupedWorkers]);

  const getMatchStatus = () => {
    const count = selectedWorkerIds.length;
    if (!batchInfo) {
      return { text: '未知', color: DISPATCHER_THEME.info };
    }
    const parts = batchInfo.suggestedWorkers.split('-').map(Number);
    const min = parts[0] ?? 0;
    const max = parts[1] ?? min;

    if (count >= min && count <= max) {
      return { text: '人数匹配', color: DISPATCHER_THEME.success };
    } else if (count < min) {
      return { text: '人数不足', color: DISPATCHER_THEME.danger };
    } else {
      return { text: '人数超额', color: DISPATCHER_THEME.warning };
    }
  };

  const renderWorkerItem = (worker: Worker) => {
    const isSelected = selectedWorkerIds.includes(worker.id);
    const isWorking = worker.status === 'working';
    const isTemporary = worker.isTemporary;

    return (
      <TouchableOpacity
        key={worker.id}
        style={[
          styles.workerItem,
          isSelected && styles.workerItemSelected,
          isWorking && styles.workerItemDisabled,
          isTemporary && styles.workerItemTemporary,
        ]}
        onPress={() => toggleWorkerSelection(worker.id, worker)}
        disabled={isWorking}
      >
        {/* 复选框 */}
        <View
          style={[
            styles.checkbox,
            isSelected && styles.checkboxSelected,
            isWorking && styles.checkboxDisabled,
          ]}
        >
          {isSelected && (
            <Ionicons name="checkmark" size={14} color="#fff" />
          )}
        </View>

        {/* 头像 */}
        <View
          style={[
            styles.avatar,
            isTemporary && styles.avatarTemporary,
            worker.group === 'mobile' && !isTemporary && styles.avatarMobile,
          ]}
        >
          <Text style={styles.avatarText}>{worker.avatar}</Text>
        </View>

        {/* 员工信息 */}
        <View style={styles.workerInfo}>
          <View style={styles.workerNameRow}>
            <Text style={[styles.workerName, isWorking && styles.textDisabled]}>
              {worker.name}
            </Text>
            <Text style={[styles.workerCode, isTemporary && { color: DISPATCHER_THEME.warning }]}>
              ({worker.employeeCode}){isTemporary ? '*' : ''}
            </Text>
            {isTemporary && (
              <View style={styles.tempBadge}>
                <Text style={styles.tempBadgeText}>临时</Text>
              </View>
            )}
          </View>
          <View style={styles.workerStatusRow}>
            {isWorking ? (
              <>
                <Text style={[styles.statusText, { color: DISPATCHER_THEME.info }]}>工作中</Text>
                <Text style={styles.taskText}>{worker.currentTask}</Text>
              </>
            ) : worker.group === 'transferable' ? (
              <>
                <Text style={[styles.statusText, { color: '#999' }]}>{worker.workshop}</Text>
                <Text style={[styles.statusText, { color: DISPATCHER_THEME.success }]}>可调动</Text>
              </>
            ) : (
              <>
                <Text style={[styles.statusText, { color: DISPATCHER_THEME.success }]}>空闲</Text>
                {isTemporary ? (
                  <Text style={[styles.statusText, { color: DISPATCHER_THEME.warning }]}>
                    合同剩余{worker.contractDaysLeft}天
                  </Text>
                ) : (
                  <Text style={styles.efficiencyText}>效率 {worker.efficiency}%</Text>
                )}
              </>
            )}
          </View>
        </View>

        {/* 技能和工时 */}
        <View style={styles.workerMeta}>
          <View
            style={[
              styles.skillBadge,
              worker.group === 'mobile' && styles.skillBadgeMobile,
              isWorking && styles.skillBadgeDisabled,
            ]}
          >
            <Text
              style={[
                styles.skillText,
                worker.group === 'mobile' && styles.skillTextMobile,
                isWorking && styles.skillTextDisabled,
              ]}
            >
              {worker.skill}{' '}
              {worker.skillLevel >= 3 && '★'}Lv.{worker.skillLevel}
            </Text>
          </View>
          {isWorking ? (
            <Text style={styles.unavailableText}>不可用</Text>
          ) : worker.group !== 'transferable' ? (
            <Text style={styles.hoursText}>
              周工时: {worker.weeklyHours}/{worker.maxWeeklyHours}h
            </Text>
          ) : (
            <Text style={styles.hoursText}>效率 {worker.efficiency}%</Text>
          )}
        </View>
      </TouchableOpacity>
    );
  };

  const matchStatus = getMatchStatus();

  // Loading state
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <LinearGradient
          colors={[DISPATCHER_THEME.primary, DISPATCHER_THEME.secondary]}
          start={{ x: 0, y: 0 }}
          end={{ x: 1, y: 1 }}
          style={styles.header}
        >
          <TouchableOpacity style={styles.backButton} onPress={handleGoBack}>
            <Ionicons name="arrow-back" size={24} color="#fff" />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>人员分配</Text>
          <View style={{ width: 40 }} />
        </LinearGradient>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color={DISPATCHER_THEME.primary} />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  // Empty state - no batch info
  if (!batchInfo) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <LinearGradient
          colors={[DISPATCHER_THEME.primary, DISPATCHER_THEME.secondary]}
          start={{ x: 0, y: 0 }}
          end={{ x: 1, y: 1 }}
          style={styles.header}
        >
          <TouchableOpacity style={styles.backButton} onPress={handleGoBack}>
            <Ionicons name="arrow-back" size={24} color="#fff" />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>人员分配</Text>
          <View style={{ width: 40 }} />
        </LinearGradient>
        <View style={styles.emptyStateContainer}>
          <Ionicons name="people-outline" size={64} color="#ccc" />
          <Text style={styles.emptyStateText}>批次信息不存在</Text>
          <TouchableOpacity style={styles.retryButton} onPress={loadBatchWorkers}>
            <Text style={styles.retryButtonText}>重试</Text>
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* 头部 */}
      <LinearGradient
        colors={[DISPATCHER_THEME.primary, DISPATCHER_THEME.secondary]}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 1 }}
        style={styles.header}
      >
        <TouchableOpacity style={styles.backButton} onPress={handleGoBack}>
          <Ionicons name="arrow-back" size={24} color="#fff" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>人员分配</Text>
        <TouchableOpacity onPress={handleConfirm} disabled={assigning}>
          <Text style={styles.confirmText}>{assigning ? '提交中' : '确认'}</Text>
        </TouchableOpacity>
      </LinearGradient>

      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        {/* 批次信息 */}
        <View style={styles.batchCard}>
          <View style={styles.batchHeader}>
            <Text style={styles.batchNumber}>{batchInfo.batchNumber}</Text>
            <View style={styles.statusBadge}>
              <Text style={styles.statusBadgeText}>
                {batchInfo.status === 'pending' ? '待分配' : '进行中'}
              </Text>
            </View>
          </View>

          <View style={styles.batchDetails}>
            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>产品</Text>
              <Text style={styles.detailValue}>{batchInfo.product}</Text>
            </View>
            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>数量</Text>
              <Text style={styles.detailValue}>{batchInfo.quantity}</Text>
            </View>
            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>车间</Text>
              <Text style={styles.detailValue}>{batchInfo.workshop}</Text>
            </View>
            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>建议人数</Text>
              <Text style={[styles.detailValue, { color: DISPATCHER_THEME.primary }]}>
                {batchInfo.suggestedWorkers}人
              </Text>
            </View>
            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>工时预估</Text>
              <Text style={styles.detailValue}>{batchInfo.estimatedHours}小时</Text>
            </View>
          </View>
        </View>

        {/* 已分配员工 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>
            已分配员工 ({selectedWorkerIds.length}人)
          </Text>
          {selectedWorkerIds.length === 0 ? (
            <View style={styles.emptySection}>
              <Text style={styles.emptyText}>暂无已分配员工</Text>
            </View>
          ) : (
            <View style={styles.selectedAvatars}>
              {selectedWorkerIds.slice(0, 6).map(id => {
                const worker = workers.find(w => w.id === id);
                if (!worker) return null;
                return (
                  <View key={id} style={styles.selectedAvatar}>
                    <Text style={styles.selectedAvatarText}>{worker.avatar}</Text>
                  </View>
                );
              })}
              {selectedWorkerIds.length > 6 && (
                <View style={[styles.selectedAvatar, styles.moreAvatar]}>
                  <Text style={styles.moreAvatarText}>+{selectedWorkerIds.length - 6}</Text>
                </View>
              )}
            </View>
          )}
        </View>

        {/* 可用员工列表 */}
        <View style={[styles.section, { marginBottom: 100 }]}>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>可用员工 ({workers.length}人)</Text>
            <View style={styles.sectionActions}>
              <TouchableOpacity onPress={handleSelectAll}>
                <Text style={styles.actionText}>全选</Text>
              </TouchableOpacity>
              <TouchableOpacity onPress={handleClearSelection}>
                <Text style={[styles.actionText, { color: '#999' }]}>清空</Text>
              </TouchableOpacity>
            </View>
          </View>

          {/* 搜索栏 */}
          <View style={styles.searchContainer}>
            <View style={styles.searchInputWrapper}>
              <Ionicons name="search" size={16} color="#999" />
              <TextInput
                style={styles.searchInput}
                placeholder="搜索工号或姓名..."
                placeholderTextColor="#999"
                value={searchText}
                onChangeText={setSearchText}
              />
            </View>
            <Text style={styles.searchHint}>输入工号 (如001) 或姓名快速定位</Text>
          </View>

          {/* 已选/建议人数提示 */}
          <View style={styles.selectionTip}>
            <Text style={styles.selectionTipText}>
              已选: <Text style={{ fontWeight: '600' }}>{selectedWorkerIds.length}人</Text> / AI建议: {batchInfo.suggestedWorkers}人
            </Text>
            <Text style={[styles.matchStatusText, { color: matchStatus.color }]}>
              {matchStatus.text}
            </Text>
          </View>

          {/* 本车间空闲 */}
          {groupedWorkers.workshop_idle.length > 0 && (
            <>
              <Text style={styles.groupLabel}>
                本车间空闲 ({groupedWorkers.workshop_idle.length}人)
              </Text>
              {groupedWorkers.workshop_idle.map(renderWorkerItem)}
            </>
          )}

          {/* 本车间工作中 */}
          {groupedWorkers.workshop_working.length > 0 && (
            <>
              <Text style={styles.groupLabel}>
                本车间工作中 ({groupedWorkers.workshop_working.length}人)
              </Text>
              {groupedWorkers.workshop_working.map(renderWorkerItem)}
            </>
          )}

          {/* 机动人员 */}
          {groupedWorkers.mobile.length > 0 && (
            <>
              <Text style={styles.groupLabel}>
                机动人员 ({groupedWorkers.mobile.length}人){' '}
                {temporaryCount > 0 && (
                  <Text style={{ color: DISPATCHER_THEME.warning, fontSize: 11 }}>
                    含临时工{temporaryCount}人
                  </Text>
                )}
              </Text>
              {groupedWorkers.mobile.map(renderWorkerItem)}
            </>
          )}

          {/* 可调动员工 */}
          {groupedWorkers.transferable.length > 0 && (
            <>
              <Text style={styles.groupLabel}>
                可调动员工 ({groupedWorkers.transferable.length}人)
              </Text>
              {(showMoreTransferable
                ? groupedWorkers.transferable
                : groupedWorkers.transferable.slice(0, 2)
              ).map(renderWorkerItem)}
              {groupedWorkers.transferable.length > 2 && !showMoreTransferable && (
                <TouchableOpacity
                  style={styles.showMoreButton}
                  onPress={() => setShowMoreTransferable(true)}
                >
                  <Text style={styles.showMoreText}>
                    +{groupedWorkers.transferable.length - 2} 更多员工...
                  </Text>
                </TouchableOpacity>
              )}
            </>
          )}
        </View>
      </ScrollView>

      {/* 底部操作栏 */}
      <View style={styles.bottomBar}>
        <TouchableOpacity style={styles.cancelButton} onPress={handleGoBack}>
          <Text style={styles.cancelButtonText}>取消</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.confirmButton} onPress={handleConfirm} disabled={assigning}>
          <LinearGradient
            colors={[DISPATCHER_THEME.secondary, DISPATCHER_THEME.accent]}
            start={{ x: 0, y: 0 }}
            end={{ x: 1, y: 0 }}
            style={styles.confirmButtonGradient}
          >
            <Text style={styles.confirmButtonText}>
              {assigning ? '提交中...' : `确认分配 (已选${selectedWorkerIds.length}人)`}
            </Text>
          </LinearGradient>
        </TouchableOpacity>
      </View>
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
  emptyStateContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 24,
  },
  emptyStateText: {
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
    paddingVertical: 16,
  },
  backButton: {
    padding: 4,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#fff',
  },
  confirmText: {
    fontSize: 14,
    color: '#fff',
    fontWeight: '500',
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    padding: 16,
  },
  batchCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
  },
  batchHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  batchNumber: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  statusBadge: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    backgroundColor: DISPATCHER_THEME.warning + '20',
    borderRadius: 4,
  },
  statusBadgeText: {
    fontSize: 12,
    color: DISPATCHER_THEME.warning,
    fontWeight: '500',
  },
  batchDetails: {
    gap: 8,
  },
  detailRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  detailLabel: {
    fontSize: 13,
    color: '#999',
  },
  detailValue: {
    fontSize: 13,
    color: '#333',
  },
  section: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
  },
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
    marginBottom: 12,
  },
  sectionActions: {
    flexDirection: 'row',
    gap: 16,
  },
  actionText: {
    fontSize: 12,
    color: DISPATCHER_THEME.primary,
  },
  emptySection: {
    paddingVertical: 24,
    alignItems: 'center',
  },
  emptyText: {
    fontSize: 14,
    color: '#999',
  },
  selectedAvatars: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  selectedAvatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: DISPATCHER_THEME.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  selectedAvatarText: {
    fontSize: 14,
    color: '#fff',
    fontWeight: '500',
  },
  moreAvatar: {
    backgroundColor: '#f0f0f0',
  },
  moreAvatarText: {
    fontSize: 12,
    color: '#666',
  },
  searchContainer: {
    marginBottom: 12,
  },
  searchInputWrapper: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#f5f5f5',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    gap: 8,
  },
  searchInput: {
    flex: 1,
    fontSize: 14,
    color: '#333',
  },
  searchHint: {
    fontSize: 11,
    color: '#999',
    marginTop: 4,
    paddingLeft: 4,
  },
  selectionTip: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: '#f0f7ff',
    borderRadius: 6,
    paddingHorizontal: 12,
    paddingVertical: 8,
    marginBottom: 12,
  },
  selectionTipText: {
    fontSize: 13,
    color: DISPATCHER_THEME.info,
  },
  matchStatusText: {
    fontSize: 12,
    fontWeight: '500',
  },
  groupLabel: {
    fontSize: 13,
    color: '#666',
    marginBottom: 8,
    marginTop: 12,
    paddingLeft: 4,
  },
  workerItem: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fff',
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: '#f0f0f0',
  },
  workerItemSelected: {
    borderColor: DISPATCHER_THEME.primary,
    backgroundColor: DISPATCHER_THEME.primary + '08',
  },
  workerItemDisabled: {
    opacity: 0.6,
  },
  workerItemTemporary: {
    backgroundColor: '#fffbe6',
    borderColor: '#ffe58f',
  },
  checkbox: {
    width: 20,
    height: 20,
    borderRadius: 4,
    borderWidth: 1.5,
    borderColor: '#d9d9d9',
    marginRight: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkboxSelected: {
    backgroundColor: DISPATCHER_THEME.primary,
    borderColor: DISPATCHER_THEME.primary,
  },
  checkboxDisabled: {
    borderColor: '#d9d9d9',
    backgroundColor: '#f5f5f5',
  },
  avatar: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: DISPATCHER_THEME.primary,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 10,
  },
  avatarMobile: {
    backgroundColor: '#ffc53d',
  },
  avatarTemporary: {
    backgroundColor: DISPATCHER_THEME.warning,
  },
  avatarText: {
    fontSize: 14,
    color: '#fff',
    fontWeight: '500',
  },
  workerInfo: {
    flex: 1,
    minWidth: 120,
  },
  workerNameRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  workerName: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
  },
  workerCode: {
    fontSize: 11,
    color: '#999',
  },
  tempBadge: {
    paddingHorizontal: 4,
    paddingVertical: 1,
    backgroundColor: DISPATCHER_THEME.warning,
    borderRadius: 2,
  },
  tempBadgeText: {
    fontSize: 10,
    color: '#fff',
  },
  workerStatusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginTop: 2,
  },
  statusText: {
    fontSize: 12,
  },
  taskText: {
    fontSize: 11,
    color: '#999',
  },
  efficiencyText: {
    fontSize: 11,
    color: '#999',
  },
  workerMeta: {
    alignItems: 'flex-end',
    gap: 2,
  },
  skillBadge: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    backgroundColor: DISPATCHER_THEME.primary + '15',
    borderRadius: 4,
  },
  skillBadgeMobile: {
    backgroundColor: '#fff7e6',
  },
  skillBadgeDisabled: {
    backgroundColor: '#f5f5f5',
  },
  skillText: {
    fontSize: 11,
    color: DISPATCHER_THEME.primary,
  },
  skillTextMobile: {
    color: DISPATCHER_THEME.warning,
  },
  skillTextDisabled: {
    color: '#999',
  },
  hoursText: {
    fontSize: 10,
    color: '#999',
  },
  unavailableText: {
    fontSize: 10,
    color: DISPATCHER_THEME.danger,
  },
  textDisabled: {
    color: '#999',
  },
  showMoreButton: {
    alignItems: 'center',
    paddingVertical: 12,
  },
  showMoreText: {
    fontSize: 13,
    color: DISPATCHER_THEME.primary,
  },
  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    padding: 16,
    backgroundColor: '#fff',
    borderTopWidth: 1,
    borderTopColor: '#f0f0f0',
    gap: 12,
  },
  cancelButton: {
    flex: 1,
    paddingVertical: 12,
    backgroundColor: '#fff',
    borderWidth: 1,
    borderColor: '#d9d9d9',
    borderRadius: 8,
    alignItems: 'center',
  },
  cancelButtonText: {
    fontSize: 14,
    color: '#666',
  },
  confirmButton: {
    flex: 2,
    borderRadius: 8,
    overflow: 'hidden',
  },
  confirmButtonGradient: {
    paddingVertical: 12,
    alignItems: 'center',
  },
  confirmButtonText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#fff',
  },
});
