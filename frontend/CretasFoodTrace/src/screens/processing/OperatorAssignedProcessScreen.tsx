import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Appbar, Card, Chip, IconButton, Text, TouchableRipple } from 'react-native-paper';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { NeoButton, ScreenWrapper } from '../../components/ui';
import { AppDialogHost } from '../../components/ui/AppDialog';
import { yieldReportApi, WorkProcessTask, WorkProcessTaskStatus } from '../../services/api/yieldReportApi';
import { shortageAlertApiClient } from '../../services/api/shortageAlertApiClient';
import { useAuthStore } from '../../store/authStore';
import { theme } from '../../theme';
import type { ShortageAlertDetailParams } from './ShortageAlertDetailScreen';
import { isTaskReportComplete } from '../../utils/operatorAssignedProcess';

type OperatorAssignedProcessStackParamList = {
  OperatorAssignedProcess: undefined;
  YieldStepReport: {
    batchId: number;
    batchNumber?: string;
    assignedWorkProcessTaskId?: number;
    assignedProcessOrder?: number;
    autoAssigned?: boolean;
  };
  ShortageAlertDetail: ShortageAlertDetailParams;
};

type NavigationProp = NativeStackNavigationProp<
  OperatorAssignedProcessStackParamList,
  'OperatorAssignedProcess'
>;

const ACTIVE_STATUSES: WorkProcessTaskStatus[] = ['IN_PROGRESS', 'PENDING'];

/**
 * 操作员的一个批次工序条目 (任务列表用).
 * reportable=true: 这批次当前第一道未完成工序正好是我的 → 可点进去报工.
 * reportable=false: 我在这批次有待报工序, 但卡在上一道(别人/我自己的更前一道)未完成 → 展示"等待中".
 * (2026-06-09: 从"只显示可报"扩成"显示全部分配批次+状态", 让操作员看到全部活 + 知道在等什么.)
 */
interface BatchEntry {
  batchId: number;
  productTypeName: string | null;
  productTypeId: string | null;                   // N1b: BOM缺料查询用
  batchNumber: string | null;
  reportable: boolean;
  currentReportableTask: WorkProcessTask | null;  // reportable 时 = 当前可报的那道(我的)
  blockingProcessName: string | null;             // 非 reportable 时 = 当前卡住的那道工序名(在等它)
  blockingProcessOrder: number | null;
  blockingAssignedToName: string | null;          // 卡住那道的负责人姓名 (在等谁做)
  blockingStatus: WorkProcessTaskStatus | null;   // 卡住那道的实时状态 (未开始/进行中)
  myNextProcessName: string | null;               // 我在这批次最近一道待报工序名
  myNextProcessOrder: number | null;
  myTaskCount: number;                            // 我在这批次的待报工序数
}

function statusRank(status: WorkProcessTaskStatus): number {
  if (status === 'IN_PROGRESS') return 0;
  if (status === 'PENDING') return 1;
  return 2;
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
  const [entries, setEntries] = useState<BatchEntry[]>([]);
  // N1b: batchId → shortfallLeafCount (populated asynchronously after entries load; 0 = no shortage)
  const [shortfallMap, setShortfallMap] = useState<Record<number, number>>({});

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
   * 遍历操作员有待报工序的每个批次, 判定该批次对我是"可报"还是"等待中", 构造完整条目列表.
   * 可报排前面, 等待排后面.
   */
  const buildBatchEntries = useCallback(
    async (assignedTasks: WorkProcessTask[], assignedTo: number): Promise<BatchEntry[]> => {
      const seenBatch = new Set<number>();
      const batchOrder: WorkProcessTask[] = [];
      for (const task of assignedTasks) {
        if (seenBatch.has(task.productionBatchId)) continue;
        seenBatch.add(task.productionBatchId);
        batchOrder.push(task);
      }

      const out: BatchEntry[] = [];
      for (const batchRep of batchOrder) {
        const batchRes = await yieldReportApi.listWorkProcessTasks(batchRep.productionBatchId);
        if (!batchRes.success) {
          throw new Error(batchRes.message || '加载批次工序链失败');
        }
        const batchTasks = [...(batchRes.data ?? [])].sort(
          (a, b) => a.processOrder - b.processOrder || a.id - b.id,
        );
        // 获取 yield 数据用于判断哨兵工序是否已报工 (fail-open: 失败时 yieldData=null 退回纯 status 判断)
        const yieldRes = await yieldReportApi.getYield(batchRep.productionBatchId).catch(() => null);
        const yieldData = yieldRes?.success ? yieldRes.data : null;
        const myOpen = batchTasks.filter(
          (t) => t.assignedTo === assignedTo && !isTaskReportComplete(t, yieldData),
        );
        const myNext = myOpen[0];
        if (!myNext) continue; // 我在这批次已无待报工序
        const firstOpenTask = batchTasks.find((task) => !isTaskReportComplete(task, yieldData)) ?? null;
        const reportable = !!firstOpenTask && firstOpenTask.assignedTo === assignedTo;
        out.push({
          batchId: batchRep.productionBatchId,
          productTypeName:
            firstOpenTask?.productTypeName ?? batchRep.productTypeName ?? myNext.productTypeName ?? null,
          productTypeId:
            firstOpenTask?.productTypeId ?? batchRep.productTypeId ?? myNext.productTypeId ?? null,
          batchNumber: firstOpenTask?.batchNumber ?? batchRep.batchNumber ?? myNext.batchNumber ?? null,
          reportable,
          currentReportableTask: reportable ? firstOpenTask : null,
          blockingProcessName: reportable ? null : firstOpenTask?.processName ?? null,
          blockingProcessOrder: reportable ? null : firstOpenTask?.processOrder ?? null,
          blockingAssignedToName: reportable ? null : firstOpenTask?.assignedToName ?? null,
          blockingStatus: reportable ? null : firstOpenTask?.status ?? null,
          myNextProcessName: myNext.processName ?? null,
          myNextProcessOrder: myNext.processOrder,
          myTaskCount: myOpen.length,
        });
      }
      // 可报的排前面
      out.sort((a, b) => (a.reportable === b.reportable ? 0 : a.reportable ? -1 : 1));
      return out;
    },
    [],
  );

  const loadAssignedTask = useCallback(async () => {
    setLoading(true);
    setError(null);
    setEntries([]);
    try {
      if (currentUserId == null) {
        setError('无法识别当前账号，请退出后重新登录');
        return;
      }
      const tasks = await loadExactAssignedTasks(currentUserId);
      const batchEntries = await buildBatchEntries(tasks, currentUserId);

      // 仅当总共就 1 个批次条目且可报 → 零点击自动跳; 否则展示列表(让操作员看到全部, 含等待中).
      const only = batchEntries.length === 1 ? batchEntries[0] : null;
      if (only && only.reportable && only.currentReportableTask) {
        navigation.replace('YieldStepReport', {
          batchId: only.batchId,
          assignedWorkProcessTaskId: only.currentReportableTask.id,
          assignedProcessOrder: only.currentReportableTask.processOrder,
          autoAssigned: true,
        });
        return;
      }
      setEntries(batchEntries);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载当前工序失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [currentUserId, buildBatchEntries, loadExactAssignedTasks, navigation]);

  useFocusEffect(
    useCallback(() => {
      loadAssignedTask();
    }, [loadAssignedTask]),
  );

  // N1b: 异步加载每个批次的 BOM 缺料情况 (不阻断主加载流程)
  const { getFactoryId } = useAuthStore();
  useEffect(() => {
    if (entries.length === 0) {
      setShortfallMap({});
      return;
    }
    let cancelled = false;
    const fetchAll = async () => {
      const updates: Record<number, number> = {};
      await Promise.all(
        entries.map(async (entry) => {
          const ptid = entry.productTypeId;
          if (!ptid) return;
          try {
            const qty =
              (entry.currentReportableTask?.plannedQuantity) ?? 1;
            const { result } = await shortageAlertApiClient.fetchBomShortage(
              ptid,
              qty,
              getFactoryId() ?? undefined,
            );
            if (!cancelled) {
              updates[entry.batchId] = result.shortfallLeafCount;
            }
          } catch {
            // 缺料查询失败时不影响主界面；badge 静默不显示
          }
        }),
      );
      if (!cancelled) {
        setShortfallMap((prev) => ({ ...prev, ...updates }));
      }
    };
    void fetchAll();
    return () => { cancelled = true; };
  }, [entries, getFactoryId]);

  // N1b: 查看缺料明细
  const onViewShortage = useCallback(
    (entry: BatchEntry) => {
      if (!entry.productTypeId) return;
      const params: ShortageAlertDetailParams = {
        batchId: entry.batchId,
        batchNumber: entry.batchNumber ?? undefined,
        productTypeName: entry.productTypeName ?? undefined,
        productTypeId: entry.productTypeId,
        plannedQuantity:
          entry.currentReportableTask?.plannedQuantity ?? undefined,
        plannedUnit:
          entry.currentReportableTask?.plannedUnit ?? undefined,
      };
      navigation.navigate('ShortageAlertDetail', params);
    },
    [navigation],
  );

  // 选可报批次 → 用 navigate (非 replace), 这样报工完返回时回到列表可切换其他工序.
  const onSelectBatch = useCallback(
    (entry: BatchEntry) => {
      if (!entry.reportable || !entry.currentReportableTask) return;
      navigation.navigate('YieldStepReport', {
        batchId: entry.batchId,
        batchNumber: entry.batchNumber ?? undefined,
        assignedWorkProcessTaskId: entry.currentReportableTask.id,
        assignedProcessOrder: entry.currentReportableTask.processOrder,
        autoAssigned: true,
      });
    },
    [navigation],
  );

  const message = useMemo(() => {
    if (error) return error;
    return '当前没有分配给你的工序，请联系管理员分配。';
  }, [error]);

  const hasEntries = entries.length > 0;
  const reportableCount = useMemo(() => entries.filter((e) => e.reportable).length, [entries]);

  return (
    <ScreenWrapper testID="operator-assigned-process" edges={['top']} backgroundColor={theme.colors.background}>
      <AppDialogHost />
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.Content
          title={hasEntries ? '我的工序任务' : '当前工序'}
          titleStyle={{ fontWeight: '600' }}
        />
        <Appbar.Action testID="operator-assigned-refresh" icon="refresh" onPress={loadAssignedTask} />
      </Appbar.Header>

      {loading ? (
        <View style={styles.centerContainer}>
          <ActivityIndicator size="large" />
          <Text style={styles.primaryText}>正在打开你的工序...</Text>
        </View>
      ) : hasEntries ? (
        <ScrollView testID="operator-batch-selector" contentContainerStyle={styles.listContainer}>
          <Text style={styles.selectorHint}>
            {reportableCount > 0
              ? `你有 ${reportableCount} 道可以报工，点击进入；其余在等上一道完成。`
              : '你的工序都在等上一道完成，完成后这里会变成可报工（可下拉刷新）。'}
          </Text>
          {entries.map((entry) => {
            const tappable = entry.reportable && !!entry.currentReportableTask;
            const shortfallCount = shortfallMap[entry.batchId] ?? 0;
            const hasShortage = shortfallCount > 0;
            const card = (
              <Card.Content style={styles.cardContent}>
                <View style={styles.cardHeaderRow}>
                  <Text style={styles.productName} numberOfLines={1}>
                    {entry.productTypeName ?? '未命名产品'}
                  </Text>
                  <View style={styles.chipRow}>
                    {tappable ? (
                      <Chip compact style={styles.okChip} textStyle={styles.okChipText}>
                        可报工 ▶
                      </Chip>
                    ) : (
                      <Chip compact style={styles.waitChip} textStyle={styles.waitChipText}>
                        等待中
                      </Chip>
                    )}
                    {/* N1b: 缺料 badge — 仅显示，不阻断开工 (fool-proof Rule 5) */}
                    {hasShortage && entry.productTypeId ? (
                      <TouchableRipple
                        testID={`shortage-badge-${entry.batchId}`}
                        onPress={() => onViewShortage(entry)}
                        style={styles.shortageBadgeRipple}
                        borderless
                      >
                        <Chip
                          compact
                          icon="alert-circle-outline"
                          style={styles.shortageChip}
                          textStyle={styles.shortageChipText}
                        >
                          ⚠ 缺料
                        </Chip>
                      </TouchableRipple>
                    ) : null}
                  </View>
                </View>
                <Text style={styles.batchNo} numberOfLines={1}>
                  批次号：{entry.batchNumber ?? `#${entry.batchId}`}
                </Text>
                {tappable ? (
                  <Text style={styles.currentProcess} numberOfLines={1}>
                    现在可报：第 {entry.currentReportableTask!.processOrder} 道
                    {entry.currentReportableTask!.processName ? ` · ${entry.currentReportableTask!.processName}` : ''}
                  </Text>
                ) : (
                  <Text style={styles.waitProcess} numberOfLines={3}>
                    我的工序：第 {entry.myNextProcessOrder} 道
                    {entry.myNextProcessName ? ` · ${entry.myNextProcessName}` : ''}
                    {'\n'}⏳ 在等第 {entry.blockingProcessOrder} 道
                    {entry.blockingProcessName ? ` · ${entry.blockingProcessName}` : ''} 完成
                    {'\n'}负责人：{entry.blockingAssignedToName ?? '未分配'}
                    {entry.blockingStatus
                      ? ` · ${entry.blockingStatus === 'IN_PROGRESS' ? '进行中' : '未开始'}`
                      : ''}
                  </Text>
                )}
                {/* N1b: 缺料提示行 (点击跳查看明细) */}
                {hasShortage && entry.productTypeId ? (
                  <TouchableRipple
                    testID={`shortage-row-${entry.batchId}`}
                    onPress={() => onViewShortage(entry)}
                    style={styles.shortageRow}
                  >
                    <Text style={styles.shortageRowText}>
                      ⚠ {shortfallCount} 种原料库存不足，点击查看缺口明细 →
                    </Text>
                  </TouchableRipple>
                ) : null}
              </Card.Content>
            );
            return (
              <Card
                key={entry.batchId}
                style={[
                  styles.batchCard,
                  !tappable && styles.batchCardWaiting,
                  hasShortage && styles.batchCardShortage,
                ]}
                mode="outlined"
              >
                {tappable ? (
                  <TouchableRipple
                    testID={`operator-batch-option-${entry.batchId}`}
                    onPress={() => onSelectBatch(entry)}
                    borderless
                    style={styles.ripple}
                  >
                    {card}
                  </TouchableRipple>
                ) : (
                  <View testID={`operator-batch-waiting-${entry.batchId}`}>{card}</View>
                )}
              </Card>
            );
          })}
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
  batchCardWaiting: {
    opacity: 0.7,
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
  chipRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    flexShrink: 0,
  },
  productName: {
    flex: 1,
    minWidth: 0,
    fontSize: 17,
    fontWeight: '600',
    color: theme.colors.onSurface,
  },
  okChip: {
    marginLeft: 8,
    backgroundColor: theme.colors.primaryContainer,
  },
  okChipText: {
    fontSize: 12,
    color: theme.colors.onPrimaryContainer,
  },
  waitChip: {
    marginLeft: 8,
    backgroundColor: theme.colors.surfaceVariant,
  },
  waitChipText: {
    fontSize: 12,
    color: theme.colors.onSurfaceVariant,
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
  waitProcess: {
    marginTop: 4,
    fontSize: 14,
    lineHeight: 20,
    color: theme.colors.onSurfaceVariant,
  },
  // N1b: 缺料相关样式
  batchCardShortage: {
    borderColor: theme.colors.error,
    borderWidth: 1.5,
  },
  shortageBadgeRipple: {
    borderRadius: 16,
  },
  shortageChip: {
    backgroundColor: '#FFEBEE',
  },
  shortageChipText: {
    fontSize: 12,
    color: theme.colors.error,
    fontWeight: '600',
  },
  shortageRow: {
    marginTop: 8,
    paddingVertical: 8,
    paddingHorizontal: 10,
    borderRadius: 6,
    backgroundColor: '#FFF3F3',
    minHeight: 44,
    justifyContent: 'center',
  },
  shortageRowText: {
    fontSize: 14,
    color: theme.colors.error,
    fontWeight: '500',
    lineHeight: 20,
  },
});
