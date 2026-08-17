import React, { useState, useCallback } from 'react';
import { View, StyleSheet, ScrollView, RefreshControl, Alert } from 'react-native';
import { Text, Appbar, Card, Chip, Divider, ActivityIndicator } from 'react-native-paper';
import { useNavigation, useRoute, useFocusEffect } from '@react-navigation/native';
import { ProcessingScreenProps } from '../../types/navigation';
import { processTaskApiClient, RunOverview, ProcessTaskItem } from '../../services/api/processTaskApiClient';
import { ScreenWrapper } from '../../components/ui';
import { theme } from '../../theme';

type Props = ProcessingScreenProps<'ProcessRunOverview'>;

const STATUS_CONFIG: Record<string, { label: string; color: string }> = {
  PENDING: { label: '待开始', color: '#909399' },
  IN_PROGRESS: { label: '进行中', color: '#1890ff' },
  COMPLETED: { label: '已完成', color: '#67c23a' },
  CLOSED: { label: '已关闭', color: '#606266' },
  SUPPLEMENTING: { label: '补报中', color: '#e6a23c' },
};

export default function ProcessRunOverviewScreen() {
  const navigation = useNavigation<Props['navigation']>();
  const route = useRoute<Props['route']>();
  const { productionRunId } = route.params;

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [overview, setOverview] = useState<RunOverview | null>(null);

  const loadData = useCallback(async () => {
    try {
      const res = await processTaskApiClient.getRunOverview(productionRunId) as { data?: RunOverview };
      if (res?.data) setOverview(res.data);
    } catch {
      Alert.alert('错误', '加载生产单信息失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [productionRunId]);

  useFocusEffect(
    useCallback(() => {
      loadData();
    }, [loadData])
  );

  const onRefresh = () => { setRefreshing(true); loadData(); };

  if (loading && !overview) {
    return (
      <ScreenWrapper edges={['top']} backgroundColor={theme.colors.background}>
        <Appbar.Header><Appbar.BackAction onPress={() => navigation.goBack()} /><Appbar.Content title="生产单" /></Appbar.Header>
        <ActivityIndicator style={{ flex: 1 }} size="large" />
      </ScreenWrapper>
    );
  }

  return (
    <ScreenWrapper testID="run-overview" edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.BackAction testID="run-overview-back" onPress={() => navigation.goBack()} />
        <Appbar.Content
          title="生产单总览"
          subtitle={productionRunId}
          titleStyle={{ fontWeight: '600' }}
        />
        <Appbar.Action icon="refresh" onPress={onRefresh} />
      </Appbar.Header>

      <ScrollView
        contentContainerStyle={styles.scrollContent}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
      >
        {overview && (
          <>
            {/* Overall Progress */}
            <Card testID="run-overview-header" style={styles.card}>
              <Card.Content>
                <Text variant="titleMedium" style={styles.sectionTitle}>整体进度</Text>
                <View testID="run-overview-stats" style={styles.overviewGrid}>
                  <View style={styles.overviewItem}>
                    <Text testID="run-overview-total" style={styles.overviewValue}>{overview.totalTasks}</Text>
                    <Text style={styles.overviewLabel}>总工序</Text>
                  </View>
                  <View style={styles.overviewItem}>
                    <Text style={[styles.overviewValue, { color: '#67c23a' }]}>{overview.completedTasks}</Text>
                    <Text style={styles.overviewLabel}>已完成</Text>
                  </View>
                  <View style={styles.overviewItem}>
                    <Text style={[styles.overviewValue, { color: theme.colors.primary }]}>
                      {(overview.overallProgress > 1 ? overview.overallProgress : overview.overallProgress * 100).toFixed(0)}%
                    </Text>
                    <Text style={styles.overviewLabel}>总进度</Text>
                  </View>
                </View>

                <View style={styles.progressContainer}>
                  <View style={styles.progressTrack}>
                    <View style={[styles.progressFill, { width: `${Math.min(overview.overallProgress > 1 ? overview.overallProgress : overview.overallProgress * 100, 100)}%` }]} />
                  </View>
                </View>
              </Card.Content>
            </Card>

            {/* Task List */}
            <Text variant="titleMedium" style={styles.listTitle}>工序列表</Text>
            {(overview.tasks || []).map((task: ProcessTaskItem, i: number) => {
              const status = STATUS_CONFIG[task.status] || { label: task.status, color: '#909399' };
              // 登记「本轮故意不改」: plannedQuantity 可能为 null(未设计划量)。
              // 本轮只改工序任务列表那一屏, 这里维持按 0 算的原行为。同一缺陷, 待单独一轮。
              const progress = (task.plannedQuantity ?? 0) > 0
                ? Math.min((task.completedQuantity / (task.plannedQuantity ?? 1)) * 100, 100)
                : 0;

              return (
                <Card
                  key={task.id}
                  style={styles.taskCard}
                  onPress={() => navigation.navigate('ProcessTaskDetail', { taskId: task.id })}
                >
                  <Card.Content>
                    <View style={styles.taskHeader}>
                      <View style={styles.taskOrder}>
                        <Text style={styles.orderNum}>{i + 1}</Text>
                      </View>
                      <View style={{ flex: 1, marginLeft: 12 }}>
                        <Text style={styles.taskName}>{task.processName || '-'}</Text>
                        {task.processCategory ? (
                          <Text style={styles.taskCategory}>{task.processCategory}</Text>
                        ) : null}
                      </View>
                      <Chip
                        compact
                        style={{ backgroundColor: status.color + '20' }}
                        textStyle={{ color: status.color, fontSize: 11 }}
                      >
                        {status.label}
                      </Chip>
                    </View>

                    <View style={styles.taskStats}>
                      <Text style={styles.taskStat}>
                        {task.completedQuantity}/{task.plannedQuantity ?? '未设置'} {task.unit}
                      </Text>
                      <View style={styles.miniProgress}>
                        <View style={[styles.miniProgressFill, { width: `${progress}%` }]} />
                      </View>
                      <Text style={styles.taskPercent}>{progress.toFixed(0)}%</Text>
                    </View>
                  </Card.Content>
                </Card>
              );
            })}
          </>
        )}
      </ScrollView>
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  scrollContent: { padding: 16, paddingBottom: 32 },
  card: { marginBottom: 16, borderRadius: 12, backgroundColor: '#fff', elevation: 2 },
  sectionTitle: { fontWeight: '600', marginBottom: 12, color: '#333' },
  overviewGrid: { flexDirection: 'row', justifyContent: 'space-around', marginBottom: 12 },
  overviewItem: { alignItems: 'center' },
  overviewValue: { fontSize: 24, fontWeight: '700', color: '#333' },
  overviewLabel: { fontSize: 12, color: '#999', marginTop: 2 },
  progressContainer: { marginTop: 4 },
  progressTrack: { height: 10, backgroundColor: '#f0f0f0', borderRadius: 5, overflow: 'hidden' },
  progressFill: { height: '100%', backgroundColor: theme.colors.primary, borderRadius: 5 },
  listTitle: { fontWeight: '600', color: '#333', marginBottom: 12, marginTop: 4 },
  taskCard: { marginBottom: 8, borderRadius: 10, backgroundColor: '#fff', elevation: 1 },
  taskHeader: { flexDirection: 'row', alignItems: 'center' },
  taskOrder: {
    width: 28, height: 28, borderRadius: 14, backgroundColor: theme.colors.primary + '15',
    justifyContent: 'center', alignItems: 'center',
  },
  orderNum: { fontSize: 13, fontWeight: '700', color: theme.colors.primary },
  taskName: { fontSize: 14, fontWeight: '600', color: '#333' },
  taskCategory: { fontSize: 11, color: '#999', marginTop: 1 },
  taskStats: { flexDirection: 'row', alignItems: 'center', marginTop: 8, gap: 8 },
  taskStat: { fontSize: 12, color: '#666', width: 90 },
  miniProgress: { flex: 1, height: 4, backgroundColor: '#f0f0f0', borderRadius: 2, overflow: 'hidden' },
  miniProgressFill: { height: '100%', backgroundColor: theme.colors.primary, borderRadius: 2 },
  taskPercent: { fontSize: 12, color: '#666', width: 34, textAlign: 'right' },
});
