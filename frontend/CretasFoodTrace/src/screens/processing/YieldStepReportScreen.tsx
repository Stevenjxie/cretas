import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { View, Text, ScrollView, StyleSheet, Alert, ActivityIndicator } from 'react-native';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScreenWrapper } from '../../components/ui/ScreenWrapper';
import { NeoCard } from '../../components/ui/NeoCard';
import { NeoButton } from '../../components/ui/NeoButton';
import YieldQuantityInput from '../../components/processing/YieldQuantityInput';
import MaterialBatchPicker, { MaterialBatchRef } from '../../components/processing/MaterialBatchPicker';
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
  // A2b: 首道领料批次引用
  const [materialBatchRefs, setMaterialBatchRefs] = useState<MaterialBatchRef[]>([]);

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

  // 切道时: 投入预填上道产出, 产出清空, 清告警, 清 limits, 清 A2b refs
  useEffect(() => {
    if (phase !== 'reporting') return;
    setInputQty(prevOutput != null ? String(prevOutput) : '');
    setOutputQty('');
    setLastAlert(null);
    setYieldLimits(null);
    setMaterialBatchRefs([]);
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
        // P0-3: 保留 limits 即使 maxAllowed==null (诚实显示"未配置标准出成上限"灰条, 不静默空白)
        if (res.success) setYieldLimits(res.data);
      } catch {
        // 预检失败不阻断报工, 静默忽略
      }
    }, 500);
    return () => {
      if (limitsDebounceRef.current) clearTimeout(limitsDebounceRef.current);
    };
  }, [inputQty, currentTask, batchId]);

  const unit = currentTask?.plannedUnit ?? 'kg';
  // P0-2: 本道产出单位 — 工序配了 outputUnit (如末道 kg→份/盒) 则用它, 否则沿用投入单位
  const outUnit = currentTask?.outputUnit ?? unit;
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

  // P0-3: 产出绝对物理上限 = 2 × maxAllowed (maxAllowed 本身已含 30% 容差;
  // 1×~2× 之间走超收确认流, 超 2× 视为单位/数量误输, 直接 disable 提交)
  const OUTPUT_HARD_CAP_MULTIPLIER = 2;
  const outputHardCap = useMemo<number | null>(() => {
    if (!yieldLimits || yieldLimits.maxAllowed == null) return null;
    return yieldLimits.maxAllowed * OUTPUT_HARD_CAP_MULTIPLIER;
  }, [yieldLimits]);
  const outputOverHardCap = useMemo<boolean>(() => {
    const out = parseFloat(outputQty);
    return outputHardCap != null && !Number.isNaN(out) && out > outputHardCap;
  }, [outputQty, outputHardCap]);

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
    // A2b: 首道 + 有批次引用时, 将 materialBatchRefs 随报工单一起提交 (一次请求, 不再双调)
    const req: YieldReportRequest = {
      workProcessTaskId: currentTask.id,
      inputQuantity: Number.isNaN(input) ? 0 : input,
      inputUnit: unit,
      outputQuantity: output,
      outputUnit: outUnit,
      ...(currentStepIndex === 0 && materialBatchRefs.length > 0
        ? {
            materialBatchRefs: materialBatchRefs.map((r: MaterialBatchRef) => ({
              materialBatchId: r.materialBatchId,
              quantity: r.quantity,
              unit: r.unit ?? unit,
            })),
          }
        : {}),
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
  }, [currentTask, outputQty, inputQty, unit, outUnit, batchId, currentStepIndex, totalSteps, submitWithForce, materialBatchRefs]);

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
    // P0-2: 跨单位 (首道投入单位 ≠ 末道产出单位) 且无累计出成率 → 诚实标"跨单位不可比", 不显 0/—
    const inU = yieldData?.firstStepInputUnit;
    const outU = yieldData?.lastStepOutputUnit;
    const crossUnitNoGrams = cum == null && inU != null && outU != null && inU !== outU;
    const cumPct = cum != null ? `${(cum * 100).toFixed(2)}%` : '—';
    return (
      <ScreenWrapper>
        <ScrollView contentContainerStyle={styles.content}>
          <NeoCard variant="elevated" style={styles.doneCard}>
            <Text style={styles.doneTitle}>✓ {totalSteps}/{totalSteps} 道全部报完</Text>
            <Text style={styles.doneProduct}>{productType || '—'}</Text>
            <Text style={styles.doneBatch}>{batchNumber}</Text>
            {crossUnitNoGrams ? (
              <View style={styles.crossUnitBanner} testID="cumulative-cross-unit">
                <Text style={styles.crossUnitText}>
                  整批出成率: 跨单位不可比 (末道为 {outU}, 需在产品管理配产品标准克重后折算)
                </Text>
              </View>
            ) : (
              <View style={styles.doneRow}>
                <Text style={styles.doneLabel}>累计出成率</Text>
                <Text style={styles.doneValue} testID="cumulative-yield-rate">{cumPct}</Text>
              </View>
            )}
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

          {/* A2b: 首道领料批次选择 (仅 currentStepIndex === 0) */}
          {currentStepIndex === 0 ? (
            <MaterialBatchPicker
              unit={unit}
              value={materialBatchRefs}
              onChange={setMaterialBatchRefs}
              disabled={submitting}
            />
          ) : null}

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

          {/* A4 + P0-3: 超收预检提示. maxAllowed!=null → 绿条边界; ==null → 诚实灰条 (未配置) */}
          {yieldLimits != null ? (
            yieldLimits.maxAllowed != null ? (
              <View style={styles.limitsHint} testID="yield-limits-hint">
                <Text style={styles.limitsHintText}>
                  目标 {yieldLimits.targetQuantity ?? '—'} / 已报 {yieldLimits.alreadyReported ?? 0} / 最多可报 {yieldLimits.remaining ?? '—'} {yieldLimits.unit ?? unit}
                </Text>
              </View>
            ) : (
              <View style={styles.limitsHintMuted} testID="yield-limits-unconfigured">
                <Text style={styles.limitsHintMutedText}>
                  该工序未配置标准出成上限, 无超收边界提示 (可在 web 工序管理配置)
                </Text>
              </View>
            )
          ) : null}

          <YieldQuantityInput
            label="产出量"
            value={outputQty}
            onChangeText={setOutputQty}
            unit={outUnit}
            max={outputHardCap}
            maxHint={
              outputHardCap != null
                ? `产出超过物理上限 ${Math.round(outputHardCap)} ${outUnit} 不可提交 (疑似单位/数量误输)`
                : null
            }
            disabled={submitting}
            testID="yield-output-qty"
          />

          {/* P0-3: 超 2× maxAllowed 的物理墙 — 显式红条 + disable 提交 */}
          {outputOverHardCap ? (
            <View style={styles.hardcapBanner} testID="yield-hardcap-banner">
              <Text style={styles.hardcapText}>
                产出量超过物理上限 {Math.round(outputHardCap ?? 0)} {outUnit}, 请核对 (疑似单位/数量错误)
              </Text>
            </View>
          ) : null}

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
          disabled={submitting || outputOverHardCap}
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
  limitsHintMuted: { backgroundColor: '#F4F4F5', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 6, marginTop: 4 },
  limitsHintMutedText: { fontSize: 13, color: '#909399' },
  hardcapBanner: { backgroundColor: '#FEF0F0', borderRadius: 8, padding: 12, marginTop: 4 },
  hardcapText: { fontSize: 14, color: '#F56C6C', fontWeight: '600' },
  fullBtn: { width: '100%', marginBottom: 12 },
  doneCard: { marginBottom: 20, alignItems: 'center' },
  doneTitle: { fontSize: 20, fontWeight: '700', color: '#67C23A', marginBottom: 12 },
  doneProduct: { fontSize: 18, fontWeight: '600', color: '#1A1A1A' },
  doneBatch: { fontSize: 14, color: '#909399', marginTop: 4, marginBottom: 16 },
  crossUnitBanner: { backgroundColor: '#FDF6EC', borderRadius: 8, padding: 12, marginTop: 8 },
  crossUnitText: { fontSize: 14, color: '#E6A23C', fontWeight: '500', textAlign: 'center' },
  doneRow: { flexDirection: 'row', alignItems: 'baseline', marginTop: 8 },
  doneLabel: { fontSize: 15, color: '#606266', marginRight: 12 },
  doneValue: { fontSize: 28, fontWeight: '700', color: '#E8732E' },
  doneFlow: { fontSize: 15, color: '#606266', marginTop: 12 },
});

export default YieldStepReportScreen;
