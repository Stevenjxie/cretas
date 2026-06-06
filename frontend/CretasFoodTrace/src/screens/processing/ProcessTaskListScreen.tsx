import React, { useState, useCallback, useMemo, useEffect } from 'react';
import { View, StyleSheet, FlatList, RefreshControl, TouchableOpacity, Alert } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Text, Appbar, Searchbar, SegmentedButtons, IconButton } from 'react-native-paper';
import { useNavigation, useFocusEffect, useNavigationState } from '@react-navigation/native';
import { ProcessingScreenProps } from '../../types/navigation';
import { processTaskApiClient, ProcessTaskItem } from '../../services/api/processTaskApiClient';
import { handleError } from '../../utils/errorHandler';
import { NeoCard, NeoButton, ScreenWrapper } from '../../components/ui';
import { theme } from '../../theme';
import { useAuthStore } from '../../store/authStore';

type Props = ProcessingScreenProps<'ProcessTaskList'>;

const STATUS_CONFIG: Record<string, { label: string; color: string }> = {
  PENDING: { label: '待开始', color: '#909399' },
  IN_PROGRESS: { label: '进行中', color: '#1890ff' },
  COMPLETED: { label: '已完成', color: '#67c23a' },
  CLOSED: { label: '已关闭', color: '#606266' },
  SUPPLEMENTING: { label: '补报中', color: '#e6a23c' },
};

const PROCESS_NAME_ORDER: Array<[string, number]> = [
  ['修油', 10],
  ['水解', 10],
  ['化冻', 10],
  ['滚揉', 20],
  ['注射', 20],
  ['焯水', 30],
  ['熟制', 40],
  ['卤制', 40],
  ['气调', 50],
  ['装盒', 50],
  ['包装', 50],
];

function toNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function getBatchRank(task: ProcessTaskItem): number {
  const batchId = toNumber(task.batchId ?? task.productionBatchId);
  if (batchId != null) return -batchId;
  if (typeof task.productionRunId === 'string' && task.productionRunId.startsWith('BATCH-')) {
    const runBatchId = toNumber(task.productionRunId.replace('BATCH-', ''));
    return runBatchId != null ? -runBatchId : 0;
  }
  return Number.MAX_SAFE_INTEGER;
}

function getBatchKey(task: ProcessTaskItem): string | null {
  const batchId = task.batchId ?? task.productionBatchId;
  if (batchId != null) return `batch:${batchId}`;
  if (typeof task.productionRunId === 'string' && task.productionRunId.startsWith('BATCH-')) {
    return task.productionRunId;
  }
  return null;
}

function getProcessOrder(task: ProcessTaskItem): number {
  const explicitOrder = toNumber(task.processOrder);
  if (explicitOrder != null) return explicitOrder;

  if (typeof task.workProcessId === 'string') {
    const suffix = task.workProcessId.match(/(\d+)$/)?.[1];
    const parsedSuffix = toNumber(suffix);
    if (parsedSuffix != null) return parsedSuffix;
  }

  const name = task.processName || '';
  const matched = PROCESS_NAME_ORDER.find(([keyword]) => name.includes(keyword));
  if (matched) return matched[1];

  const taskId = toNumber(task.workProcessTaskId);
  if (taskId != null) return taskId;

  return Number.MAX_SAFE_INTEGER;
}

function getStatusRank(status: ProcessTaskItem['status']): number {
  if (status === 'IN_PROGRESS' || status === 'SUPPLEMENTING') return 0;
  if (status === 'PENDING') return 1;
  if (status === 'COMPLETED') return 2;
  return 3;
}

function isTerminalTask(status: ProcessTaskItem['status']): boolean {
  return status === 'COMPLETED' || status === 'CLOSED';
}

function compareTasksByWorkOrder(a: ProcessTaskItem, b: ProcessTaskItem): number {
  return (
    getBatchRank(a) - getBatchRank(b)
    || getProcessOrder(a) - getProcessOrder(b)
    || getStatusRank(a.status) - getStatusRank(b.status)
    || String(a.createdAt || '').localeCompare(String(b.createdAt || ''))
    || String(a.id).localeCompare(String(b.id))
  );
}

function buildReportableTaskIds(orderedTasks: ProcessTaskItem[]): Set<string> {
  const reportableIds = new Set<string>();
  const batchTasks = new Map<string, ProcessTaskItem[]>();

  for (const task of orderedTasks) {
    const key = getBatchKey(task);
    if (!key) {
      if (task.status === 'IN_PROGRESS' || task.status === 'SUPPLEMENTING') {
        reportableIds.add(task.id);
      }
      continue;
    }
    const group = batchTasks.get(key) || [];
    group.push(task);
    batchTasks.set(key, group);
  }

  for (const group of batchTasks.values()) {
    const sortedGroup = [...group].sort(compareTasksByWorkOrder);
    const nextTask = sortedGroup.find(task => !isTerminalTask(task.status));
    if (nextTask && nextTask.status !== 'CLOSED') {
      reportableIds.add(nextTask.id);
    }
  }

  return reportableIds;
}

export default function ProcessTaskListScreen() {
  const navigation = useNavigation<Props['navigation']>();
  const canGoBack = useNavigationState(state => state.routes.length > 1);
  const currentRole = useAuthStore(state => state.getUserRole());
  const isOperator = currentRole === 'operator';

  const [searchQuery, setSearchQuery] = useState('');
  const [selectedStatus, setSelectedStatus] = useState<string>('active');
  const [tasks, setTasks] = useState<ProcessTaskItem[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchTasks = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      let result;
      if (selectedStatus === 'active') {
        result = await processTaskApiClient.getActiveTasks();
      } else {
        result = await processTaskApiClient.getTasks({
          status: selectedStatus === 'all' ? undefined : selectedStatus,
          page: 1,
          size: 50,
        });
      }

      let taskList: ProcessTaskItem[] = [];
      const res = result as { data?: { content?: ProcessTaskItem[] } | ProcessTaskItem[] };
      if (res?.data && 'content' in res.data && res.data.content) taskList = res.data.content;
      else if (Array.isArray(res?.data)) taskList = res.data;
      else if (Array.isArray(result)) taskList = result as ProcessTaskItem[];

      setTasks(taskList);
    } catch (err) {
      handleError(err, { showAlert: false, logError: true });
      setError(err instanceof Error ? err.message : '加载工序任务失败');
      setTasks([]);
    } finally {
      setLoading(false);
    }
  }, [selectedStatus]);

  useFocusEffect(
    useCallback(() => {
      fetchTasks();
    }, [fetchTasks])
  );

  // 首次使用引导
  useEffect(() => {
    AsyncStorage.getItem('processTaskGuideShown').then(shown => {
      if (!shown) {
        Alert.alert(
          '操作指引',
          '1. 选择要报工的工序卡片\n2. 点击「报工」按钮\n3. 输入本次产出数量\n4. 提交后等待主管审批',
          [{ text: '知道了', onPress: () => AsyncStorage.setItem('processTaskGuideShown', 'true') }]
        );
      }
    });
  }, []);

  const handleRefresh = async () => {
    setRefreshing(true);
    await fetchTasks();
    setRefreshing(false);
  };

  const orderedTasks = useMemo(() => [...tasks].sort(compareTasksByWorkOrder), [tasks]);

  const filteredTasks = useMemo(() => orderedTasks
    .filter(task => {
      if (!searchQuery) return true;
      const q = searchQuery.toLowerCase();
      return (
        task.processName?.toLowerCase().includes(q) ||
        task.productTypeName?.toLowerCase().includes(q) ||
        task.processCategory?.toLowerCase().includes(q) ||
        task.id.toLowerCase().includes(q)
      );
    }), [orderedTasks, searchQuery]);

  const reportableTaskIds = useMemo(
    () => buildReportableTaskIds(orderedTasks),
    [orderedTasks]
  );

  const getProgress = (task: ProcessTaskItem) => {
    if (!task.plannedQuantity || task.plannedQuantity === 0) return 0;
    return Math.min((task.completedQuantity / task.plannedQuantity) * 100, 100);
  };

  const renderTaskCard = useCallback(({ item }: { item: ProcessTaskItem }) => {
    const status = STATUS_CONFIG[item.status] || { label: item.status, color: '#909399' };
    const progress = getProgress(item);
    const canReport = reportableTaskIds.has(item.id);
    const waitingPrevious = item.status === 'PENDING' && !canReport && getBatchKey(item) != null;

    return (
      <TouchableOpacity
        testID={`process-task-card-${item.id}`}
        onPress={() => navigation.navigate('ProcessTaskDetail', { taskId: item.id })}
        activeOpacity={0.7}
      >
        <NeoCard style={styles.card} padding="m">
          <View style={styles.cardHeader}>
            <View style={{ flex: 1 }}>
              <Text variant="titleMedium" style={styles.processName}>
                {item.processName || '未命名工序'}
              </Text>
              {item.processCategory ? (
                <Text variant="bodySmall" style={styles.category}>{item.processCategory}</Text>
              ) : null}
            </View>
            <View style={{ flexDirection: 'row', gap: 6 }}>
              {(item as any).overdue && (
                <View style={[styles.statusBadge, { backgroundColor: '#f5636420' }]}>
                  <Text style={[styles.statusText, { color: '#f56364' }]}>超期</Text>
                </View>
              )}
              <View style={[styles.statusBadge, { backgroundColor: status.color + '20' }]}>
                <Text style={[styles.statusText, { color: status.color }]}>{status.label}</Text>
              </View>
            </View>
          </View>

          <View style={styles.cardBody}>
            <View style={styles.row}>
              <View style={styles.col}>
                <Text style={styles.label}>产品</Text>
                <Text style={styles.value}>{item.productTypeName || '-'}</Text>
              </View>
              <View style={styles.col}>
                <Text style={styles.label}>单位</Text>
                <Text style={styles.value}>{item.unit || 'kg'}</Text>
              </View>
            </View>

            <View style={styles.quantityRow}>
              <View style={styles.col}>
                <Text style={styles.label}>计划量</Text>
                <Text style={styles.value}>{item.plannedQuantity}</Text>
              </View>
              <View style={styles.col}>
                <Text style={styles.label}>已完成</Text>
                <Text style={[styles.value, styles.highlight]}>{item.completedQuantity}</Text>
              </View>
              {item.pendingQuantity > 0 ? (
                <View style={styles.col}>
                  <Text style={styles.label}>待审批</Text>
                  <Text style={[styles.value, { color: '#e6a23c' }]}>{item.pendingQuantity}</Text>
                </View>
              ) : null}
            </View>

            {/* Progress bar */}
            <View style={styles.progressContainer}>
              <View style={styles.progressTrack}>
                <View style={[styles.progressFill, { width: `${progress}%` }]} />
              </View>
              <Text style={styles.progressText}>{progress.toFixed(0)}%</Text>
            </View>
          </View>

          {canReport ? (
            <View style={styles.cardFooter}>
              <NeoButton
                testID={`process-task-report-btn-${item.id}`}
                variant="primary"
                size="medium"
                onPress={() => navigation.navigate('ProcessTaskReport', {
                  taskId: item.id,
                  processName: item.processName,
                  unit: item.unit,
                })}
              >
                报工
              </NeoButton>
            </View>
          ) : waitingPrevious ? (
            <View style={styles.cardFooter}>
              <Text style={styles.waitingText}>等上一道完成后再报</Text>
            </View>
          ) : null}
        </NeoCard>
      </TouchableOpacity>
    );
  }, [navigation, reportableTaskIds]);

  return (
    <ScreenWrapper testID="process-task-list" edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        {canGoBack ? (
          <Appbar.BackAction testID="process-task-list-back" onPress={() => navigation.goBack()} />
        ) : null}
        <Appbar.Content title="工序任务" titleStyle={{ fontWeight: '600' }} />
        <Appbar.Action testID="three-step-report-btn" icon="qrcode-scan" onPress={() => navigation.navigate('ThreeStepReport')} />
        {!isOperator ? (
          <Appbar.Action testID="process-task-approval-btn" icon="clipboard-check-outline" onPress={() => navigation.navigate('ProcessTaskApproval' as never)} />
        ) : null}
        <Appbar.Action testID="process-task-history-btn" icon="history" onPress={() => navigation.navigate('ProcessTaskHistory')} />
      </Appbar.Header>

      <View style={styles.searchContainer}>
        <Searchbar
          testID="process-task-search"
          placeholder="搜索工序名称、产品..."
          onChangeText={setSearchQuery}
          value={searchQuery}
          style={styles.searchBar}
          inputStyle={styles.searchInput}
          onSubmitEditing={() => fetchTasks()}
          elevation={0}
        />
      </View>

      <View testID="process-task-filter">
        <SegmentedButtons
          value={selectedStatus}
          onValueChange={setSelectedStatus}
          buttons={[
            { value: 'active', label: '进行中' },
            { value: 'COMPLETED', label: '已完成' },
            { value: 'all', label: '全部' },
          ]}
          style={styles.segmentedButtons}
          density="small"
        />
      </View>

      <FlatList
        testID="process-task-flatlist"
        data={filteredTasks}
        renderItem={renderTaskCard}
        keyExtractor={item => item.id}
        contentContainerStyle={styles.listContent}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />}
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            {error ? (
              <>
                <IconButton icon="alert-circle-outline" size={48} iconColor={theme.colors.error} />
                <Text style={styles.errorText}>{error}</Text>
                <NeoButton variant="outline" onPress={fetchTasks} style={styles.retryButton}>重试</NeoButton>
              </>
            ) : (
              <Text style={styles.emptyText}>
                {loading ? '加载中...' : '暂无工序任务'}
              </Text>
            )}
          </View>
        }
      />
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  searchContainer: {
    padding: 16,
    backgroundColor: theme.colors.surface,
    paddingBottom: 8,
  },
  searchBar: {
    backgroundColor: theme.colors.surfaceVariant,
    borderRadius: theme.custom.borderRadius.m,
    height: 44,
  },
  searchInput: { minHeight: 0 },
  segmentedButtons: { margin: 16, marginTop: 0 },
  listContent: { padding: 16, paddingTop: 0 },
  card: { marginBottom: 12 },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 12,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.outlineVariant,
    paddingBottom: 12,
  },
  processName: { fontWeight: '700', color: theme.colors.text, fontSize: 20 },
  category: { color: theme.colors.textTertiary, marginTop: 2, fontSize: 16 },
  statusBadge: { borderRadius: 12, paddingHorizontal: 12, paddingVertical: 5 },
  statusText: { fontSize: 14, fontWeight: '600' },
  cardBody: { gap: 10 },
  row: { flexDirection: 'row', justifyContent: 'space-between' },
  quantityRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 },
  col: { flex: 1 },
  label: { color: theme.colors.textSecondary, fontSize: 15, marginBottom: 2 },
  value: { color: theme.colors.text, fontWeight: '600', fontSize: 18 },
  highlight: { color: theme.colors.primary, fontWeight: '700' },
  progressContainer: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 6 },
  progressTrack: {
    flex: 1,
    height: 8,
    backgroundColor: theme.colors.surfaceVariant,
    borderRadius: 3,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    backgroundColor: theme.colors.primary,
    borderRadius: 3,
  },
  progressText: { fontSize: 12, color: theme.colors.textSecondary, width: 36, textAlign: 'right' },
  cardFooter: { marginTop: 12, flexDirection: 'row', justifyContent: 'flex-end' },
  waitingText: { color: theme.colors.textSecondary, fontSize: 14, fontWeight: '600' },
  emptyContainer: { alignItems: 'center', padding: 48 },
  emptyText: { color: theme.colors.textSecondary, marginTop: 16 },
  errorText: { color: theme.colors.error, marginTop: 16, marginBottom: 16 },
  retryButton: { minWidth: 120 },
});
