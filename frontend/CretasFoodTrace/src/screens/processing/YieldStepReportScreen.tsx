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

// A.6 逐道成本格式化: null (未配工价 / 无原料单价) → "—" (非 ¥0).
const fmtMoney = (v: number | null | undefined): string =>
  v == null || Number.isNaN(Number(v)) ? '—' : `¥${Number(v).toFixed(2)}`;

// A.6 一道成本文案 — 低文化操作工友好: 人工/材料/合计 明确标签.
// 全 null (整道无成本数据) → "未配工价" 诚实提示, 不显 ¥0.
const stepCostLine = (
  s: { laborCost: number | null; materialCost: number | null; stepCost: number | null } | null | undefined,
): string => {
  if (!s) return '';
  if (s.laborCost == null && s.materialCost == null && s.stepCost == null) {
    return '本道成本: 未配工价 / 无原料单价';
  }
  return `本道成本: 人工${fmtMoney(s.laborCost)} + 材料${fmtMoney(s.materialCost)} = ${fmtMoney(s.stepCost)}`;
};

const YieldStepReportScreen: React.FC = () => {
  const navigation = useNavigation<NavT>();
  const route = useRoute<RouteT>();
  const { batchId } = route.params;

  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [phase, setPhase] = useState<'reporting' | 'done'>('reporting');

  const [productType, setProductType] = useState<string>('');
  const [batchNumber, setBatchNumber] = useState<string>(route.params.batchNumber ?? '');
  const [batchStatus, setBatchStatus] = useState<string>('');  // P1-1: 完工幂等判断
  const [tasks, setTasks] = useState<WorkProcessTask[]>([]);
  const [yieldData, setYieldData] = useState<BatchYieldDTO | null>(null);
  const [currentStepIndex, setCurrentStepIndex] = useState(0);

  const [inputQty, setInputQty] = useState('');
  const [outputQty, setOutputQty] = useState('');
  // P1-3 (G4): 本道人数 / 工时 (选填; 张权 "用了多少人 / 一个人一个小时")
  const [workerCount, setWorkerCount] = useState('');
  const [workMinutes, setWorkMinutes] = useState('');
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

  // A.6: 本道已报工的成本 (若该道之前已报过, yieldData.steps 里有对应行带 cost; 未报过则 undefined → 不显示成本行)
  const currentStepYield = useMemo<StepYieldDTO | undefined>(() => {
    if (!currentTask || !yieldData) return undefined;
    return yieldData.steps.find((s: StepYieldDTO) => s.processOrder === currentTask.processOrder);
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
        setBatchStatus(batchRes.data.status ?? '');  // P1-1: 完工幂等判断
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
    setWorkerCount('');
    setWorkMinutes('');
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
  const isFirstStep = currentStepIndex === 0;
  // G7 Wave 4: 非首道可领的上道 WIP 余额 (来自 limits.wipAvailable); 首道为 null (领原料不受 WIP 约束)
  const wipAvailable = yieldLimits?.wipAvailable ?? null;
  // G7 跨单位防呆: WIP 余额单位 = 上道 outputUnit (可能 ≠ 本道 unit); banner/:max 提示用源 WIP 真实单位
  const wipUnit = yieldLimits?.wipAvailableUnit ?? unit;
  const plannedMax = planned != null ? planned * OVER_RECEIVE_TOLERANCE : null;
  // G7: 非首道有 WIP 余额时, 投入硬上限 = WIP 余额 (操作工领不超过可用; 防呆 Rule 1 事前阻止);
  // 与计划超收上限取更严的 (min) 作为 input :max。首道沿用计划超收上限。
  const inputMax =
    wipAvailable != null
      ? plannedMax != null
        ? Math.min(plannedMax, wipAvailable)
        : wipAvailable
      : plannedMax;
  const inputMaxHint =
    wipAvailable != null
      ? `可领上道半成品余额 ${wipAvailable} ${wipUnit} (本道最多领这么多)`
      : planned != null
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
    // P1-3 (G4): 本道人数 / 工时 — 选填整数, 仅 >0 才进 req (不填则后端存 null, 向后兼容)
    const wc = parseInt(workerCount, 10);
    const wm = parseInt(workMinutes, 10);
    // A2b: 首道 + 有批次引用时, 将 materialBatchRefs 随报工单一起提交 (一次请求, 不再双调)
    const req: YieldReportRequest = {
      workProcessTaskId: currentTask.id,
      inputQuantity: Number.isNaN(input) ? 0 : input,
      inputUnit: unit,
      outputQuantity: output,
      outputUnit: outUnit,
      ...(Number.isNaN(wc) || wc <= 0 ? {} : { workerCount: wc }),
      ...(Number.isNaN(wm) || wm <= 0 ? {} : { workMinutes: wm }),
      ...(currentStepIndex === 0 && materialBatchRefs.length > 0
        ? {
            materialBatchRefs: materialBatchRefs.map((r: MaterialBatchRef) => ({
              materialBatchId: r.materialBatchId,
              quantity: r.quantity,
              unit: r.unit ?? unit,
            })),
          }
        : {}),
      // G7 Wave 4: 非首道领用上道 WIP — limits 透出唯一可领 WIP 工序批次号则带回, 后端扣减其余额
      ...(currentStepIndex > 0 && yieldLimits?.sourceWipNo
        ? { sourceWipNo: yieldLimits.sourceWipNo }
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
  }, [currentTask, outputQty, inputQty, unit, outUnit, batchId, currentStepIndex, totalSteps, submitWithForce, materialBatchRefs, workerCount, workMinutes, yieldLimits]);

  // P1-1: 结清 (triggerComplete 决定是否同时完工入库)
  const doSettle = useCallback(async (triggerComplete: boolean) => {
    setSubmitting(true);
    try {
      const res = await yieldReportApi.settleDay(batchId, { triggerComplete });
      if (!res.success) {
        Alert.alert('结清失败', res.message || '请重试');
        return;
      }
      if (res.data.completed) {
        const out = yieldData?.lastStepOutput;
        const unitL = yieldData?.lastStepOutputUnit ?? '';
        // D3 Wave 4: 完工后若仍有在制 WIP 结余 → 附诚实退回提示 (后端给, 不阻塞完工)
        const wipHint = res.data.wipRemainingHint ? `\n${res.data.wipRemainingHint}` : '';
        Alert.alert(
          '已完工入库',
          `${productType || ''} ${batchNumber}\n本次结清 ${res.data.settledCount} 条报工\n` +
          `批次已完工, 末道产出 ${out ?? '—'}${unitL} 已入成品库, 生产计划实际产量已回填` +
          wipHint,
          [{ text: '返回选批次', onPress: () => navigation.goBack() }],
        );
      } else if (res.data.completeError) {
        // Rule 5 next-action: 结清成功但完工失败 (批次未开始生产), 透传后端原因
        Alert.alert(
          '已结清 (批次未完工)',
          `本次结清 ${res.data.settledCount} 条报工\n${res.data.completeError}`,
        );
      } else {
        Alert.alert('已标记今日结清', `本次结清 ${res.data.settledCount} 条报工 (批次未完工)`);
      }
    } catch (error) {
      handleError(error, { showAlert: false, logError: true });
      const e = error as { response?: { data?: { message?: string; hint?: string } } };
      const msg = e.response?.data?.message;
      const hint = e.response?.data?.hint;
      Alert.alert('结清失败', msg ? (hint ? `${msg}\n${hint}` : msg) : '请重试');
    } finally {
      setSubmitting(false);
    }
  }, [batchId, productType, batchNumber, yieldData, navigation]);

  // P1-1: 末道全报完 → 二段确认是否完工入库 (Rule 2 context + Rule 4 幂等)
  const handleSettleDay = useCallback(() => {
    const alreadyCompleted = batchStatus === 'COMPLETED' || batchStatus === 'completed';
    if (alreadyCompleted) {
      // Rule 4 幂等: 批次已完工, 不再重复触发 (后端会返 completeError)
      Alert.alert('批次已完工', `${batchNumber} 已入库, 无需重复完工`, [
        { text: '返回选批次', onPress: () => navigation.goBack() },
      ]);
      return;
    }
    const out = yieldData?.lastStepOutput;
    const unitL = yieldData?.lastStepOutputUnit ?? '';
    // Rule 2 context: 品名 + 批次 + 末道产出
    Alert.alert(
      '完工入库确认',
      `${productType || ''} ${batchNumber}\n末道产出 ${out ?? '—'}${unitL}\n` +
      `确认后: 批次标完工 + 末道产出入成品库 + 回填生产计划实际产量`,
      [
        { text: '暂不完工(仅结清今日)', onPress: () => doSettle(false) },
        { text: '完工入库', style: 'default', onPress: () => doSettle(true) },
      ],
    );
  }, [batchStatus, productType, batchNumber, yieldData, doSettle, navigation]);

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
                <Text style={styles.doneLabel}>
                  {yieldData?.inProgress ? '批次累计出成率 (进行中)' : '整批出成率'}
                </Text>
                <Text style={styles.doneValue} testID="cumulative-yield-rate">{cumPct}</Text>
              </View>
            )}
            {/* G8 Wave 4 (C): 进行中标注 — 在制半成品未计入成品, 数字偏低且会变 (per 设计章一 ★推荐) */}
            {yieldData?.inProgress && (yieldData?.wipInProgressQuantity ?? 0) > 0 ? (
              <View style={styles.inProgressBanner} testID="yield-inprogress-banner">
                <Text style={styles.inProgressText}>
                  进行中: 含 {yieldData.wipInProgressQuantity} {yieldData.wipInProgressUnit ?? ''} 在制半成品未计入成品, 出成率完工后才锁定
                </Text>
              </View>
            ) : !yieldData?.inProgress ? (
              <Text style={styles.lockedNote} testID="yield-locked-note">已完工, 出成率已锁定为最终值</Text>
            ) : null}
            {yieldData?.firstStepInput != null && yieldData?.lastStepOutput != null ? (
              <Text style={styles.doneFlow}>
                {yieldData.firstStepInput}{yieldData.firstStepInputUnit ?? ''} → {yieldData.lastStepOutput}{yieldData.lastStepOutputUnit ?? ''}
              </Text>
            ) : null}
            {/* A.6: 整批成本汇总 — 人工/材料/合计. 全 null → "未配工价" 诚实提示, 不显 ¥0 */}
            {yieldData != null ? (
              <View style={styles.doneCostWrap} testID="yield-batch-cost">
                {yieldData.totalLaborCost == null && yieldData.totalMaterialCost == null && yieldData.totalCost == null ? (
                  <Text style={styles.doneCostMuted}>整批成本: 未配工价 / 无原料单价</Text>
                ) : (
                  <Text style={styles.doneCost}>
                    整批成本: 人工{fmtMoney(yieldData.totalLaborCost)} + 材料{fmtMoney(yieldData.totalMaterialCost)} = {fmtMoney(yieldData.totalCost)}
                  </Text>
                )}
              </View>
            ) : null}
          </NeoCard>
          <NeoButton variant="primary" size="large" onPress={handleSettleDay} disabled={submitting} loading={submitting} style={styles.fullBtn}>
            完工入库
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

          {/* A.6: 本道成本 — 仅该道已报过工 (yieldData 有对应行) 才显; 全 null → "未配工价" 诚实提示, 不显 ¥0 */}
          {currentStepYield ? (
            <Text style={styles.stepCost} testID="yield-step-cost">{stepCostLine(currentStepYield)}</Text>
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

          {/* G7 Wave 4: 非首道领用上道半成品 — 显式显示可领余额 (防呆 Rule 1: dialog 打开即显边界) */}
          {!isFirstStep && wipAvailable != null ? (
            <View style={styles.wipBanner} testID="yield-wip-available">
              <Text style={styles.wipBannerText}>
                可领上道半成品余额 {wipAvailable} {wipUnit}
                {wipAvailable <= 0 ? ' (已领空, 请确认上道是否还需报工产出)' : ' (本道最多领这么多)'}
              </Text>
              {yieldLimits?.sourceWipNo ? (
                <Text style={styles.wipBannerSub}>来源批次 {yieldLimits.sourceWipNo}</Text>
              ) : null}
            </View>
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

          {/* P1-3 (G4): 本道人数 + 工时 (选填) — 张权 "用了多少人 / 一个人一个小时" (Rule 2 context: label 明确"本道") */}
          <YieldQuantityInput
            label="本道人数 (选填)"
            value={workerCount}
            onChangeText={setWorkerCount}
            unit="人"
            disabled={submitting}
            testID="yield-worker-count"
          />
          <YieldQuantityInput
            label="本道工时 (选填)"
            value={workMinutes}
            onChangeText={setWorkMinutes}
            unit="分钟"
            disabled={submitting}
            testID="yield-work-minutes"
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
  // A.6: 本道成本行 (报工卡内, 该道已报过时显示)
  stepCost: { fontSize: 14, color: '#606266', marginTop: 6, fontWeight: '500' },
  divider: { height: 1, backgroundColor: '#EBEEF5', marginVertical: 16 },
  alertBanner: { backgroundColor: '#FDF6EC', borderRadius: 8, padding: 12, marginTop: 4 },
  alertText: { fontSize: 14, color: '#E6A23C', fontWeight: '500' },
  limitsHint: { backgroundColor: '#F0F9EB', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 6, marginTop: 4 },
  limitsHintText: { fontSize: 13, color: '#67C23A' },
  limitsHintMuted: { backgroundColor: '#F4F4F5', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 6, marginTop: 4 },
  limitsHintMutedText: { fontSize: 13, color: '#909399' },
  hardcapBanner: { backgroundColor: '#FEF0F0', borderRadius: 8, padding: 12, marginTop: 4 },
  hardcapText: { fontSize: 14, color: '#F56C6C', fontWeight: '600' },
  // G7 Wave 4: 可领上道半成品余额提示 (蓝条, 防呆 Rule 1)
  wipBanner: { backgroundColor: '#ECF5FF', borderRadius: 8, padding: 12, marginBottom: 8 },
  wipBannerText: { fontSize: 14, color: '#409EFF', fontWeight: '600' },
  wipBannerSub: { fontSize: 12, color: '#909399', marginTop: 4 },
  fullBtn: { width: '100%', marginBottom: 12 },
  doneCard: { marginBottom: 20, alignItems: 'center' },
  doneTitle: { fontSize: 20, fontWeight: '700', color: '#67C23A', marginBottom: 12 },
  doneProduct: { fontSize: 18, fontWeight: '600', color: '#1A1A1A' },
  doneBatch: { fontSize: 14, color: '#909399', marginTop: 4, marginBottom: 16 },
  crossUnitBanner: { backgroundColor: '#FDF6EC', borderRadius: 8, padding: 12, marginTop: 8 },
  crossUnitText: { fontSize: 14, color: '#E6A23C', fontWeight: '500', textAlign: 'center' },
  // G8 Wave 4 (C): 进行中标注 (橙条) / 完工锁定提示
  inProgressBanner: { backgroundColor: '#FDF6EC', borderRadius: 8, padding: 12, marginTop: 12 },
  inProgressText: { fontSize: 13, color: '#E6A23C', fontWeight: '500', textAlign: 'center' },
  lockedNote: { fontSize: 13, color: '#67C23A', marginTop: 10, textAlign: 'center' },
  doneRow: { flexDirection: 'row', alignItems: 'baseline', marginTop: 8 },
  doneLabel: { fontSize: 15, color: '#606266', marginRight: 12 },
  doneValue: { fontSize: 28, fontWeight: '700', color: '#E8732E' },
  doneFlow: { fontSize: 15, color: '#606266', marginTop: 12 },
  // A.6: 整批成本汇总 (done 卡内)
  doneCostWrap: { marginTop: 12 },
  doneCost: { fontSize: 14, color: '#303133', fontWeight: '600', textAlign: 'center' },
  doneCostMuted: { fontSize: 13, color: '#909399', textAlign: 'center' },
});

export default YieldStepReportScreen;
