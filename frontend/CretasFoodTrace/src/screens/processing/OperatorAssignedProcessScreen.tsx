import React, { useCallback, useMemo, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { ActivityIndicator, Appbar, IconButton, Text } from 'react-native-paper';
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

  const findCurrentReportableTask = useCallback(
    async (assignedTasks: WorkProcessTask[], assignedTo: number): Promise<WorkProcessTask | null> => {
      for (const candidate of assignedTasks) {
        const batchRes = await yieldReportApi.listWorkProcessTasks(candidate.productionBatchId);
        if (!batchRes.success) {
          throw new Error(batchRes.message || '加载批次工序链失败');
        }
        const batchTasks = [...(batchRes.data ?? [])].sort((a, b) => a.processOrder - b.processOrder || a.id - b.id);
        const firstOpenTask = batchTasks.find((task) => !isTerminalStatus(task.status));
        if (firstOpenTask?.id === candidate.id && firstOpenTask.assignedTo === assignedTo) {
          return firstOpenTask;
        }
      }
      return null;
    },
    [],
  );

  const loadAssignedTask = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      if (currentUserId == null) {
        setError('无法识别当前账号，请退出后重新登录');
        return;
      }
      const tasks = await loadExactAssignedTasks(currentUserId);
      setTaskCount(tasks.length);
      const currentTask = await findCurrentReportableTask(tasks, currentUserId);
      if (currentTask) {
        navigation.replace('YieldStepReport', {
          batchId: currentTask.productionBatchId,
          assignedWorkProcessTaskId: currentTask.id,
          assignedProcessOrder: currentTask.processOrder,
          autoAssigned: true,
        });
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载当前工序失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [currentUserId, findCurrentReportableTask, loadExactAssignedTasks, navigation]);

  useFocusEffect(
    useCallback(() => {
      loadAssignedTask();
    }, [loadAssignedTask]),
  );

  const message = useMemo(() => {
    if (error) return error;
    if (taskCount > 0) return '当前没有可以报工的工序，请等上一道完成后再刷新。';
    return '当前没有分配给你的工序，请联系管理员分配。';
  }, [error, taskCount]);

  return (
    <ScreenWrapper testID="operator-assigned-process" edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.Content title="当前工序" titleStyle={{ fontWeight: '600' }} />
        <Appbar.Action testID="operator-assigned-refresh" icon="refresh" onPress={loadAssignedTask} />
      </Appbar.Header>

      <View style={styles.container}>
        {loading ? (
          <>
            <ActivityIndicator size="large" />
            <Text style={styles.primaryText}>正在打开你的工序...</Text>
          </>
        ) : (
          <>
            <IconButton icon={error ? 'alert-circle-outline' : 'clipboard-clock-outline'} size={52} />
            <Text style={styles.primaryText}>{message}</Text>
            <NeoButton testID="operator-assigned-retry" onPress={loadAssignedTask} style={styles.button}>
              刷新
            </NeoButton>
          </>
        )}
      </View>
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  container: {
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
});
