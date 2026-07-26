import React, { useCallback, useMemo, useState } from 'react';
import {
  FlatList,
  RefreshControl,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Button, SegmentedButtons, TouchableRipple } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { labelQcApi } from '../../services/api/labelQcApi';
import { useAuthStore } from '../../store/authStore';
import {
  LabelQcTaskStatus,
  LabelQcTaskSummary,
} from '../../types/labelQc';
import {
  QI_COLORS,
  QualityInspectorStackParamList,
} from '../../types/qualityInspector';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;
type QueueFilter = 'REVIEW' | 'AI' | 'DONE';

const FILTER_STATUSES: Record<QueueFilter, LabelQcTaskStatus[]> = {
  REVIEW: ['NEEDS_REVIEW', 'ANALYSIS_FAILED'],
  AI: ['QUEUED', 'ANALYZING'],
  DONE: ['REVIEWED'],
};

const STATUS_COPY: Record<
  LabelQcTaskStatus,
  { label: string; color: string; background: string }
> = {
  DRAFT: { label: '草稿', color: '#725C15', background: '#FFF8D8' },
  UPLOADING: { label: '上传中', color: '#174A7E', background: '#EAF3FF' },
  QUEUED: { label: '排队中', color: '#174A7E', background: '#EAF3FF' },
  ANALYZING: { label: 'AI 初筛中', color: '#174A7E', background: '#EAF3FF' },
  NEEDS_REVIEW: { label: '待人工审核', color: '#8A5100', background: '#FFF0D2' },
  REVIEWED: { label: '已完成', color: '#08795A', background: '#DCF8EF' },
  ANALYSIS_FAILED: { label: 'AI 失败·人工检查', color: '#A12D23', background: '#FFE9E6' },
};

const getErrorMessage = (error: unknown): string => {
  const responseMessage = (
    error as { response?: { data?: { message?: string } } }
  )?.response?.data?.message;
  if (responseMessage) return responseMessage;
  if (error instanceof Error && error.message) return error.message;
  return '待审核任务加载失败，请检查网络后重试';
};

export default function QILabelQcQueueScreen() {
  const navigation = useNavigation<NavigationProp>();
  const insets = useSafeAreaInsets();
  const factoryId = useAuthStore((state) => state.user?.factoryId);
  const [filter, setFilter] = useState<QueueFilter>('REVIEW');
  const [tasks, setTasks] = useState<LabelQcTaskSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (asRefresh = false) => {
      if (!factoryId) {
        setError('登录信息缺少工厂，请重新登录');
        setLoading(false);
        return;
      }
      try {
        if (asRefresh) setRefreshing(true);
        else setLoading(true);
        setError(null);
        const page = await labelQcApi.listTasks(
          { statuses: FILTER_STATUSES[filter], page: 1, size: 50 },
          factoryId,
        );
        setTasks(page.content);
      } catch (loadError) {
        setError(getErrorMessage(loadError));
      } finally {
        setLoading(false);
        setRefreshing(false);
      }
    },
    [factoryId, filter],
  );

  useFocusEffect(
    useCallback(() => {
      void load();
    }, [load]),
  );

  const emptyCopy = useMemo(() => {
    if (filter === 'REVIEW') {
      return {
        icon: 'checkmark-done-circle-outline' as const,
        title: '当前没有待审核任务',
        body: '新任务完成 AI 初筛后会自动出现在这里。',
      };
    }
    if (filter === 'AI') {
      return {
        icon: 'sparkles-outline' as const,
        title: '当前没有初筛中的任务',
        body: '拍检提交后可以在这里查看 AI 处理进度。',
      };
    }
    return {
      icon: 'archive-outline' as const,
      title: '还没有已完成记录',
      body: '人工审核提交后会保留在这里。',
    };
  }, [filter]);

  const openTask = (task: LabelQcTaskSummary) => {
    if (['NEEDS_REVIEW', 'ANALYSIS_FAILED', 'REVIEWED'].includes(task.status)) {
      navigation.navigate('QILabelQcReview', { taskId: task.id });
      return;
    }
    navigation.navigate('QILabelQcSubmitted', {
      taskId: task.id,
      skuCode: task.skuCode,
      skuName: task.skuName,
      batchNumber: task.batchNumber,
      productionDate: task.productionDate,
    });
  };

  return (
    <View style={styles.screen} testID="qi-label-qc-queue-screen">
      <View style={styles.headerCard}>
        <View style={styles.headerCopy}>
          <Text style={styles.title}>标签拍检</Text>
          <Text style={styles.subtitle}>AI 先找疑点，人工逐图给最终结论</Text>
        </View>
        <Button
          mode="contained"
          icon="camera-plus-outline"
          buttonColor={QI_COLORS.primary}
          onPress={() => navigation.navigate('QILabelQcCreate')}
          testID="qi-label-qc-queue-create-button"
        >
          新建拍检
        </Button>
      </View>

      <SegmentedButtons
        value={filter}
        onValueChange={(value) => setFilter(value as QueueFilter)}
        style={styles.filters}
        buttons={[
          { value: 'REVIEW', label: '待我审核' },
          { value: 'AI', label: 'AI 初筛中' },
          { value: 'DONE', label: '已完成' },
        ]}
      />

      {error && (
        <View style={styles.errorCard}>
          <Ionicons name="cloud-offline-outline" size={22} color={QI_COLORS.danger} />
          <Text style={styles.errorText}>{error}</Text>
          <Button mode="text" onPress={() => void load()}>
            重试
          </Button>
        </View>
      )}

      <FlatList
        data={tasks}
        keyExtractor={(task) => task.id}
        contentContainerStyle={[
          styles.listContent,
          !tasks.length && styles.emptyListContent,
          { paddingBottom: insets.bottom + 24 },
        ]}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            tintColor={QI_COLORS.primary}
            onRefresh={() => void load(true)}
          />
        }
        renderItem={({ item }) => {
          const statusCopy = STATUS_COPY[item.status];
          return (
            <TouchableRipple
              style={styles.taskCard}
              onPress={() => openTask(item)}
              borderless
              accessibilityRole="button"
              accessibilityLabel={`${item.skuName}，${statusCopy.label}`}
              testID={`qi-label-qc-task-${item.id}`}
            >
              <View>
                <View style={styles.taskHeader}>
                  <View style={styles.taskTitleWrap}>
                    <Text style={styles.taskTitle} numberOfLines={1}>
                      {item.skuName}
                    </Text>
                    <Text style={styles.taskSku}>{item.skuCode}</Text>
                  </View>
                  <View style={[styles.statusBadge, { backgroundColor: statusCopy.background }]}>
                    <Text style={[styles.statusText, { color: statusCopy.color }]}>
                      {statusCopy.label}
                    </Text>
                  </View>
                </View>
                <View style={styles.metaRow}>
                  <Text style={styles.metaText}>批次 {item.batchNumber}</Text>
                  <Text style={styles.metaText}>{item.productionDate}</Text>
                </View>
                <View style={styles.summaryRow}>
                  <Text style={styles.summaryText}>{item.photoCount} 张照片</Text>
                  <Text style={item.aiCandidateCount > 0 ? styles.warningText : styles.summaryText}>
                    AI 疑点 {item.aiCandidateCount} 处
                  </Text>
                  <Ionicons name="chevron-forward" size={20} color={QI_COLORS.textSecondary} />
                </View>
              </View>
            </TouchableRipple>
          );
        }}
        ListEmptyComponent={
          !loading && !error ? (
            <View style={styles.emptyState}>
              <Ionicons name={emptyCopy.icon} size={54} color="#75A99A" />
              <Text style={styles.emptyTitle}>{emptyCopy.title}</Text>
              <Text style={styles.emptyBody}>{emptyCopy.body}</Text>
            </View>
          ) : null
        }
      />
      {loading && (
        <View style={styles.loadingOverlay}>
          <Text style={styles.loadingText}>正在加载任务…</Text>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: QI_COLORS.background },
  headerCard: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingTop: 14,
    paddingBottom: 10,
    backgroundColor: QI_COLORS.card,
  },
  headerCopy: { flex: 1, paddingRight: 12 },
  title: { fontSize: 21, fontWeight: '800', color: QI_COLORS.text },
  subtitle: { marginTop: 3, fontSize: 12, color: QI_COLORS.textSecondary },
  filters: { marginHorizontal: 16, marginVertical: 12 },
  errorCard: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: 16,
    marginBottom: 10,
    paddingLeft: 12,
    borderRadius: 12,
    backgroundColor: '#FFF0F0',
  },
  errorText: { flex: 1, marginLeft: 8, fontSize: 13, color: '#7D3030' },
  listContent: { paddingHorizontal: 16, gap: 10 },
  emptyListContent: { flexGrow: 1, justifyContent: 'center' },
  taskCard: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 14,
    padding: 15,
    overflow: 'hidden',
  },
  taskHeader: { flexDirection: 'row', alignItems: 'flex-start' },
  taskTitleWrap: { flex: 1, paddingRight: 10 },
  taskTitle: { fontSize: 16, fontWeight: '700', color: QI_COLORS.text },
  taskSku: { marginTop: 3, fontSize: 12, color: QI_COLORS.textSecondary },
  statusBadge: { borderRadius: 999, paddingHorizontal: 9, paddingVertical: 5 },
  statusText: { fontSize: 11, fontWeight: '700' },
  metaRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 13,
    paddingTop: 11,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: QI_COLORS.border,
  },
  metaText: { fontSize: 12, color: QI_COLORS.textSecondary },
  summaryRow: { flexDirection: 'row', alignItems: 'center', gap: 12, marginTop: 10 },
  summaryText: { fontSize: 12, color: QI_COLORS.textSecondary },
  warningText: { flex: 1, fontSize: 12, fontWeight: '700', color: '#A86100' },
  emptyState: { alignItems: 'center', paddingHorizontal: 30, paddingBottom: 70 },
  emptyTitle: { marginTop: 14, fontSize: 18, fontWeight: '700', color: QI_COLORS.text },
  emptyBody: {
    marginTop: 7,
    fontSize: 13,
    lineHeight: 20,
    color: QI_COLORS.textSecondary,
    textAlign: 'center',
  },
  loadingOverlay: {
    ...StyleSheet.absoluteFillObject,
    top: 118,
    alignItems: 'center',
    justifyContent: 'center',
    pointerEvents: 'none',
  },
  loadingText: {
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 999,
    overflow: 'hidden',
    color: QI_COLORS.textSecondary,
    backgroundColor: 'rgba(255,255,255,0.9)',
  },
});
