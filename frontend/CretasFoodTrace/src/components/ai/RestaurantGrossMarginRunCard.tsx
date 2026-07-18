import React, { useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import {
  replayGrossMarginDeclineRun,
  startGrossMarginDeclineRun,
  type RestaurantAgentRunSubscription,
} from '../../services/api/restaurantAgentRuns';
import {
  RESTAURANT_AGENT_RUN_ROUTE,
  type RestaurantAgentEventV1,
  type RestaurantAgentRunState,
  type RestaurantAgentTerminalOutcome,
} from '../../types/restaurantAgentRun';

interface RestaurantGrossMarginRunCardProps {
  factoryId: string;
  startDate: string;
  endDate: string;
}

const EVENT_LABELS: Record<RestaurantAgentEventV1['eventType'], string> = {
  RUN_STARTED: '运行已创建',
  ROUTE_SELECTED: '已选择固定毛利归因路线',
  PLAN_CREATED: '读取计划已持久化',
  STEP_STARTED: '开始读取数据',
  STEP_COMPLETED: '数据读取完成',
  STEP_FAILED: '数据读取失败',
  BUDGET_EXCEEDED: '运行预算已用尽',
  RUN_CANCELLED: '服务端记录运行取消',
  RUN_COMPLETED: '运行完成',
  RUN_FAILED: '运行失败',
};

const STATE_LABELS: Record<RestaurantAgentRunState, string> = {
  RUNNING: '运行中',
  COMPLETED: '分析完成',
  PARTIAL: '部分完成',
  FAILED: '分析失败',
  CANCELLED: '服务端已记录取消',
  BUDGET_EXCEEDED: '分析预算已用尽',
};

export function RestaurantGrossMarginRunCard({
  factoryId,
  startDate,
  endDate,
}: RestaurantGrossMarginRunCardProps) {
  const [events, setEvents] = useState<RestaurantAgentEventV1[]>([]);
  const [runState, setRunState] = useState<RestaurantAgentRunState | null>(null);
  const [outcome, setOutcome] = useState<RestaurantAgentTerminalOutcome | null>(null);
  const [failureCode, setFailureCode] = useState<string | null>(null);
  const [runId, setRunId] = useState<string | null>(null);
  const [status, setStatus] = useState('');
  const [receiving, setReceiving] = useState(false);
  const subscriptionRef = useRef<RestaurantAgentRunSubscription | null>(null);
  const stopRequestedRef = useRef(false);
  const lastSequenceRef = useRef(0);

  const mergeEvents = useCallback((incoming: RestaurantAgentEventV1[]) => {
    setEvents((previous) => {
      const bySequence = new Map(previous.map((event) => [event.sequence, event]));
      for (const event of incoming) bySequence.set(event.sequence, event);
      return Array.from(bySequence.values()).sort((a, b) => a.sequence - b.sequence);
    });
    lastSequenceRef.current = Math.max(
      lastSequenceRef.current,
      ...incoming.map((event) => event.sequence),
      0,
    );
  }, []);

  const stopReceiving = useCallback(() => {
    if (!receiving) return;
    stopRequestedRef.current = true;
    subscriptionRef.current?.stopReceiving();
    setReceiving(false);
    setStatus('已停止接收');
  }, [receiving]);

  useEffect(() => () => {
    stopRequestedRef.current = true;
    subscriptionRef.current?.stopReceiving();
  }, []);

  const startRun = useCallback(async () => {
    if (receiving) return;
    stopRequestedRef.current = false;
    lastSequenceRef.current = 0;
    setEvents([]);
    setRunState('RUNNING');
    setOutcome(null);
    setFailureCode(null);
    setRunId(null);
    setStatus('正在接收持久化事件');
    setReceiving(true);

    try {
      const subscription = await startGrossMarginDeclineRun(
        factoryId,
        {
          schemaVersion: '1.0',
          routeCode: RESTAURANT_AGENT_RUN_ROUTE,
          startDate,
          endDate,
        },
        {
          onEvent: (event) => {
            setRunId(event.runId);
            mergeEvents([event]);
          },
          onError: (message) => setStatus(message),
        },
      );
      subscriptionRef.current = subscription;
      if (stopRequestedRef.current) subscription.stopReceiving();
      const completion = await subscription.completion;
      if (completion.runId) setRunId(completion.runId);
      if (completion.stoppedReceiving || stopRequestedRef.current) {
        setStatus('已停止接收');
        return;
      }
      if (!completion.runId) throw new Error('运行未返回可回放的 Run ID');

      const replay = await replayGrossMarginDeclineRun(
        factoryId,
        completion.runId,
        lastSequenceRef.current,
      );
      mergeEvents(replay.events);
      setRunState(replay.state);
      setOutcome(replay.terminalOutcome);
      setFailureCode(replay.failureCode);
      setStatus(STATE_LABELS[replay.state]);
    } catch (error) {
      setStatus(
        stopRequestedRef.current
          ? '已停止接收'
          : error instanceof Error
            ? error.message
            : '毛利下降分析连接失败',
      );
    } finally {
      subscriptionRef.current = null;
      setReceiving(false);
    }
  }, [endDate, factoryId, mergeEvents, receiving, startDate]);

  return (
    <View testID="restaurant-agent-run-card" style={styles.card}>
      <View style={styles.header}>
        <View style={styles.titleBlock}>
          <Text style={styles.title}>毛利下降归因</Text>
          <Text style={styles.window}>本月：{startDate} 至 {endDate}</Text>
        </View>
        <TouchableOpacity
          testID={receiving ? 'restaurant-agent-stop' : 'restaurant-agent-start'}
          style={styles.button}
          onPress={receiving ? stopReceiving : startRun}
        >
          <Text style={styles.buttonText}>
            {receiving ? '停止接收' : '分析毛利下降原因'}
          </Text>
        </TouchableOpacity>
      </View>
      {status ? <Text testID="restaurant-agent-status" style={styles.status}>{status}</Text> : null}
      {status === '已停止接收' ? (
        <Text style={styles.note}>已停止接收。此操作只关闭当前页面连接，不代表服务端任务已取消。</Text>
      ) : null}
      {runId ? <Text style={styles.note}>Run {runId}</Text> : null}
      {runState ? <Text style={styles.note}>持久化状态：{runState}</Text> : null}
      {events.map((event) => (
        <Text key={event.sequence} style={styles.event}>
          #{event.sequence} {EVENT_LABELS[event.eventType]}
          {event.toolName ? ` · ${event.toolName}` : ''}
        </Text>
      ))}
      {outcome ? (
        <View testID="restaurant-agent-outcome" style={styles.outcome}>
          <Text style={styles.outcomeTitle}>
            持久化结论：{outcome.status} · {outcome.attributionSupported ? '支持归因' : '证据不足以归因'}
          </Text>
          {outcome.claims.map((claim) => (
            <Text key={`${claim.evidenceId}:${claim.factId}`} style={styles.claim}>
              {claim.metric} {claim.value}{claim.unit || ''}{'\n'}
              证据引用 {claim.evidenceId} / {claim.factId}
            </Text>
          ))}
          {outcome.observations.map((item) => <Text key={item} style={styles.event}>• {item}</Text>)}
          {outcome.blockers.map((item) => <Text key={item} style={styles.error}>阻塞：{item}</Text>)}
          {failureCode ? <Text style={styles.error}>失败代码：{failureCode}</Text> : null}
          <Text style={styles.note}>这里只展示持久化事件和结论引用，不代表完整 EvidenceEnvelope。</Text>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    marginBottom: 16,
    padding: 14,
    borderWidth: 1,
    borderColor: '#d7c8a5',
    borderRadius: 12,
    backgroundColor: '#fffdf7',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 10,
  },
  titleBlock: { flex: 1 },
  title: { fontSize: 15, fontWeight: '700', color: '#2d4a3e' },
  window: { marginTop: 3, fontSize: 11, color: '#756f63' },
  button: {
    minHeight: 44,
    justifyContent: 'center',
    paddingHorizontal: 12,
    borderRadius: 8,
    backgroundColor: '#667eea',
  },
  buttonText: { color: '#fff', fontSize: 13, fontWeight: '600' },
  status: { marginTop: 10, color: '#2d4a3e', fontSize: 13, fontWeight: '600' },
  note: { marginTop: 4, color: '#7b756a', fontSize: 11, lineHeight: 16 },
  event: { marginTop: 5, color: '#514a3e', fontSize: 12, lineHeight: 17 },
  outcome: { marginTop: 10, paddingTop: 9, borderTopWidth: 1, borderTopColor: '#e6dcc7' },
  outcomeTitle: { color: '#2d4a3e', fontSize: 13, fontWeight: '700' },
  claim: { marginTop: 7, color: '#4e473c', fontSize: 12, lineHeight: 17 },
  error: { marginTop: 5, color: '#b42318', fontSize: 12 },
});
