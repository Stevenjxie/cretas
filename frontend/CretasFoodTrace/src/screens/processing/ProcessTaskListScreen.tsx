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
import {
  buildReportableTaskIds,
  compareTasksByWorkOrder,
  extractProcessTaskList,
  getTaskBatchId,
  getBatchKey,
  getTaskWorkProcessTaskId,
} from '../../utils/processTaskFlow';

type Props = ProcessingScreenProps<'ProcessTaskList'>;

const STATUS_CONFIG: Record<string, { label: string; color: string }> = {
  PENDING: { label: '待开始', color: '#909399' },
  IN_PROGRESS: { label: '进行中', color: '#1890ff' },
  COMPLETED: { label: '已完成', color: '#67c23a' },
  CLOSED: { label: '已关闭', color: '#606266' },
  SUPPLEMENTING: { label: '补报中', color: '#e6a23c' },
};

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

      setTasks(extractProcessTaskList(result));
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

  /**
   * 进度百分比。**没有计划量就没有进度可言** —— 返回 null, 让调用方不画进度条。
   *
   * ⚠️ 原来这里 `if (!plannedQuantity || === 0) return 0`, 把两件完全不同的事
   * 折叠成了同一个 0:
   *   - 计划量 100, 完成 0    → 真的是 0%
   *   - 计划量【没设】        → 分母不存在, 百分比【算不出来】
   * 对仓管员/操作员来说, 一条 0% 的进度条是一个明确的陈述(「这活一点没干」),
   * 而事实是「没人给这道工序设过计划量」。那是在编一个数。
   */
  const getProgress = (task: ProcessTaskItem): number | null => {
    if (task.plannedQuantity == null || task.plannedQuantity === 0) return null;
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
                {item.plannedQuantity == null ? (
                  <Text
                    testID={`process-task-planned-unset-${item.id}`}
                    style={[styles.value, styles.valueUnset]}
                  >
                    未设置
                  </Text>
                ) : (
                  <Text testID={`process-task-planned-value-${item.id}`} style={styles.value}>
                    {item.plannedQuantity}
                  </Text>
                )}
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

            {/* Progress bar —— 没有计划量就不画。
                ⛔ 不要退回「画一条 0% 的条」: 那是把「算不出来」说成「一点没干」。 */}
            {progress != null ? (
              <View testID={`process-task-progress-${item.id}`} style={styles.progressContainer}>
                <View style={styles.progressTrack}>
                  <View style={[styles.progressFill, { width: `${progress}%` }]} />
                </View>
                <Text style={styles.progressText}>{progress.toFixed(0)}%</Text>
              </View>
            ) : null}
          </View>

          {canReport ? (
            <View style={styles.cardFooter}>
              <NeoButton
                testID={`process-task-report-btn-${item.id}`}
                variant="primary"
                size="medium"
                onPress={() => {
                  const batchId = getTaskBatchId(item);
                  if (batchId == null) {
                    Alert.alert('不能报工', '当前工序没有绑定生产批次，请联系管理员重新转批次。');
                    return;
                  }
                  navigation.navigate('YieldStepReport', {
                    batchId,
                    assignedWorkProcessTaskId: getTaskWorkProcessTaskId(item) ?? undefined,
                    assignedProcessOrder: item.processOrder,
                  });
                }}
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
        <Appbar.Action testID="yield-batch-select-btn" icon="qrcode-scan" onPress={() => navigation.navigate('YieldBatchSelect')} />
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
  // 「未设置」不是一个数值, 视觉上也不该长得像数值 —— 弱化 + 缩小, 避免被当成读数
  valueUnset: { color: theme.colors.textTertiary, fontWeight: '500', fontSize: 15 },
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
