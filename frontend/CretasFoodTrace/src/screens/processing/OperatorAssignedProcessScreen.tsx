import React, { useCallback, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Appbar, Card, Chip, IconButton, Text, TouchableRipple } from 'react-native-paper';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { NeoButton, ScreenWrapper } from '../../components/ui';
import { yieldReportApi, WorkProcessTask, WorkProcessTaskStatus } from '../../services/api/yieldReportApi';
import { useAuthStore } from '../../store/authStore';
import { theme } from '../../theme';

type OperatorAssignedProcessStackParamList = {
  OperatorAssignedProcess: undefined;
  YieldStepReport: {
    batchId: number;
    batchNumber?: string;
    assignedWorkProcessTaskId?: number;
    assignedProcessOrder?: number;
    autoAssigned?: boolean;
  };
};

type NavigationProp = NativeStackNavigationProp<
  OperatorAssignedProcessStackParamList,
  'OperatorAssignedProcess'
>;

const ACTIVE_STATUSES: WorkProcessTaskStatus[] = ['IN_PROGRESS', 'PENDING'];

/**
 * T157: 一个可报工批次条目 (跨批次/跨产品选择屏用).
 * 当小组长在 2+ 批次/产品里都有可报工序时, 每批次一张卡, 防呆 Rule 2 (带产品+批次 context).
 */
interface ReportableBatchOption {
  batchId: number;
  productTypeName: string | null;   // null = 批次/产品已删除 (禁假数据, UI 兜底显示批次号)
  batchNumber: string | null;       // null = 批次已删除
  currentReportableTask: WorkProcessTask;  // 该批次当前可报的那道工序
  myTaskCount: number;              // 我在该批次的待报工序数 (PENDING+IN_PROGRESS)
}

function statusRank(status: WorkProcessTaskStatus): number {
  if (status === 'IN_PROGRESS') return 0;
  if (status === 'PENDING') return 1;
  return 2;
}

function isTerminalStatus(status: WorkProcessTaskStatus): boolean {
  return status === 'COMPLETED' || status === 'SKIPPED' || status === 'CANCELLED';
}

function compareAssignedTasks(a: WorkProcessTask, b: WorkProcessTask): number {
  return (
    b.productionBatchId - a.productionBatchId
    || statusRank(a.status) - statusRank(b.status)
    || a.processOrder - b.processOrder
    || a.id - b.id
  );
}

function uniqueTasks(tasks: WorkProcessTask[]): WorkProcessTask[] {
  const seen = new Set<number>();
  const out: WorkProcessTask[] = [];
  for (const task of tasks) {
    if (seen.has(task.id)) continue;
    seen.add(task.id);
    out.push(task);
  }
  return out;
}

export default function OperatorAssignedProcessScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { getUserId } = useAuthStore();
  const currentUserId = getUserId();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [taskCount, setTaskCount] = useState(0);
  // T157: 当 2+ 批次都有可报工序时, 渲染选择屏; 单批次仍走 replace 自动跳转 (零额外点击).
  const [options, setOptions] = useState<ReportableBatchOption[]>([]);

  const loadExactAssignedTasks = useCallback(async (assignedTo: number): Promise<WorkProcessTask[]> => {
    const chunks = await Promise.all(
      ACTIVE_STATUSES.map(async (status) => {
        const res = await yieldReportApi.listAssignedWorkProcessTasks({
          assignedTo,
          status,
          page: 1,
          size: 100,
        });
        if (!res.success) {
          throw new Error(res.message || '加载分配工序失败');
        }
        return res.data?.content ?? [];
      }),
    );
    return uniqueTasks(chunks.flat())
      .filter((task) => task.assignedTo === assignedTo)
      .sort(compareAssignedTasks);
  }, []);

  /**
   * T157: 不再 early-return 首个匹配; 而是遍历每个不同批次, 找出每批次"当前可报"的那道工序,
   * 构造 ReportableBatchOption 列表 (跨产品/批次都能看到, 不再静默死锁其他产品).
   */
  const findReportableBatchOptions = useCallback(
    async (assignedTasks: WorkProcessTask[], assignedTo: number): Promise<ReportableBatchOption[]> => {
      // 按批次去重 (assignedTasks 已按 productionBatchId 排序), 保留每批次第一条作为 context 来源.
      const seenBatch = new Set<number>();
      const batchOrder: WorkProcessTask[] = [];
      for (const task of assignedTasks) {
        if (seenBatch.has(task.productionBatchId)) continue;
        seenBatch.add(task.productionBatchId);
        batchOrder.push(task);
      }

      const result: ReportableBatchOption[] = [];
      for (const batchRep of batchOrder) {
        const batchRes = await yieldReportApi.listWorkProcessTasks(batchRep.productionBatchId);
        if (!batchRes.success) {
          throw new Error(batchRes.message || '加载批次工序链失败');
        }
        const batchTasks = [...(batchRes.data ?? [])].sort(
          (a, b) => a.processOrder - b.processOrder || a.id - b.id,
        );
        const firstOpenTask = batchTasks.find((task) => !isTerminalStatus(task.status));
        // 只有"当前第一道未完成工序"正好是我负责的, 才算这批次对我可报 (保持原 findCurrentReportableTask 语义).
        if (firstOpenTask && firstOpenTask.assignedTo === assignedTo) {
          const myTaskCount = batchTasks.filter(
            (t) => t.assignedTo === assignedTo && !isTerminalStatus(t.status),
          ).length;
          result.push({
            batchId: batchRep.productionBatchId,
            // 优先用批次工序链里的 enriched 名 (list 路径已透出), fallback 到 assigned chunk 的字段.
            productTypeName: firstOpenTask.productTypeName ?? batchRep.productTypeName ?? null,
            batchNumber: firstOpenTask.batchNumber ?? batchRep.batchNumber ?? null,
            currentReportableTask: firstOpenTask,
            myTaskCount,
          });
        }
      }
      return result;
    },
    [],
  );

  const loadAssignedTask = useCallback(async () => {
    setLoading(true);
    setError(null);
    setOptions([]);
    try {
      if (currentUserId == null) {
        setError('无法识别当前账号，请退出后重新登录');
        return;
      }
      const tasks = await loadExactAssignedTasks(currentUserId);
      setTaskCount(tasks.length);
      const batchOptions = await findReportableBatchOptions(tasks, currentUserId);

      const only = batchOptions.length === 1 ? batchOptions[0] : null;
      if (only) {
        // 单批次可报 → 保持原快路径: replace 自动跳转, 不暴露选批次 (小组长零额外点击).
        navigation.replace('YieldStepReport', {
          batchId: only.batchId,
          assignedWorkProcessTaskId: only.currentReportableTask.id,
          assignedProcessOrder: only.currentReportableTask.processOrder,
          autoAssigned: true,
        });
        return;
      }
      // 0 个 → 走空状态文案; 2+ 个 → 渲染选择屏.
      setOptions(batchOptions);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载当前工序失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [currentUserId, findReportableBatchOptions, loadExactAssignedTasks, navigation]);

  useFocusEffect(
    useCallback(() => {
      loadAssignedTask();
    }, [loadAssignedTask]),
  );

  // T157: 选择某批次 → 用 navigate (非 replace), 这样报工完返回时回到选择屏可切换其他产品.
  const onSelectBatch = useCallback(
    (option: ReportableBatchOption) => {
      navigation.navigate('YieldStepReport', {
        batchId: option.batchId,
        batchNumber: option.batchNumber ?? undefined,
        assignedWorkProcessTaskId: option.currentReportableTask.id,
        assignedProcessOrder: option.currentReportableTask.processOrder,
        autoAssigned: true,
      });
    },
    [navigation],
  );

  const message = useMemo(() => {
    if (error) return error;
    if (taskCount > 0) return '当前没有可以报工的工序，请等上一道完成后再刷新。';
    return '当前没有分配给你的工序，请联系管理员分配。';
  }, [error, taskCount]);

  const hasOptions = options.length > 0;

  return (
    <ScreenWrapper testID="operator-assigned-process" edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.Content
          title={hasOptions ? '选择报工批次' : '当前工序'}
          titleStyle={{ fontWeight: '600' }}
        />
        <Appbar.Action testID="operator-assigned-refresh" icon="refresh" onPress={loadAssignedTask} />
      </Appbar.Header>

      {loading ? (
        <View style={styles.centerContainer}>
          <ActivityIndicator size="large" />
          <Text style={styles.primaryText}>正在打开你的工序...</Text>
        </View>
      ) : hasOptions ? (
        // T157: 2+ 批次可报 → 选择屏 (防呆 Rule 2: 每卡带产品名 + 批次号 + 待报工序数 + 当前可报工序名).
        <ScrollView
          testID="operator-batch-selector"
          contentContainerStyle={styles.listContainer}
        >
          <Text style={styles.selectorHint}>你在多个批次都有待报工序，请选择要报工的批次：</Text>
          {options.map((opt) => (
            <Card key={opt.batchId} style={styles.batchCard} mode="outlined">
              <TouchableRipple
                testID={`operator-batch-option-${opt.batchId}`}
                onPress={() => onSelectBatch(opt)}
                borderless
                style={styles.ripple}
              >
                <Card.Content style={styles.cardContent}>
                  <View style={styles.cardHeaderRow}>
                    <Text style={styles.productName} numberOfLines={1}>
                      {opt.productTypeName ?? '未命名产品'}
                    </Text>
                    <Chip compact style={styles.countChip} textStyle={styles.countChipText}>
                      待报 {opt.myTaskCount} 道
                    </Chip>
                  </View>
                  <Text style={styles.batchNo} numberOfLines={1}>
                    批次号：{opt.batchNumber ?? `#${opt.batchId}`}
                  </Text>
                  <Text style={styles.currentProcess} numberOfLines={1}>
                    当前可报：第 {opt.currentReportableTask.processOrder} 道
                    {opt.currentReportableTask.processName ? ` · ${opt.currentReportableTask.processName}` : ''}
                  </Text>
                </Card.Content>
              </TouchableRipple>
            </Card>
          ))}
        </ScrollView>
      ) : (
        <View style={styles.centerContainer}>
          <IconButton icon={error ? 'alert-circle-outline' : 'clipboard-clock-outline'} size={52} />
          <Text style={styles.primaryText}>{message}</Text>
          <NeoButton testID="operator-assigned-retry" onPress={loadAssignedTask} style={styles.button}>
            刷新
          </NeoButton>
        </View>
      )}
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  centerContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  primaryText: {
    marginTop: 12,
    textAlign: 'center',
    fontSize: 16,
    lineHeight: 24,
    color: theme.colors.onSurface,
  },
  button: {
    marginTop: 18,
    minWidth: 120,
  },
  listContainer: {
    padding: 16,
    paddingBottom: 32,
  },
  selectorHint: {
    fontSize: 14,
    lineHeight: 20,
    color: theme.colors.onSurfaceVariant,
    marginBottom: 12,
  },
  batchCard: {
    marginBottom: 12,
    backgroundColor: theme.colors.surface,
  },
  ripple: {
    borderRadius: 12,
  },
  cardContent: {
    paddingVertical: 14,
  },
  cardHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  productName: {
    flex: 1,
    minWidth: 0,
    fontSize: 17,
    fontWeight: '600',
    color: theme.colors.onSurface,
  },
  countChip: {
    marginLeft: 8,
    backgroundColor: theme.colors.primaryContainer,
  },
  countChipText: {
    fontSize: 12,
    color: theme.colors.onPrimaryContainer,
  },
  batchNo: {
    marginTop: 6,
    fontSize: 14,
    color: theme.colors.onSurfaceVariant,
  },
  currentProcess: {
    marginTop: 4,
    fontSize: 14,
    color: theme.colors.primary,
  },
});
