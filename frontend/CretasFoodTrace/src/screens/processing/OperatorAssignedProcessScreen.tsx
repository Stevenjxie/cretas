import React, { useCallback, useMemo, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { ActivityIndicator, Appbar, IconButton, Text } from 'react-native-paper';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { NeoButton, ScreenWrapper } from '../../components/ui';
import { processTaskApiClient } from '../../services/api/processTaskApiClient';
import { theme } from '../../theme';
import {
  extractProcessTaskList,
  getTaskBatchId,
  getTaskWorkProcessTaskId,
  pickCurrentReportTask,
} from '../../utils/processTaskFlow';

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

export default function OperatorAssignedProcessScreen() {
  const navigation = useNavigation<NavigationProp>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [taskCount, setTaskCount] = useState(0);

  const loadAssignedTask = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await processTaskApiClient.getActiveTasks();
      const tasks = extractProcessTaskList(result);
      setTaskCount(tasks.length);
      const currentTask = pickCurrentReportTask(tasks);
      if (currentTask) {
        const batchId = getTaskBatchId(currentTask);
        if (batchId == null) {
          setError('当前工序没有绑定生产批次，请联系管理员重新转批次。');
          return;
        }
        navigation.replace('YieldStepReport', {
          batchId,
          assignedWorkProcessTaskId: getTaskWorkProcessTaskId(currentTask) ?? undefined,
          assignedProcessOrder: currentTask.processOrder,
          autoAssigned: true,
        });
        return;
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载当前工序失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [navigation]);

  useFocusEffect(
    useCallback(() => {
      loadAssignedTask();
    }, [loadAssignedTask])
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
