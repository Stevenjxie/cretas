import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { View, Text, ScrollView, StyleSheet, Alert, ActivityIndicator } from 'react-native';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScreenWrapper } from '../../components/ui/ScreenWrapper';
import { NeoCard } from '../../components/ui/NeoCard';
import { NeoButton } from '../../components/ui/NeoButton';
import YieldQuantityInput from '../../components/processing/YieldQuantityInput';
import {
  yieldReportApi,
  WorkProcessTask,
  BatchYieldDTO,
  StepYieldDTO,
  YieldReportRequest,
  YieldLimitsDTO,
} from '../../services/api/yieldReportApi';
import { processingApiClient } from '../../services/api/processingApiClient';
import { handleError } from '../../utils/errorHandler';

type YieldStepReportParams = { batchId: number; batchNumber?: string };
type RouteT = RouteProp<{ YieldStepReport: YieldStepReportParams }, 'YieldStepReport'>;
type NavT = NativeStackNavigationProp<Record<string, object | undefined>>;

const OVER_RECEIVE_TOLERANCE = 1.3; // A4 软上限: 计划 ×1.3 (含 30% 超收)

const YieldStepReportScreen: React.FC = () => {
  const navigation = useNavigation<NavT>();
  const route = useRoute<RouteT>();
  const { batchId } = route.params;

  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [phase, setPhase] = useState<'reporting' | 'done'>('reporting');

  const [productType, setProductType] = useState<string>('');
  const [batchNumber, setBatchNumber] = useState<string>(route.params.batchNumber ?? '');
  const [tasks, setTasks] = useState<WorkProcessTask[]>([]);
  const [yieldData, setYieldData] = useState<BatchYieldDTO | null>(null);
  const [currentStepIndex, setCurrentStepIndex] = useState(0);

  const [inputQty, setInputQty] = useState('');
  const [outputQty, setOutputQty] = useState('');
  const [lastAlert, setLastAlert] = useState<'BELOW_MIN' | 'ABOVE_MAX' | null>(null);
  // A4: 超收预检
  const [yieldLimits, setYieldLimits] = useState<YieldLimitsDTO | null>(null);
  const limitsDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const currentTask = tasks[currentStepIndex] ?? null;
  const totalSteps = tasks.length;

  // 上道产出 (下道预填): 取 yield steps 里 processOrder == 当前道-1 的 totalOutput
  const prevOutput = useMemo<number | null>(() => {
    if (!currentTask || !yieldData) return null;
    const prevOrder = currentTask.processOrder - 1;
    const prevStep = yieldData.steps.find((s: StepYieldDTO) => s.processOrder === prevOrder);
    return prevStep?.totalOutput ?? null;
  }, [currentTask, yieldData]);

  const loadAll = useCallback(async () => {
    try {
      const [tasksRes, batchRes, yieldRes] = await Promise.all([
        yieldReportApi.listWorkProcessTasks(batchId),
        processingApiClient.getBatchById(String(batchId)),
        yieldReportApi.getYield(batchId),
      ]);
      if (tasksRes.success) {
        const sorted = [...tasksRes.data].sort((a, b) => a.processOrder - b.processOrder);
        setTasks(sorted);
      }
      if (batchRes.success && batchRes.data) {
        setProductType(batchRes.data.productType ?? '');
        if (batchRes.data.batchNumber) setBatchNumber(batchRes.data.batchNumber);
      }
      if (yieldRes.success) setYieldData(yieldRes.data);
    } catch (error) {
      handleError(error, { showAlert: false, logError: true });
      const msg = error instanceof Error ? error.message : '加载批次工序失败';
      Alert.alert('加载失败', msg);
    } finally {
      setLoading(false);
    }
  }, [batchId]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  // 切道时: 投入预填上道产出, 产出清空, 清告警, 清 limits
  useEffect(() => {
    if (phase !== 'reporting') return;
    setInputQty(prevOutput != null ? String(prevOutput) : '');
    setOutputQty('');
    setLastAlert(null);
    setYieldLimits(null);
  }, [currentStepIndex, prevOutput, phase]);

  // A4: 投入量变化后 debounce 500ms 拉超收上限
  useEffect(() => {
    if (limitsDebounceRef.current) clearTimeout(limitsDebounceRef.current);
    if (!currentTask) return;
    const parsed = parseFloat(inputQty);
    if (!inputQty || Number.isNaN(parsed) || parsed <= 0) {
      setYieldLimits(null);
      return;
    }
    limitsDebounceRef.current = setTimeout(async () => {
      try {
        const res = await yieldReportApi.getYieldLimits(batchId, currentTask.id, parsed);
        if (res.success) {
          setYieldLimits(res.data.maxAllowed != null ? res.data : null);
        }
      } catch {
        // 预检失败不阻断报工, 静默忽略
      }
    }, 500);
    return () => {
      if (limitsDebounceRef.current) clearTimeout(limitsDebounceRef.current);
    };
  }, [inputQty, currentTask, batchId]);

  const unit = currentTask?.plannedUnit ?? 'kg';
  const planned = currentTask?.plannedQuantity ?? null;
  const inputMax = planned != null ? planned * OVER_RECEIVE_TOLERANCE : null;
  const inputMaxHint =
    planned != null
      ? `计划 ${planned} ${unit}, 可投上限约 ${Math.round(planned * OVER_RECEIVE_TOLERANCE)} (含 30% 超收)`
      : null;
  const prefillNote =
    prevOutput != null
      ? `← 上道产出 ${prevOutput} ${unit}, 请确认实际投了多少`
      : '本道为首道, 请填本道领料投入量';

  // A4: 强制提交 (OVER_RECEIPT 确认后调用)
  const submitWithForce = useCallback(async (req: YieldReportRequest) => {
    setSubmitting(true);
    try {
      const res = await yieldReportApi.submitReport(batchId, { ...req, forceSubmit: true });
      if (!res.success) {
        Alert.alert('提交失败', res.message || '请重试');
        return;
      }
      setLastAlert(res.data.alert ?? null);
      const yieldRes = await yieldReportApi.getYield(batchId);
      if (yieldRes.success) setYieldData(yieldRes.data);
      const isLast = currentStepIndex >= totalSteps - 1;
      if (isLast) {
        setPhase('done');
      } else {
        setCurrentStepIndex((i) => i + 1);
      }
    } catch (forceError) {
      handleError(forceError, { showAlert: false, logError: true });
      const fe = forceError as { response?: { data?: { message?: string } } };
      Alert.alert('提交失败', fe.response?.data?.message ?? '请重试');
    } finally {
      setSubmitting(false);
    }
  }, [batchId, currentStepIndex, totalSteps]);

  const handleSubmit = useCallback(async () => {
    if (!currentTask) return;
    const output = parseFloat(outputQty);
    if (Number.isNaN(output) || output <= 0) {
      Alert.alert('请填写本道产出量', '产出量必须大于 0');
      return;
    }
    const input = parseFloat(inputQty);
    const req: YieldReportRequest = {
      workProcessTaskId: currentTask.id,
      inputQuantity: Number.isNaN(input) ? 0 : input,
      inputUnit: unit,
      outputQuantity: output,
      outputUnit: unit,
    };
    setSubmitting(true);
    try {
      const res = await yieldReportApi.submitReport(batchId, req);
      if (!res.success) {
        Alert.alert('提交失败', res.message || '请重试');
        return;
      }
      setLastAlert(res.data.alert ?? null);

      // re-fetch yield 刷新下道预填
      const yieldRes = await yieldReportApi.getYield(batchId);
      if (yieldRes.success) setYieldData(yieldRes.data);

      const isLast = currentStepIndex >= totalSteps - 1;
      if (isLast) {
        setPhase('done');
      } else {
        setCurrentStepIndex((i) => i + 1);
      }
    } catch (error) {
      // A4: OVER_RECEIPT (HTTP 409) → 弹确认框, 不走默认 toast
      // apiClient 对非 401/410 的 HTTP 错误直接 Promise.reject(error), 无全局 toast,
      // 所以直接在 catch 检查 errorCode 即可拦截.
      const e = error as { response?: { data?: { success?: boolean; message?: string; errorCode?: string; actionHint?: string; hint?: string } } };
      const errorCode = e.response?.data?.errorCode;
      if (errorCode === 'OVER_RECEIPT') {
        handleError(error, { showAlert: false, logError: true });
        const actionHint = e.response?.data?.actionHint ?? e.response?.data?.message ?? '产出已超收收告警上限, 确认要超收提交吗?';
        Alert.alert(
          '超收确认',
          actionHint,
          [
            { text: '取消', style: 'cancel' },
            { text: '确认超收提交', onPress: () => submitWithForce(req) },
          ],
        );
        return;
      }
      // 4位一体: 透传后端 message (含 hint), 用 Alert (RN sticky 模态)
      handleError(error, { showAlert: false, logError: true });
      const backendMsg = e.response?.data?.message;
      const hint = e.response?.data?.hint;
      const msg = backendMsg
        ? hint
          ? `${backendMsg}\n${hint}`
          : backendMsg
        : error instanceof Error
          ? error.message
          : '提交失败, 请重试';
      Alert.alert('提交失败', msg);
    } finally {
      setSubmitting(false);
    }
  }, [currentTask, outputQty, inputQty, unit, batchId, currentStepIndex, totalSteps, submitWithForce]);

  const handleSettleDay = useCallback(async () => {
    setSubmitting(true);
    try {
      const res = await yieldReportApi.settleDay(batchId, {});
      if (res.success) {
        Alert.alert('已标记今日结清', `本次结清 ${res.data.settledCount} 条报工`);
      } else {
        Alert.alert('结清失败', res.message || '请重试');
      }
    } catch (error) {
      handleError(error, { showAlert: false, logError: true });
      const e = error as { response?: { data?: { message?: string } } };
      Alert.alert('结清失败', e.response?.data?.message ?? '请重试');
    } finally {
      setSubmitting(false);
    }
  }, [batchId]);

  if (loading) {
    return (
      <ScreenWrapper>
        <View style={styles.centered}>
          <ActivityIndicator size="large" color="#E8732E" />
          <Text style={styles.loadingText}>加载批次工序...</Text>
        </View>
      </ScreenWrapper>
    );
  }

  if (totalSteps === 0) {
    return (
      <ScreenWrapper>
        <View style={styles.centered}>
          <Text style={styles.emptyTitle}>该批次未生成工序任务</Text>
          <Text style={styles.emptyDesc}>请联系主管为该批次 spawn 工序后再报工</Text>
          <NeoButton variant="outline" size="large" onPress={() => navigation.goBack()} style={styles.fullBtn}>
            返回选批次
          </NeoButton>
        </View>
      </ScreenWrapper>
    );
  }

  if (phase === 'done') {
    const cum = yieldData?.cumulativeYieldRate;
    const cumPct = cum != null ? `${(cum * 100).toFixed(2)}%` : '—';
    return (
      <ScreenWrapper>
        <ScrollView contentContainerStyle={styles.content}>
          <NeoCard variant="elevated" style={styles.doneCard}>
            <Text style={styles.doneTitle}>✓ {totalSteps}/{totalSteps} 道全部报完</Text>
            <Text style={styles.doneProduct}>{productType || '—'}</Text>
            <Text style={styles.doneBatch}>{batchNumber}</Text>
            <View style={styles.doneRow}>
              <Text style={styles.doneLabel}>累计出成率</Text>
              <Text style={styles.doneValue} testID="cumulative-yield-rate">{cumPct}</Text>
            </View>
            {yieldData?.firstStepInput != null && yieldData?.lastStepOutput != null ? (
              <Text style={styles.doneFlow}>
                {yieldData.firstStepInput}{yieldData.firstStepInputUnit ?? ''} → {yieldData.lastStepOutput}{yieldData.lastStepOutputUnit ?? ''}
              </Text>
            ) : null}
          </NeoCard>
          <NeoButton variant="primary" size="large" onPress={handleSettleDay} disabled={submitting} loading={submitting} style={styles.fullBtn}>
            标记今日结清
          </NeoButton>
          <NeoButton variant="outline" size="large" onPress={() => navigation.goBack()} style={styles.fullBtn}>
            返回选批次
          </NeoButton>
        </ScrollView>
      </ScreenWrapper>
    );
  }

  const alertText =
    lastAlert === 'ABOVE_MAX'
      ? '⚠ 出成率偏高, 请核对'
      : lastAlert === 'BELOW_MIN'
        ? '⚠ 出成率偏低, 请核对'
        : null;

  return (
    <ScreenWrapper>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        {/* 进度条 报工 i/N + 圆点 */}
        <View style={styles.progressWrap}>
          <Text style={styles.progressText}>报工 {currentStepIndex + 1} / {totalSteps}</Text>
          <View style={styles.dotsRow}>
            {tasks.map((t, i) => (
              <View
                key={t.id}
                style={[styles.dot, i <= currentStepIndex ? styles.dotActive : styles.dotInactive]}
              />
            ))}
          </View>
        </View>

        <NeoCard variant="elevated" style={styles.card}>
          {/* 卡片头 context (Rule 2) */}
          <Text style={styles.product}>{productType || '—'}</Text>
          <Text style={styles.batchProcess}>
            {batchNumber}  ·  {currentTask?.processName ?? `第 ${currentTask?.processOrder} 道`}
          </Text>
          {planned != null ? (
            <Text style={styles.planned}>计划数量  {planned} {unit}</Text>
          ) : null}

          {/* 标准区间 (Task 0 透出) */}
          {currentTask?.standardYieldMin != null || currentTask?.standardYieldMax != null ? (
            <Text style={styles.stdRange}>
              标准出成率{' '}
              {currentTask?.standardYieldMin != null ? `${(currentTask.standardYieldMin * 100).toFixed(0)}%` : '—'}
              {' ~ '}
              {currentTask?.standardYieldMax != null ? `${(currentTask.standardYieldMax * 100).toFixed(0)}%` : '—'}
            </Text>
          ) : null}

          <View style={styles.divider} />

          <YieldQuantityInput
            label="投入量"
            value={inputQty}
            onChangeText={setInputQty}
            unit={unit}
            max={inputMax}
            maxHint={inputMaxHint}
            prefillNote={prefillNote}
            disabled={submitting}
            testID="yield-input-qty"
          />

          {/* A4: 超收预检提示 (仅 maxAllowed != null 时显示) */}
          {yieldLimits != null ? (
            <View style={styles.limitsHint} testID="yield-limits-hint">
              <Text style={styles.limitsHintText}>
                目标 {yieldLimits.targetQuantity ?? '—'} / 已报 {yieldLimits.alreadyReported ?? 0} / 最多可报 {yieldLimits.remaining ?? '—'} {yieldLimits.unit ?? unit}
              </Text>
            </View>
          ) : null}

          <YieldQuantityInput
            label="产出量"
            value={outputQty}
            onChangeText={setOutputQty}
            unit={unit}
            disabled={submitting}
            testID="yield-output-qty"
          />

          {alertText ? (
            <View style={styles.alertBanner} testID="yield-alert-banner">
              <Text style={styles.alertText}>{alertText}</Text>
            </View>
          ) : null}
        </NeoCard>

        <NeoButton
          variant="primary"
          size="large"
          onPress={handleSubmit}
          disabled={submitting}
          loading={submitting}
          style={styles.fullBtn}
          testID="yield-submit-btn"
        >
          {currentStepIndex >= totalSteps - 1 ? '提交  ·  完成' : '提交  ·  下一道  ▶'}
        </NeoButton>
      </ScrollView>
    </ScreenWrapper>
  );
};

const styles = StyleSheet.create({
  content: { padding: 16 },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  loadingText: { marginTop: 12, fontSize: 15, color: '#909399' },
  emptyTitle: { fontSize: 18, fontWeight: '600', color: '#303133', marginBottom: 8, textAlign: 'center' },
  emptyDesc: { fontSize: 14, color: '#909399', marginBottom: 24, textAlign: 'center' },
  progressWrap: { marginBottom: 16 },
  progressText: { fontSize: 16, fontWeight: '600', color: '#303133', marginBottom: 8 },
  dotsRow: { flexDirection: 'row', flexWrap: 'wrap' },
  dot: { width: 12, height: 12, borderRadius: 6, marginRight: 8, marginBottom: 6 },
  dotActive: { backgroundColor: '#E8732E' },
  dotInactive: { backgroundColor: '#DCDFE6' },
  card: { marginBottom: 16 },
  product: { fontSize: 22, fontWeight: '700', color: '#1A1A1A' },
  batchProcess: { fontSize: 15, color: '#606266', marginTop: 6 },
  planned: { fontSize: 14, color: '#909399', marginTop: 6 },
  stdRange: { fontSize: 13, color: '#409EFF', marginTop: 4 },
  divider: { height: 1, backgroundColor: '#EBEEF5', marginVertical: 16 },
  alertBanner: { backgroundColor: '#FDF6EC', borderRadius: 8, padding: 12, marginTop: 4 },
  alertText: { fontSize: 14, color: '#E6A23C', fontWeight: '500' },
  limitsHint: { backgroundColor: '#F0F9EB', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 6, marginTop: 4 },
  limitsHintText: { fontSize: 13, color: '#67C23A' },
  fullBtn: { width: '100%', marginBottom: 12 },
  doneCard: { marginBottom: 20, alignItems: 'center' },
  doneTitle: { fontSize: 20, fontWeight: '700', color: '#67C23A', marginBottom: 12 },
  doneProduct: { fontSize: 18, fontWeight: '600', color: '#1A1A1A' },
  doneBatch: { fontSize: 14, color: '#909399', marginTop: 4, marginBottom: 16 },
  doneRow: { flexDirection: 'row', alignItems: 'baseline', marginTop: 8 },
  doneLabel: { fontSize: 15, color: '#606266', marginRight: 12 },
  doneValue: { fontSize: 28, fontWeight: '700', color: '#E8732E' },
  doneFlow: { fontSize: 15, color: '#606266', marginTop: 12 },
});

export default YieldStepReportScreen;
