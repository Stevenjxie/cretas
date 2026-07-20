import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import {
  cancelGrossMarginDeclineRun,
  clearRestaurantAgentRunCheckpoint,
  loadRestaurantAgentRunCheckpoint,
  replayGrossMarginDeclineRun,
  saveRestaurantAgentRunCheckpoint,
  startGrossMarginDeclineRun,
  type RestaurantAgentRunSubscription,
} from '../../services/api/restaurantAgentRuns';
import {
  RESTAURANT_AGENT_RUN_ROUTE,
  type RestaurantAgentEventV1,
  type RestaurantAgentRunState,
  type RestaurantAgentTerminalOutcome,
  parseRestaurantAgentEvidenceDrilldown,
} from '../../types/restaurantAgentRun';

interface RestaurantGrossMarginRunCardProps {
  factoryId: string;
  ownerUserId: string;
  startDate: string;
  endDate: string;
  autoStart?: boolean;
}

interface RestaurantAgentStartLease {
  key: string;
  token: symbol;
}

const restaurantAgentStartLeases = new Map<string, symbol>();

function restaurantAgentStartLeaseKey(
  factoryId: string,
  ownerUserId: string,
  startDate: string,
  endDate: string,
): string {
  return JSON.stringify([
    factoryId,
    ownerUserId,
    RESTAURANT_AGENT_RUN_ROUTE,
    startDate,
    endDate,
  ]);
}

function acquireRestaurantAgentStartLease(key: string): RestaurantAgentStartLease | null {
  if (restaurantAgentStartLeases.has(key)) return null;
  const lease = { key, token: Symbol(key) };
  restaurantAgentStartLeases.set(key, lease.token);
  return lease;
}

function releaseRestaurantAgentStartLease(lease: RestaurantAgentStartLease): void {
  if (restaurantAgentStartLeases.get(lease.key) === lease.token) {
    restaurantAgentStartLeases.delete(lease.key);
  }
}

const EVENT_LABELS: Record<RestaurantAgentEventV1['eventType'], string> = {
  RUN_STARTED: '运行已创建',
  ROUTE_SELECTED: '已选择固定毛利归因路线',
  PLAN_CREATED: '读取计划已持久化',
  STEP_STARTED: '开始读取数据',
  STEP_COMPLETED: '数据读取完成',
  STEP_FAILED: '数据读取失败',
  EVIDENCE_RECORDED: '可核验的证据引用已保存',
  EVIDENCE_GAP: '发现证据缺口',
  REPLAN: '已按证据缺口调整读取计划',
  CLARIFICATION: '需要补充业务信息',
  CANCEL_REQUESTED: '取消请求已持久化',
  BUDGET_EXCEEDED: '运行预算已用尽',
  RUN_CANCELLED: '服务端记录运行取消',
  RUN_COMPLETED: '运行完成',
  RUN_FAILED: '运行失败',
};

const ACTION_LABELS: Record<string, string> = {
  REVIEW_STORE_COST_ALLOCATION: '核对门店成本分摊口径',
  REVIEW_DISH_COST_DATA: '补齐并核对菜品成本数据',
  REVIEW_DISH_PRICING_AND_COST: '复核低毛利菜品的售价与成本',
};

const CLARIFICATION_LABELS: Record<string, string> = {
  CONFIRM_DECLINE_WINDOW_AND_DATA_COVERAGE: '请确认分析时间范围，并检查该期间的营业额与毛利数据是否完整。',
  PROVIDE_DISH_AND_STORE_COST_EVIDENCE: '请补齐门店成本分摊及菜品分期成本数据后再继续归因。',
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
  ownerUserId,
  startDate,
  endDate,
  autoStart = false,
}: RestaurantGrossMarginRunCardProps) {
  const [events, setEvents] = useState<RestaurantAgentEventV1[]>([]);
  const [runState, setRunState] = useState<RestaurantAgentRunState | null>(null);
  const [outcome, setOutcome] = useState<RestaurantAgentTerminalOutcome | null>(null);
  const [failureCode, setFailureCode] = useState<string | null>(null);
  const [runId, setRunId] = useState<string | null>(null);
  const [status, setStatus] = useState('');
  const [initializing, setInitializing] = useState(true);
  const [startLeaseBlocked, setStartLeaseBlocked] = useState(false);
  const [receiving, setReceiving] = useState(false);
  const [cancelRequested, setCancelRequested] = useState(false);
  const [cancelAlreadyTerminal, setCancelAlreadyTerminal] = useState(false);
  const [cancelInFlight, setCancelInFlight] = useState(false);
  const [expandedEvidenceId, setExpandedEvidenceId] = useState<string | null>(null);
  const subscriptionRef = useRef<RestaurantAgentRunSubscription | null>(null);
  const runIdRef = useRef<string | null>(null);
  const cancelInFlightRef = useRef(false);
  const stopRequestedRef = useRef(false);
  const lastSequenceRef = useRef(0);
  const initializationStartedRef = useRef(false);
  const startLeaseRef = useRef<RestaurantAgentStartLease | null>(null);

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

  const applyReplay = useCallback(async (targetRunId: string, afterSequence: number) => {
    const replay = await replayGrossMarginDeclineRun(factoryId, targetRunId, afterSequence);
    mergeEvents(replay.events);
    setRunId(targetRunId);
    runIdRef.current = targetRunId;
    setRunState(replay.state);
    setOutcome(replay.terminalOutcome);
    setFailureCode(replay.failureCode);
    lastSequenceRef.current = Math.max(lastSequenceRef.current, replay.nextEventSequence);
    if (replay.state === 'RUNNING') {
      await saveRestaurantAgentRunCheckpoint(factoryId, ownerUserId, {
        runId: targetRunId,
        lastSequence: lastSequenceRef.current,
      });
      setStatus('后台分析仍在运行，可继续回放或取消');
    } else {
      await clearRestaurantAgentRunCheckpoint(factoryId, ownerUserId);
      setStatus(STATE_LABELS[replay.state]);
    }
    return replay;
  }, [factoryId, mergeEvents, ownerUserId]);

  const requestCancellation = useCallback(async () => {
    if (!runId || runState !== 'RUNNING' || cancelRequested || cancelInFlightRef.current) return;
    cancelInFlightRef.current = true;
    setCancelInFlight(true);
    try {
      const response = await cancelGrossMarginDeclineRun(factoryId, runId);
      setCancelRequested(true);
      setCancelAlreadyTerminal(response.result === 'ALREADY_TERMINAL');
      setStatus(response.result === 'ALREADY_TERMINAL' ? '运行已经结束' : '取消请求已持久化');
      if (response.result === 'ALREADY_TERMINAL') {
        await applyReplay(runId, lastSequenceRef.current);
      }
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '取消分析失败');
    } finally {
      cancelInFlightRef.current = false;
      setCancelInFlight(false);
    }
  }, [applyReplay, cancelRequested, factoryId, runId, runState]);

  useEffect(() => () => {
    stopRequestedRef.current = true;
    subscriptionRef.current?.stopReceiving();
    const lease = startLeaseRef.current;
    if (lease) {
      releaseRestaurantAgentStartLease(lease);
      startLeaseRef.current = null;
    }
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
    runIdRef.current = null;
    setCancelRequested(false);
    setCancelAlreadyTerminal(false);
    cancelInFlightRef.current = false;
    setCancelInFlight(false);
    setExpandedEvidenceId(null);
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
            runIdRef.current = event.runId;
            mergeEvents([event]);
            void saveRestaurantAgentRunCheckpoint(factoryId, ownerUserId, {
              runId: event.runId,
              lastSequence: event.sequence,
            });
            if (event.eventType === 'CANCEL_REQUESTED') {
              setCancelRequested(true);
              setCancelAlreadyTerminal(false);
              setStatus('取消请求已持久化');
            }
          },
          onError: (message) => setStatus(message),
        },
      );
      subscriptionRef.current = subscription;
      if (stopRequestedRef.current) subscription.stopReceiving();
      const completion = await subscription.completion;
      if (completion.runId) {
        setRunId(completion.runId);
        runIdRef.current = completion.runId;
        await saveRestaurantAgentRunCheckpoint(factoryId, ownerUserId, {
          runId: completion.runId,
          lastSequence: completion.lastSequence,
        });
      }
      if (completion.stoppedReceiving || stopRequestedRef.current) {
        setStatus('已停止接收');
        return;
      }
      if (!completion.runId) throw new Error('运行未返回可回放的 Run ID');

      await applyReplay(completion.runId, lastSequenceRef.current);
    } catch (error) {
      const recoverableRunId = runIdRef.current;
      if (recoverableRunId && !stopRequestedRef.current) {
        try {
          await applyReplay(recoverableRunId, lastSequenceRef.current);
          return;
        } catch {
          // Keep the durable checkpoint for an explicit retry after reconnection.
        }
      }
      if (!recoverableRunId && !stopRequestedRef.current) {
        setRunState(null);
      }
      setStatus(
        stopRequestedRef.current
          ? '已停止接收'
          : error instanceof Error
            ? error.message === 'RESTAURANT_AGENT_RUNS_OFF'
              ? '当前客户端未启用餐饮分析，请更新发布配置'
              : error.message
            : '毛利下降分析连接失败',
      );
    } finally {
      subscriptionRef.current = null;
      setReceiving(false);
    }
  }, [applyReplay, endDate, factoryId, mergeEvents, ownerUserId, receiving, startDate]);

  const startRunWithLease = useCallback(async (checkpointAlreadyChecked: boolean) => {
    const leaseKey = restaurantAgentStartLeaseKey(
      factoryId,
      ownerUserId,
      startDate,
      endDate,
    );
    const lease = acquireRestaurantAgentStartLease(leaseKey);
    if (!lease) {
      setStartLeaseBlocked(true);
      setStatus('同一账号已有本期毛利归因正在启动，本消息不会重复发起');
      return;
    }
    startLeaseRef.current = lease;
    setStartLeaseBlocked(false);

    try {
      if (!checkpointAlreadyChecked) {
        let checkpoint;
        try {
          checkpoint = await loadRestaurantAgentRunCheckpoint(factoryId, ownerUserId);
        } catch (error) {
          setStatus(error instanceof Error ? error.message : '恢复后台分析失败');
          return;
        }
        if (checkpoint) {
          runIdRef.current = checkpoint.runId;
          lastSequenceRef.current = checkpoint.lastSequence;
          setRunId(checkpoint.runId);
          setRunState('RUNNING');
          try {
            await applyReplay(checkpoint.runId, 0);
          } catch (error) {
            setStatus(error instanceof Error ? error.message : '恢复后台分析失败');
          }
          return;
        }
      }
      await startRun();
    } finally {
      releaseRestaurantAgentStartLease(lease);
      if (startLeaseRef.current?.token === lease.token) {
        startLeaseRef.current = null;
      }
    }
  }, [applyReplay, endDate, factoryId, ownerUserId, startDate, startRun]);

  useEffect(() => {
    if (initializationStartedRef.current) return;
    initializationStartedRef.current = true;
    let active = true;

    void (async () => {
      let checkpoint;
      try {
        checkpoint = await loadRestaurantAgentRunCheckpoint(factoryId, ownerUserId);
      } catch (error) {
        if (active) {
          setInitializing(false);
          setStatus(error instanceof Error ? error.message : '恢复后台分析失败');
        }
        return;
      }
      if (!active) return;
      if (checkpoint) {
        runIdRef.current = checkpoint.runId;
        lastSequenceRef.current = checkpoint.lastSequence;
        setRunId(checkpoint.runId);
        setRunState('RUNNING');
        try {
          await applyReplay(checkpoint.runId, 0);
        } catch (error) {
          if (active) setStatus(error instanceof Error ? error.message : '恢复后台分析失败');
        } finally {
          if (active) setInitializing(false);
        }
        return;
      }
      if (autoStart && active) {
        setInitializing(false);
        await startRunWithLease(true);
      } else if (active) {
        setInitializing(false);
      }
    })();

    return () => { active = false; };
  }, [applyReplay, autoStart, factoryId, ownerUserId, startRunWithLease]);

  const resumeRun = useCallback(async () => {
    if (!runId || receiving) return;
    setReceiving(true);
    try {
      await applyReplay(runId, 0);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '恢复后台分析失败');
    } finally {
      setReceiving(false);
    }
  }, [applyReplay, receiving, runId]);

  const drilldowns = useMemo(
    () => events
      .map(parseRestaurantAgentEvidenceDrilldown)
      .filter((item): item is NonNullable<typeof item> => item !== null),
    [events],
  );

  const clarification = useMemo(() => {
    const event = [...events].reverse().find((item) => item.eventType === 'CLARIFICATION');
    const code = event?.payload.clarificationCode;
    return typeof code === 'string' ? (CLARIFICATION_LABELS[code] || code) : null;
  }, [events]);

  return (
    <View testID="restaurant-agent-run-card" style={styles.card}>
      <View style={styles.header}>
        <View style={styles.titleBlock}>
          <Text style={styles.title}>毛利下降归因</Text>
          <Text style={styles.window}>本月：{startDate} 至 {endDate}</Text>
        </View>
        <TouchableOpacity
          testID={runState === 'RUNNING' ? 'restaurant-agent-cancel' : 'restaurant-agent-start'}
          style={[styles.button, initializing || (runState === 'RUNNING' && (!runId || cancelRequested || cancelInFlight)) ? styles.buttonDisabled : null]}
          onPress={runState === 'RUNNING' ? requestCancellation : () => { void startRunWithLease(false); }}
          disabled={initializing || (runState === 'RUNNING' && (!runId || cancelRequested || cancelInFlight))}
        >
          <Text style={styles.buttonText}>
            {initializing ? '正在恢复分析' : runState === 'RUNNING' ? (cancelAlreadyTerminal ? '运行已经结束' : cancelRequested ? '正在取消' : runId ? '取消分析' : '分析已启动') : startLeaseBlocked ? '检查已有分析并重试' : '分析毛利下降原因'}
          </Text>
        </TouchableOpacity>
      </View>
      {status ? <Text testID="restaurant-agent-status" style={styles.status}>{status}</Text> : null}
      {runId && runState === 'RUNNING' && !receiving ? (
        <TouchableOpacity testID="restaurant-agent-resume" style={styles.resumeButton} onPress={resumeRun}>
          <Text style={styles.resumeText}>继续回放后台分析</Text>
        </TouchableOpacity>
      ) : null}
      {clarification ? <Text testID="restaurant-agent-clarification" style={styles.clarification}>{clarification}</Text> : null}
      {runId ? <Text style={styles.note}>Run {runId}</Text> : null}
      {runState ? <Text style={styles.note}>持久化状态：{runState}</Text> : null}
      {events.map((event) => (
        <Text key={event.sequence} style={styles.event}>
          #{event.sequence} {EVENT_LABELS[event.eventType]}
          {event.toolName ? ` · ${event.toolName}` : ''}
        </Text>
      ))}
      {drilldowns.map((drilldown) => {
        const expanded = expandedEvidenceId === drilldown.evidenceId;
        return (
          <View key={drilldown.evidenceId} testID={`restaurant-agent-evidence-${drilldown.evidenceId}`} style={styles.evidenceCard}>
            <TouchableOpacity
              testID={`restaurant-agent-evidence-toggle-${drilldown.evidenceId}`}
              onPress={() => setExpandedEvidenceId(expanded ? null : drilldown.evidenceId)}
            >
              <Text style={styles.evidenceTitle}>
                证据 {drilldown.evidenceId} · {drilldown.evidenceStatus} · {drilldown.factReferences.length} 项
              </Text>
              <Text style={styles.note}>{expanded ? '收起证据' : '查看证据来源与数字'}</Text>
            </TouchableOpacity>
            {expanded ? (
              <View>
                {drilldown.factReferences.map((fact) => (
                  <Text key={fact.factId} style={styles.claim}>
                    {fact.metric} {fact.value}{fact.unit || ''} · Fact {fact.factId}
                  </Text>
                ))}
                {drilldown.provenance.map((source) => (
                  <Text key={source.refId} style={styles.note}>来源：{source.asset} · {source.queryId}</Text>
                ))}
                {drilldown.drilldownTruncated ? (
                  <Text testID="restaurant-agent-evidence-truncated" style={styles.note}>证据详情已按安全大小上限截取；结论引用仍保留精确 evidenceId/factId。</Text>
                ) : null}
              </View>
            ) : null}
          </View>
        );
      })}
      {outcome ? (
        <View testID="restaurant-agent-outcome" style={styles.outcome}>
          <Text style={styles.outcomeTitle}>
            持久化结论：{outcome.status} · {outcome.attributionSupported ? '支持归因' : '证据不足以归因'}
          </Text>
          {outcome.claims.map((claim) => (
            <TouchableOpacity
              key={`${claim.evidenceId}:${claim.factId}`}
              testID={`restaurant-agent-claim-ref-${claim.evidenceId}-${claim.factId}`}
              onPress={() => setExpandedEvidenceId(claim.evidenceId)}
            >
              <Text style={styles.claim}>
                {claim.metric} {claim.value}{claim.unit || ''}{'\n'}
                证据引用 {claim.evidenceId} / {claim.factId} · 点击查看
              </Text>
            </TouchableOpacity>
          ))}
          {outcome.observations.map((item) => <Text key={item} style={styles.event}>• {item}</Text>)}
          {outcome.blockers.map((item) => <Text key={item} style={styles.error}>阻塞：{item}</Text>)}
          {outcome.actionProposals.map((proposal) => (
            <View key={proposal.proposalCode} testID={`restaurant-agent-proposal-${proposal.proposalCode}`} style={styles.proposal}>
              <Text style={styles.proposalTitle}>建议动作</Text>
              <Text style={styles.event}>{ACTION_LABELS[proposal.actionCode] || proposal.actionCode}</Text>
              {proposal.evidenceReferences.map((reference) => (
                <TouchableOpacity
                  key={`${reference.evidenceId}:${reference.factId}`}
                  onPress={() => setExpandedEvidenceId(reference.evidenceId)}
                >
                  <Text style={styles.note}>证据引用 {reference.evidenceId} / {reference.factId} · 点击查看</Text>
                </TouchableOpacity>
              ))}
              <Text testID="restaurant-agent-action-readonly" style={styles.note}>只读建议，尚未写入 ERP，也未发起审批。</Text>
            </View>
          ))}
          {failureCode ? <Text style={styles.error}>失败代码：{failureCode}</Text> : null}
          <Text testID="restaurant-agent-outcome-note" style={styles.note}>所有数字都绑定可点击的 evidenceId/factId；建议动作不会自动写入 ERP。</Text>
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
  buttonDisabled: { opacity: 0.55 },
  resumeButton: { marginTop: 8, alignSelf: 'flex-start', paddingVertical: 6, paddingHorizontal: 8 },
  resumeText: { color: '#415d9b', fontSize: 12, fontWeight: '600' },
  status: { marginTop: 10, color: '#2d4a3e', fontSize: 13, fontWeight: '600' },
  note: { marginTop: 4, color: '#7b756a', fontSize: 11, lineHeight: 16 },
  event: { marginTop: 5, color: '#514a3e', fontSize: 12, lineHeight: 17 },
  outcome: { marginTop: 10, paddingTop: 9, borderTopWidth: 1, borderTopColor: '#e6dcc7' },
  outcomeTitle: { color: '#2d4a3e', fontSize: 13, fontWeight: '700' },
  claim: { marginTop: 7, color: '#4e473c', fontSize: 12, lineHeight: 17 },
  error: { marginTop: 5, color: '#b42318', fontSize: 12 },
  clarification: { marginTop: 8, padding: 9, borderRadius: 8, backgroundColor: '#fff4d6', color: '#704b00', fontSize: 12, lineHeight: 17 },
  evidenceCard: { marginTop: 8, padding: 9, borderRadius: 8, backgroundColor: '#f4f7ff', borderWidth: 1, borderColor: '#dce5ff' },
  evidenceTitle: { color: '#304a78', fontSize: 12, fontWeight: '700' },
  proposal: { marginTop: 8, padding: 9, borderRadius: 8, backgroundColor: '#eef9f0', borderWidth: 1, borderColor: '#cfe8d3' },
  proposalTitle: { color: '#245c2f', fontSize: 12, fontWeight: '700' },
});
