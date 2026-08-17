import React, { useState, useCallback, useEffect, useRef } from 'react';
import { View, StyleSheet, ScrollView, RefreshControl, Alert } from 'react-native';
import { Text, Appbar, Card, Chip, Divider, ActivityIndicator } from 'react-native-paper';
import { useNavigation, useRoute, useFocusEffect } from '@react-navigation/native';
import { ProcessingScreenProps } from '../../types/navigation';
import {
  processTaskApiClient,
  ProcessTaskItem,
  ProcessTaskSummary,
  ProcessReportItem,
  WorkerSummary,
} from '../../services/api/processTaskApiClient';
import { NeoButton, ScreenWrapper } from '../../components/ui';
import { theme } from '../../theme';
import {
  getTaskBatchId,
  getTaskWorkProcessTaskId,
} from '../../utils/processTaskFlow';

type Props = ProcessingScreenProps<'ProcessTaskDetail'>;

/** Format datetime string to show date + time (e.g. "03-20 15:30") */
const formatReportTime = (dateStr?: string): string => {
  if (!dateStr) return '-';
  try {
    const d = new Date(dateStr);
    if (isNaN(d.getTime())) return dateStr;
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    const hh = String(d.getHours()).padStart(2, '0');
    const min = String(d.getMinutes()).padStart(2, '0');
    return `${mm}-${dd} ${hh}:${min}`;
  } catch {
    return dateStr;
  }
};

const STATUS_CONFIG: Record<string, { label: string; color: string }> = {
  PENDING: { label: '待开始', color: '#909399' },
  IN_PROGRESS: { label: '进行中', color: '#1890ff' },
  COMPLETED: { label: '已完成', color: '#67c23a' },
  CLOSED: { label: '已关闭', color: '#606266' },
  SUPPLEMENTING: { label: '补报中', color: '#e6a23c' },
};

const APPROVAL_CONFIG: Record<string, { label: string; color: string }> = {
  PENDING: { label: '待审批', color: '#e6a23c' },
  APPROVED: { label: '已通过', color: '#67c23a' },
  REJECTED: { label: '已驳回', color: '#f56c6c' },
};

export default function ProcessTaskDetailScreen() {
  const navigation = useNavigation<Props['navigation']>();
  const route = useRoute<Props['route']>();
  const { taskId } = route.params;

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [summary, setSummary] = useState<ProcessTaskSummary | null>(null);
  const [reports, setReports] = useState<ProcessReportItem[]>([]);
  const [workers, setWorkers] = useState<WorkerSummary[]>([]);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [summaryRes, reportsRes, workersRes] = await Promise.all([
        processTaskApiClient.getTaskSummary(taskId) as Promise<{ data?: ProcessTaskSummary }>,
        processTaskApiClient.getReportsByTask(taskId) as Promise<{ data?: ProcessReportItem | ProcessReportItem[] }>,
        processTaskApiClient.getWorkersByTask(taskId) as Promise<{ data?: WorkerSummary | WorkerSummary[] }>,
      ]);

      if (summaryRes?.data) setSummary(summaryRes.data);
      if (reportsRes?.data) setReports(Array.isArray(reportsRes.data) ? reportsRes.data : []);
      if (workersRes?.data) setWorkers(Array.isArray(workersRes.data) ? workersRes.data : []);
    } catch {
      Alert.alert('错误', '加载任务详情失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [taskId]);

  useFocusEffect(
    useCallback(() => {
      loadData();
    }, [loadData])
  );

  // 30s 轮询检测审批结果变化
  const prevPendingRef = useRef<number | null>(null);
  useEffect(() => {
    const interval = setInterval(async () => {
      try {
        const res = await processTaskApiClient.getTaskSummary(taskId) as { data?: ProcessTaskSummary };
        if (res?.data) {
          const newPending = res.data.pendingQuantity ?? (res.data.task?.pendingQuantity ?? 0);
          const newCompleted = res.data.completedQuantity ?? (res.data.task?.completedQuantity ?? 0);
          if (prevPendingRef.current !== null && newPending < prevPendingRef.current) {
            Alert.alert('审批更新', `有报工已审批完成，完成量: ${newCompleted}`);
            loadData();
          }
          prevPendingRef.current = newPending;
        }
      } catch { /* silent */ }
    }, 30000);
    return () => clearInterval(interval);
  }, [taskId, loadData]);

  const handleRefresh = () => { setRefreshing(true); loadData(); };

  // Backend may return flat shape (fields at top level) or nested { task: {...} }
  const task = summary?.task || (summary?.taskId || summary?.status ? {
    id: summary.taskId || summary.id || taskId,
    factoryId: '',
    productTypeId: '',
    workProcessId: '',
    processName: summary.processName || '未命名工序',
    processCategory: summary.processCategory,
    productTypeName: summary.productTypeName || summary.productName,
    unit: summary.unit || 'kg',
    productionRunId: summary.productionRunId,
    plannedQuantity: summary.plannedQuantity || 0,
    completedQuantity: summary.completedQuantity || 0,
    pendingQuantity: summary.pendingQuantity || 0,
    inputQuantity: summary.inputQuantity,
    status: (summary.status || 'PENDING') as ProcessTaskItem['status'],
  } as ProcessTaskItem : null);
  const status = task ? STATUS_CONFIG[task.status] || { label: task.status, color: '#909399' } : null;

  // Input/Output comparison
  const inputQty = task?.inputQuantity;
  const completedQty = task ? task.completedQuantity : undefined;
  const showIOComparison = inputQty != null && inputQty > 0 && completedQty != null && completedQty > 0;
  const conversionRate = showIOComparison ? (completedQty / inputQty) * 100 : 0;
  const conversionColor = conversionRate >= 90 ? '#67c23a' : conversionRate >= 80 ? '#e6a23c' : '#f56c6c';

  // 登记「本轮故意不改」: plannedQuantity 可能为 null(未设计划量)。
  // 本轮只改工序任务列表那一屏, 这里维持按 0 算的原行为。同一缺陷, 待单独一轮。
  const progress = task && (task.plannedQuantity ?? 0) > 0
    ? Math.min((task.completedQuantity / (task.plannedQuantity ?? 1)) * 100, 100)
    : 0;

  if (loading && !summary) {
    return (
      <ScreenWrapper edges={['top']} backgroundColor={theme.colors.background}>
        <Appbar.Header><Appbar.BackAction onPress={() => navigation.goBack()} /><Appbar.Content title="任务详情" /></Appbar.Header>
        <ActivityIndicator style={{ flex: 1 }} size="large" />
      </ScreenWrapper>
    );
  }

  return (
    <ScreenWrapper testID="process-task-detail" edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.BackAction testID="task-detail-back" onPress={() => navigation.goBack()} />
        <Appbar.Content title={task?.processName || '任务详情'} titleStyle={{ fontWeight: '600' }} />
        <Appbar.Action icon="refresh" onPress={handleRefresh} />
      </Appbar.Header>

      <ScrollView
        contentContainerStyle={styles.scrollContent}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />}
      >
        {/* Task Overview */}
        {task && status && (
          <Card style={styles.card}>
            <Card.Content>
              <View style={styles.headerRow}>
                <Text variant="titleLarge" style={styles.title}>{task.processName}</Text>
                <Chip style={{ backgroundColor: status.color + '20' }} textStyle={{ color: status.color }}>
                  {status.label}
                </Chip>
              </View>

              {task.processCategory ? (
                <Text style={styles.subtitle}>类别: {task.processCategory}</Text>
              ) : null}
              {task.productTypeName ? (
                <Text style={styles.subtitle}>产品: {task.productTypeName}</Text>
              ) : null}

              <Divider style={styles.divider} />

              {/* Quantity Summary */}
              <View testID="task-detail-quantities" style={styles.quantityGrid}>
                <View style={styles.quantityItem}>
                  <Text testID="task-detail-planned-qty" style={styles.quantityValue}>{task.plannedQuantity}</Text>
                  <Text style={styles.quantityLabel}>计划量 ({task.unit})</Text>
                </View>
                <View style={styles.quantityItem}>
                  <Text testID="task-detail-completed-qty" style={[styles.quantityValue, { color: '#67c23a' }]}>{task.completedQuantity}</Text>
                  <Text style={styles.quantityLabel}>已完成</Text>
                </View>
                <View style={styles.quantityItem}>
                  <Text testID="task-detail-pending-qty" style={[styles.quantityValue, { color: '#e6a23c' }]}>{task.pendingQuantity}</Text>
                  <Text style={styles.quantityLabel}>待审批</Text>
                </View>
              </View>

              {/* Progress bar */}
              <View style={styles.progressContainer}>
                <View style={styles.progressTrack}>
                  <View style={[styles.progressFill, { width: `${progress}%` }]} />
                </View>
                <Text style={styles.progressText}>{progress.toFixed(1)}%</Text>
              </View>
            </Card.Content>
          </Card>
        )}

        {/* Input/Output Comparison */}
        {showIOComparison && (
          <Card style={styles.card} testID="process-io-comparison">
            <Card.Content>
              <Text variant="titleMedium" style={styles.sectionTitle}>投入产出对比</Text>
              <View style={styles.ioRow}>
                <Text style={styles.ioLabel}>投入量</Text>
                <Text style={styles.ioValue}>{inputQty} {task?.unit || 'kg'}</Text>
              </View>
              <View style={styles.ioRow}>
                <Text style={styles.ioLabel}>产出量</Text>
                <Text style={styles.ioValue}>{completedQty} {task?.unit || 'kg'}</Text>
              </View>
              <Divider style={styles.divider} />
              <View style={styles.ioRow}>
                <Text style={styles.ioLabel}>转化率</Text>
                <View style={styles.ioRateContainer}>
                  <View style={[styles.ioRateDot, { backgroundColor: conversionColor }]} />
                  <Text style={[styles.ioRateValue, { color: conversionColor }]}>
                    {conversionRate.toFixed(1)}%
                  </Text>
                </View>
              </View>
            </Card.Content>
          </Card>
        )}

        {/* Action Buttons */}
        {task && (task.status === 'IN_PROGRESS' || task.status === 'SUPPLEMENTING') && (
          <View style={styles.actionRow}>
            <NeoButton
              testID="task-detail-report-btn"
              variant="primary"
              onPress={() => {
                const batchId = getTaskBatchId(task);
                if (batchId == null) {
                  Alert.alert('不能报工', '当前工序没有绑定生产批次，请联系管理员重新转批次。');
                  return;
                }
                navigation.navigate('YieldStepReport', {
                  batchId,
                  assignedWorkProcessTaskId: getTaskWorkProcessTaskId(task) ?? undefined,
                  assignedProcessOrder: task.processOrder,
                });
              }}
              style={styles.actionBtn}
            >
              {task.status === 'SUPPLEMENTING' ? '补报' : '报工'}
            </NeoButton>
            {task.productionRunId ? (
              <NeoButton
                testID="task-detail-run-btn"
                variant="outline"
                onPress={() => navigation.navigate('ProcessRunOverview', {
                  productionRunId: task.productionRunId!,
                })}
                style={styles.actionBtn}
              >
                查看生产单
              </NeoButton>
            ) : null}
          </View>
        )}

        {/* Worker Summary */}
        {workers.length > 0 && (
          <Card style={styles.card}>
            <Card.Content>
              <Text variant="titleMedium" style={styles.sectionTitle}>工人统计</Text>
              {workers.map((w, i) => (
                <View key={i} style={styles.workerRow}>
                  <Text style={styles.workerName}>{w.workerName}</Text>
                  <View style={styles.workerStats}>
                    <Text style={styles.workerStat}>已审批 {w.approvedQuantity}</Text>
                    {w.pendingQuantity > 0 && (
                      <Text style={[styles.workerStat, { color: '#e6a23c' }]}>待审批 {w.pendingQuantity}</Text>
                    )}
                    <Text style={styles.workerStat}>{w.reportCount}次</Text>
                  </View>
                </View>
              ))}
            </Card.Content>
          </Card>
        )}

        {/* Recent Reports */}
        <Card style={styles.card}>
          <Card.Content>
            <Text variant="titleMedium" style={styles.sectionTitle}>
              报工记录 ({reports.length})
            </Text>
            {reports.length === 0 ? (
              <Text style={styles.emptyText}>暂无报工记录</Text>
            ) : (
              reports.slice(0, 20).map((r, i) => {
                const approval = APPROVAL_CONFIG[r.approvalStatus] || { label: r.approvalStatus, color: '#909399' };
                return (
                  <View key={i} style={styles.reportRow}>
                    <View style={{ flex: 1 }}>
                      <View style={styles.reportHeader}>
                        <Text style={styles.reporterName}>{r.reporterName || '-'}</Text>
                        {r.isSupplemental && (
                          <Chip compact style={styles.supplementChip} textStyle={styles.supplementText}>补报</Chip>
                        )}
                      </View>
                      <Text style={styles.reportDate}>{formatReportTime(r.reportDate || r.createdAt)}</Text>
                    </View>
                    <View style={styles.reportRight}>
                      <Text style={[styles.reportQty, r.outputQuantity < 0 && { color: '#f56c6c' }]}>
                        {r.outputQuantity > 0 ? '+' : ''}{r.outputQuantity}
                      </Text>
                      <Text style={[styles.reportStatus, { color: approval.color }]}>{approval.label}</Text>
                    </View>
                  </View>
                );
              })
            )}
          </Card.Content>
        </Card>
      </ScrollView>
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  scrollContent: { padding: 16, paddingBottom: 32 },
  card: { marginBottom: 12, borderRadius: 12, backgroundColor: '#fff', elevation: 2 },
  headerRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  title: { fontWeight: '700', color: '#333', flex: 1 },
  subtitle: { color: '#666', marginTop: 4, fontSize: 15 },
  divider: { marginVertical: 12 },
  quantityGrid: { flexDirection: 'row', justifyContent: 'space-around' },
  quantityItem: { alignItems: 'center' },
  quantityValue: { fontSize: 26, fontWeight: '700', color: '#333' },
  quantityLabel: { fontSize: 14, color: '#999', marginTop: 2 },
  progressContainer: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 12 },
  progressTrack: { flex: 1, height: 8, backgroundColor: '#f0f0f0', borderRadius: 4, overflow: 'hidden' },
  progressFill: { height: '100%', backgroundColor: theme.colors.primary, borderRadius: 4 },
  progressText: { fontSize: 13, color: '#666', width: 44, textAlign: 'right' },
  actionRow: { flexDirection: 'row', gap: 12, marginBottom: 12 },
  actionBtn: { flex: 1 },
  sectionTitle: { fontWeight: '600', marginBottom: 12, color: '#333' },
  workerRow: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
    paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#f0f0f0',
  },
  workerName: { fontSize: 16, fontWeight: '500', color: '#333' },
  workerStats: { flexDirection: 'row', gap: 12 },
  workerStat: { fontSize: 14, color: '#666' },
  reportRow: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
    paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#f5f5f5',
  },
  reportHeader: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  reporterName: { fontSize: 16, fontWeight: '500', color: '#333' },
  supplementChip: { height: 22, backgroundColor: '#e6a23c15' },
  supplementText: { fontSize: 11, color: '#e6a23c' },
  reportDate: { fontSize: 14, color: '#999', marginTop: 2 },
  reportRight: { alignItems: 'flex-end' },
  reportQty: { fontSize: 18, fontWeight: '700', color: '#1890ff' },
  reportStatus: { fontSize: 13, marginTop: 2 },
  emptyText: { color: '#999', textAlign: 'center', paddingVertical: 20 },
  ioRow: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
    paddingVertical: 6,
  },
  ioLabel: { fontSize: 14, color: '#666' },
  ioValue: { fontSize: 15, fontWeight: '600', color: '#333' },
  ioRateContainer: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  ioRateDot: { width: 8, height: 8, borderRadius: 4 },
  ioRateValue: { fontSize: 18, fontWeight: '700' },
});
