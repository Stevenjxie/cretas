import React, { useState, useCallback } from 'react';
import { View, StyleSheet, FlatList, RefreshControl } from 'react-native';
import { Text, Appbar, Card, Chip, SegmentedButtons, ActivityIndicator } from 'react-native-paper';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { ProcessingScreenProps } from '../../types/navigation';
import { processTaskApiClient, ProcessTaskItem } from '../../services/api/processTaskApiClient';
import { ScreenWrapper } from '../../components/ui';
import { theme } from '../../theme';

type Props = ProcessingScreenProps<'ProcessTaskHistory'>;

const STATUS_CONFIG: Record<string, { label: string; color: string }> = {
  PENDING: { label: '待开始', color: '#909399' },
  IN_PROGRESS: { label: '进行中', color: '#1890ff' },
  COMPLETED: { label: '已完成', color: '#67c23a' },
  CLOSED: { label: '已关闭', color: '#606266' },
  SUPPLEMENTING: { label: '补报中', color: '#e6a23c' },
};

type FilterValue = 'ALL' | 'COMPLETED' | 'CLOSED';

export default function ProcessTaskHistoryScreen() {
  const navigation = useNavigation<Props['navigation']>();
  const [tasks, setTasks] = useState<ProcessTaskItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [filter, setFilter] = useState<FilterValue>('ALL');
  const [error, setError] = useState<string | null>(null);

  const loadTasks = useCallback(async () => {
    try {
      const res = await processTaskApiClient.getTasks({
        status: filter === 'ALL' ? undefined : filter,
        page: 1,
        size: 100,
      });
      let list: ProcessTaskItem[] = [];
      const result = res as { data?: { content?: ProcessTaskItem[] } | ProcessTaskItem[] };
      if (result?.data && 'content' in result.data && result.data.content) list = result.data.content;
      else if (Array.isArray(result?.data)) list = result.data;
      setTasks(list);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载历史记录失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [filter]);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      loadTasks();
    }, [loadTasks])
  );

  const onRefresh = () => { setRefreshing(true); loadTasks(); };

  const renderItem = ({ item }: { item: ProcessTaskItem }) => {
    const status = STATUS_CONFIG[item.status] || { label: item.status, color: '#909399' };
    const progress = item.plannedQuantity > 0
      ? Math.min((item.completedQuantity / item.plannedQuantity) * 100, 100)
      : 0;

    return (
      <Card
        testID={`task-history-item-${item.id}`}
        style={styles.card}
        onPress={() => navigation.navigate('ProcessTaskDetail', { taskId: item.id })}
      >
        <Card.Content>
          <View style={styles.headerRow}>
            <View style={{ flex: 1 }}>
              <Text variant="titleSmall" style={styles.processName}>{item.processName || '-'}</Text>
              <Text style={styles.product}>{item.productTypeName || '-'}</Text>
            </View>
            <Chip
              compact
              style={{ backgroundColor: status.color + '20' }}
              textStyle={{ color: status.color, fontSize: 11 }}
            >
              {status.label}
            </Chip>
          </View>

          <View style={styles.statsRow}>
            <Text style={styles.stat}>
              计划 <Text style={styles.statValue}>{item.plannedQuantity}</Text>
            </Text>
            <Text style={styles.stat}>
              完成 <Text style={[styles.statValue, { color: '#67c23a' }]}>{item.completedQuantity}</Text>
            </Text>
            <Text style={styles.stat}>
              进度 <Text style={[styles.statValue, { color: theme.colors.primary }]}>{progress.toFixed(0)}%</Text>
            </Text>
          </View>

          {item.createdAt && (
            <Text style={styles.date}>{item.createdAt.substring(0, 10)}</Text>
          )}
        </Card.Content>
      </Card>
    );
  };

  return (
    <ScreenWrapper testID="process-task-history" edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.BackAction testID="task-history-back" onPress={() => navigation.goBack()} />
        <Appbar.Content title="工序历史" titleStyle={{ fontWeight: '600' }} />
      </Appbar.Header>

      <View style={styles.filterRow}>
        <SegmentedButtons
          value={filter}
          onValueChange={v => setFilter(v as FilterValue)}
          buttons={[
            { value: 'ALL', label: '全部' },
            { value: 'COMPLETED', label: '已完成' },
            { value: 'CLOSED', label: '已关闭' },
          ]}
          density="small"
        />
      </View>

      {loading ? (
        <ActivityIndicator style={{ flex: 1 }} size="large" />
      ) : (
        <FlatList
          data={tasks}
          keyExtractor={item => item.id}
          renderItem={renderItem}
          contentContainerStyle={styles.list}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
          ListEmptyComponent={
            <Text style={styles.empty}>{error || '暂无历史记录'}</Text>
          }
        />
      )}
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  filterRow: { paddingHorizontal: 16, paddingVertical: 8, backgroundColor: '#fff' },
  list: { padding: 16, paddingBottom: 32 },
  empty: { textAlign: 'center', color: '#999', marginTop: 40 },
  card: { marginBottom: 10, borderRadius: 12, backgroundColor: '#fff', elevation: 1 },
  headerRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  processName: { fontWeight: '600', color: '#333' },
  product: { fontSize: 12, color: '#999', marginTop: 2 },
  statsRow: { flexDirection: 'row', gap: 16, marginTop: 4 },
  stat: { fontSize: 13, color: '#666' },
  statValue: { fontWeight: '600', color: '#333' },
  date: { fontSize: 11, color: '#bbb', marginTop: 6 },
});
