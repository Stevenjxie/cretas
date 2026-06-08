import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  View, Text, TextInput, TouchableOpacity, Image, ScrollView, StyleSheet,
  ActivityIndicator,
} from 'react-native';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import * as ImagePicker from 'expo-image-picker';
import * as ImageManipulator from 'expo-image-manipulator';
import { ScreenWrapper } from '../../components/ui/ScreenWrapper';
import { NeoCard } from '../../components/ui/NeoCard';
import { NeoButton } from '../../components/ui/NeoButton';
import YieldQuantityInput from '../../components/processing/YieldQuantityInput';
import MaterialBatchPicker, { MaterialBatchRef } from '../../components/processing/MaterialBatchPicker';
import WipBatchPicker, { WipSelection } from '../../components/processing/WipBatchPicker';
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
import { useAuthStore } from '../../store/authStore';
import { appAlert, AppDialogHost } from '../../components/ui/AppDialog';

type YieldStepReportParams = {
  batchId: number;
  batchNumber?: string;
  assignedWorkProcessTaskId?: number;
  assignedProcessOrder?: number;
  autoAssigned?: boolean;
};
type RouteT = RouteProp<{ YieldStepReport: YieldStepReportParams }, 'YieldStepReport'>;
type NavT = NativeStackNavigationProp<Record<string, object | undefined>>;

const OVER_RECEIVE_TOLERANCE = 1.3; // A4 软上限: 计划 ×1.3 (含 30% 超收)
const MAX_EVIDENCE_VIDEO_BYTES = 50 * 1024 * 1024;
const MAX_EVIDENCE_VIDEO_DURATION_MS = 60 * 1000;
const VIDEO_EXTENSIONS = ['mp4', 'mov', 'm4v', 'webm'];

// 三阶段报工 (单元2): 该道当前所处阶段 (从 getYield 的 step.phase 推断)
type StepPhase = 'AWAITING_INPUT' | 'IN_PRODUCTION' | 'COMPLETED';
type ProductionStepMode = 'SEGMENT' | 'OUTPUT';

type EvidenceMediaKind = 'image' | 'video';

// 单元4 STEP 3: 图片/视频证据本地态 (uri 上传中 / 上传完拿 serverUrl)
interface EvidencePhoto {
  uri: string;
  uploading: boolean;
  mediaKind: EvidenceMediaKind;
  serverUrl?: string;
}
// 单元4 STEP 5: 副产物本地态 (名称 + 数量 + 单位)
interface ByproductInput {
  name: string;
  quantity: string;
  unit: string;
}

// A.6 逐道成本格式化: null (未配工价 / 无原料单价) → "—" (非 ¥0).
const fmtMoney = (v: number | null | undefined): string =>
  v == null || Number.isNaN(Number(v)) ? '—' : `¥${Number(v).toFixed(2)}`;

function isEvidenceVideoAsset(asset: ImagePicker.ImagePickerAsset): boolean {
  return asset.type === 'video' || asset.mimeType?.startsWith('video/') === true || isEvidenceVideoUrl(asset.uri);
}

function isEvidenceVideoUrl(url: string): boolean {
  const clean = url.split(/[?#]/)[0]?.toLowerCase() || '';
  return VIDEO_EXTENSIONS.some((ext) => clean.endsWith(`.${ext}`));
}

function evidenceMimeType(asset: ImagePicker.ImagePickerAsset, mediaKind: EvidenceMediaKind): string {
  if (asset.mimeType) return asset.mimeType;
  if (mediaKind === 'image') return 'image/jpeg';
  const clean = asset.uri.split(/[?#]/)[0]?.toLowerCase() || '';
  if (clean.endsWith('.mov')) return 'video/quicktime';
  if (clean.endsWith('.webm')) return 'video/webm';
  return 'video/mp4';
}

function evidenceFileName(mimeType: string): string {
  const ext = mimeType.includes('quicktime')
    ? 'mov'
    : mimeType.includes('webm')
      ? 'webm'
      : mimeType.startsWith('video/')
        ? 'mp4'
        : 'jpg';
  return `yield_evidence_${Date.now()}.${ext}`;
}

function validateEvidenceVideo(asset: ImagePicker.ImagePickerAsset): boolean {
  if (typeof asset.fileSize === 'number' && asset.fileSize > MAX_EVIDENCE_VIDEO_BYTES) {
    appAlert('视频太大', '单个视频不能超过 50MB。请截短后再上传，或改拍关键照片。');
    return false;
  }
  if (typeof asset.duration === 'number' && asset.duration > MAX_EVIDENCE_VIDEO_DURATION_MS) {
    appAlert('视频太长', '单个留证视频建议控制在 60 秒内。请截短后再上传。');
    return false;
  }
  return true;
}

const YieldStepReportScreen: React.FC = () => {
  const navigation = useNavigation<NavT>();
  const route = useRoute<RouteT>();
  const {
    batchId,
    assignedWorkProcessTaskId,
    assignedProcessOrder,
    autoAssigned = false,
  } = route.params;

  // 角色/身份 (Task 7 — 小组长过滤 + 完工入库主管权限)
  const { getUserId, getUserRole } = useAuthStore();
  const currentUserId = getUserId();
  const currentRole = getUserRole();
  // 操作工 (小组长) vs 主管: operator 只见自己的任务 + 不能完工入库
  const isOperator = currentRole === 'operator';

  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [screenPhase, setScreenPhase] = useState<'reporting' | 'done'>('reporting');

  const [productType, setProductType] = useState<string>('');
  const [batchNumber, setBatchNumber] = useState<string>(route.params.batchNumber ?? '');
  const [batchStatus, setBatchStatus] = useState<string>('');  // P1-1: 完工幂等判断
  const [tasks, setTasks] = useState<WorkProcessTask[]>([]);
  const [yieldData, setYieldData] = useState<BatchYieldDTO | null>(null);
  const [currentStepIndex, setCurrentStepIndex] = useState(0);

  // 投入阶段输入
  const [inputQty, setInputQty] = useState('');
  // A4: 投入超收预检
  const [yieldLimits, setYieldLimits] = useState<YieldLimitsDTO | null>(null);
  const limitsDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // A2b: 首道领料批次引用
  const [materialBatchRefs, setMaterialBatchRefs] = useState<MaterialBatchRef[]>([]);
  // 单元D (F006 #5): 上道多笔 WIP 时操作工选中的领用批次 (单选; null = 未选或不适用)
  const [selectedWip, setSelectedWip] = useState<WipSelection | null>(null);

  // 生产阶段 — 完工出成块输入
  const [outputQty, setOutputQty] = useState('');
  const [byproducts, setByproducts] = useState<ByproductInput[]>([]);          // 副产物
  const [wasteQty, setWasteQty] = useState('');                                // 损耗量
  const [sampleRetainQty, setSampleRetainQty] = useState('');                  // 留样

  // 生产阶段 — 单段工时报工块 (一次提交一段, 累加; 张权 多段开工/收工)
  const [segStart, setSegStart] = useState('');
  const [segEnd, setSegEnd] = useState('');
  const [segHeadcount, setSegHeadcount] = useState('');
  const [segNote, setSegNote] = useState('');
  const [segProcessedQty, setSegProcessedQty] = useState('');
  const [segStageOutputQty, setSegStageOutputQty] = useState('');
  const [segWasteQty, setSegWasteQty] = useState('');
  const [segByproducts, setSegByproducts] = useState<ByproductInput[]>([]);

  // 图片证据 (投入阶段 / 生产时段段 / 完工出成 各自一份, 切阶段清空)
  const [evidencePhotos, setEvidencePhotos] = useState<EvidencePhoto[]>([]);
  const [productionStepMode, setProductionStepMode] = useState<ProductionStepMode>('SEGMENT');

  const [lastAlert, setLastAlert] = useState<'BELOW_MIN' | 'ABOVE_MAX' | null>(null);

  const currentTask = tasks[currentStepIndex] ?? null;
  const totalSteps = tasks.length;

  // 当前道的 yield step (从 getYield 取; 无报工时不存在 → undefined → AWAITING_INPUT)
  const currentStepYield = useMemo<StepYieldDTO | undefined>(() => {
    if (!currentTask || !yieldData) return undefined;
    return yieldData.steps.find((s: StepYieldDTO) => s.processOrder === currentTask.processOrder);
  }, [currentTask, yieldData]);

  // 三阶段 (单元2): 当前道阶段. step 不存在 (无报工) 或 phase 缺省 → AWAITING_INPUT.
  const stepPhaseOf = useCallback(
    (task: WorkProcessTask | null): StepPhase => {
      if (!task || !yieldData) return 'AWAITING_INPUT';
      const s = yieldData.steps.find((st: StepYieldDTO) => st.processOrder === task.processOrder);
      if (!s) return 'AWAITING_INPUT';
      return (s.phase as StepPhase) ?? 'AWAITING_INPUT';
    },
    [yieldData],
  );
  const currentPhase = useMemo<StepPhase>(() => stepPhaseOf(currentTask), [stepPhaseOf, currentTask]);

  // 上道产出 (投入阶段预填): 取 yield steps 里 processOrder == 当前道-1 的 totalOutput
  const prevOutput = useMemo<number | null>(() => {
    if (!currentTask || !yieldData) return null;
    const prevOrder = currentTask.processOrder - 1;
    const prevStep = yieldData.steps.find((s: StepYieldDTO) => s.processOrder === prevOrder);
    return prevStep?.totalOutput ?? null;
  }, [currentTask, yieldData]);

  const loadAll = useCallback(async () => {
    try {
      // Task 7 (小组长过滤): operator 传自己的 userId → 后端只返回分配给自己 + 未分配的任务;
      // 主管省略 assignedTo → 后端返回全部任务.
      const assignedTo = isOperator && currentUserId != null ? currentUserId : undefined;
      const [tasksRes, batchRes, yieldRes] = await Promise.all([
        yieldReportApi.listWorkProcessTasks(batchId, undefined, assignedTo),
        processingApiClient.getBatchById(String(batchId)),
        yieldReportApi.getYield(batchId),
      ]);
      let sortedTasks: WorkProcessTask[] = [];
      if (tasksRes.success) {
        const visibleTasks = isOperator && currentUserId != null
          ? tasksRes.data.filter((task) => task.assignedTo === currentUserId)
          : tasksRes.data;
        sortedTasks = [...visibleTasks].sort((a, b) => a.processOrder - b.processOrder);
        setTasks(sortedTasks);
      }
      if (batchRes.success && batchRes.data) {
        setProductType(batchRes.data.productType ?? '');
        if (batchRes.data.batchNumber) setBatchNumber(batchRes.data.batchNumber);
        setBatchStatus(batchRes.data.status ?? '');  // P1-1: 完工幂等判断
      }
      const yd = yieldRes.success ? yieldRes.data : null;
      if (yd) setYieldData(yd);

      // 三阶段 (单元2): operator 自动分配模式锁定后台分配的本道;
      // 普通模式首次加载自动跳到第一道未完成 (phase != COMPLETED) 的道.
      if (sortedTasks.length > 0) {
        const phaseFor = (task: WorkProcessTask): StepPhase => {
          if (!yd) return 'AWAITING_INPUT';
          const s = yd.steps.find((st: StepYieldDTO) => st.processOrder === task.processOrder);
          return (s?.phase as StepPhase) ?? 'AWAITING_INPUT';
        };
        if (autoAssigned) {
          const assignedIndex = sortedTasks.findIndex((task) => {
            if (assignedWorkProcessTaskId != null && task.id === assignedWorkProcessTaskId) return true;
            return assignedProcessOrder != null && task.processOrder === assignedProcessOrder;
          });
          if (assignedIndex !== -1) {
            setCurrentStepIndex(assignedIndex);
            setScreenPhase('reporting');
            return;
          }
        }
        const firstUnfinished = sortedTasks.findIndex((t) => phaseFor(t) !== 'COMPLETED');
        if (firstUnfinished === -1) {
          setScreenPhase('done');
        } else {
          setCurrentStepIndex(firstUnfinished);
        }
      }
    } catch (error) {
      handleError(error, { showAlert: false, logError: true });
      const msg = error instanceof Error ? error.message : '加载批次工序失败';
      appAlert('加载失败', msg);
    } finally {
      setLoading(false);
    }
  }, [batchId, isOperator, currentUserId, autoAssigned, assignedWorkProcessTaskId, assignedProcessOrder]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  // 切道时重置所有阶段输入态 (每道独立). 投入预填上道产出.
  const resetStepInputs = useCallback(() => {
    setInputQty(prevOutput != null ? String(prevOutput) : '');
    setOutputQty('');
    setLastAlert(null);
    setYieldLimits(null);
    setMaterialBatchRefs([]);
    setSelectedWip(null);
    setEvidencePhotos([]);
    setByproducts([]);
    setWasteQty('');
    setSampleRetainQty('');
    setSegStart('');
    setSegEnd('');
    setSegHeadcount('');
    setSegNote('');
    setSegProcessedQty('');
    setSegStageOutputQty('');
    setSegWasteQty('');
    setSegByproducts([]);
    setProductionStepMode('SEGMENT');
  }, [prevOutput]);

  useEffect(() => {
    if (screenPhase !== 'reporting') return;
    resetStepInputs();
    // 仅在切道时 reset (currentStepIndex 变); resetStepInputs 依赖 prevOutput 已含进去.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentStepIndex, screenPhase]);

  useEffect(() => {
    if (currentPhase === 'IN_PRODUCTION') {
      setProductionStepMode('SEGMENT');
    }
  }, [currentTask?.id, currentPhase]);

  // A4: 投入量变化后 debounce 500ms 拉超收上限 (仅投入阶段需要)
  useEffect(() => {
    if (limitsDebounceRef.current) clearTimeout(limitsDebounceRef.current);
    if (!currentTask || currentPhase !== 'AWAITING_INPUT') return;
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
  }, [inputQty, currentTask, currentPhase, batchId]);

  // 生产阶段产出超收预检: 进入 IN_PRODUCTION 即拉一次 limits (用本道已报投入量做基准).
  useEffect(() => {
    if (!currentTask || currentPhase !== 'IN_PRODUCTION') return;
    const reportedInput = currentStepYield?.totalInput ?? null;
    if (reportedInput == null || reportedInput <= 0) return;
    (async () => {
      try {
        const res = await yieldReportApi.getYieldLimits(batchId, currentTask.id, reportedInput);
        if (res.success) setYieldLimits(res.data);
      } catch {
        // 静默忽略
      }
    })();
  }, [currentTask, currentPhase, currentStepYield, batchId]);

  const unit = currentTask?.plannedUnit ?? 'kg';
  // P0-2: 本道产出单位 — 工序配了 outputUnit (如末道 kg→份/盒) 则用它, 否则沿用投入单位
  const outUnit = currentTask?.outputUnit ?? unit;
  const planned = currentTask?.plannedQuantity ?? null;
  const isFirstStep = currentStepIndex === 0;
  const isLastStep = currentStepIndex >= totalSteps - 1;
  // G7 Wave 4: 非首道可领的上道 WIP 余额 (来自 limits.wipAvailable); 首道为 null (领原料不受 WIP 约束)
  const wipAvailable = yieldLimits?.wipAvailable ?? null;
  // 单元D (F006 #5): 上道多笔 WIP — sourceWipNo 歧义 (null) 但 wipAvailable>0 → 需操作工单选;
  const needsWipPicker = !isFirstStep && yieldLimits != null && yieldLimits.sourceWipNo == null && (wipAvailable ?? 0) > 0;
  const effectiveSourceWipNo = needsWipPicker ? (selectedWip?.sourceWipNo ?? null) : (yieldLimits?.sourceWipNo ?? null);
  const effectiveWipAvailable = needsWipPicker
    ? (selectedWip?.availableQuantity ?? null)
    : wipAvailable;
  const wipUnit = (needsWipPicker ? selectedWip?.unit : yieldLimits?.wipAvailableUnit) ?? unit;
  const plannedMax = planned != null ? planned * OVER_RECEIVE_TOLERANCE : null;
  const inputMax =
    effectiveWipAvailable != null
      ? plannedMax != null
        ? Math.min(plannedMax, effectiveWipAvailable)
        : effectiveWipAvailable
      : plannedMax;
  const inputMaxHint =
    effectiveWipAvailable != null
      ? `可领上道半成品余额 ${effectiveWipAvailable} ${wipUnit} (本道最多领这么多)`
      : planned != null
        ? `计划 ${planned} ${unit}, 可投上限约 ${Math.round(planned * OVER_RECEIVE_TOLERANCE)} (含 30% 超收)`
        : null;
  const prefillNote =
    prevOutput != null
      ? `← 上道产出 ${prevOutput} ${unit}, 请确认实际投了多少`
      : '本道为首道, 请填本道领料投入量';

  // P0-3: 产出绝对物理上限 = 2 × maxAllowed
  const OUTPUT_HARD_CAP_MULTIPLIER = 2;
  const outputHardCap = useMemo<number | null>(() => {
    if (!yieldLimits || yieldLimits.maxAllowed == null) return null;
    return yieldLimits.maxAllowed * OUTPUT_HARD_CAP_MULTIPLIER;
  }, [yieldLimits]);
  const outputOverHardCap = useMemo<boolean>(() => {
    const out = parseFloat(outputQty);
    return outputHardCap != null && !Number.isNaN(out) && out > outputHardCap;
  }, [outputQty, outputHardCap]);

  // 单元D: 上道多笔 WIP 但未选领用批次 → 阻塞投入提交.
  const submitBlockedNoWip = needsWipPicker && selectedWip == null;
  // 上传中 (任一证据) → 阻塞提交, 避免 evidenceImages 丢 URL
  const evidenceUploading = evidencePhotos.some((p) => p.uploading);

  // ── 图片/视频证据 → 图片压缩 / 视频预检 → 上传 OSS → 收集 URL (三阶段共用) ──
  const uploadEvidence = useCallback(async (asset: ImagePicker.ImagePickerAsset) => {
    const mediaKind: EvidenceMediaKind = isEvidenceVideoAsset(asset) ? 'video' : 'image';
    if (mediaKind === 'video' && !validateEvidenceVideo(asset)) return;

    const localUri = mediaKind === 'video'
      ? asset.uri
      : (await ImageManipulator.manipulateAsync(
          asset.uri,
          [{ resize: { width: 1024 } }],
          { compress: 0.7, format: ImageManipulator.SaveFormat.JPEG },
        )).uri;
    const mimeType = evidenceMimeType(asset, mediaKind);

    setEvidencePhotos((prev) => [...prev, { uri: localUri, uploading: true, mediaKind }]);
    try {
      const url = await yieldReportApi.uploadYieldEvidence(localUri, {
        mimeType,
        fileName: evidenceFileName(mimeType),
      });
      setEvidencePhotos((prev) =>
        prev.map((p) => (p.uri === localUri ? { ...p, uploading: false, serverUrl: url } : p)),
      );
    } catch (err) {
      setEvidencePhotos((prev) => prev.filter((p) => p.uri !== localUri));
      const e = err as { response?: { data?: { message?: string } } };
      appAlert('证据上传失败', e.response?.data?.message ?? '请重试 (网络/格式/大小)');
    }
  }, []);

  const takeEvidencePhoto = useCallback(async () => {
    const permission = await ImagePicker.requestCameraPermissionsAsync();
    if (!permission.granted) {
      appAlert('需要相机权限', '请在系统设置开启相机权限后再拍照');
      return;
    }
    const result = await ImagePicker.launchCameraAsync({
      mediaTypes: ['images', 'videos'],
      quality: 0.8,
      allowsEditing: false,
    });
    if (!result.canceled && result.assets?.[0]) {
      await uploadEvidence(result.assets[0]);
    }
  }, [uploadEvidence]);

  const pickEvidencePhoto = useCallback(async () => {
    const permission = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!permission.granted) {
      appAlert('需要相册权限', '请在系统设置开启相册权限后再选图');
      return;
    }
    // B5: allowsMultipleSelection so operators can pick several photos in one tap.
    // The upload loop already handles arrays (uploadEvidence is called per-asset).
    // Camera remains single-shot (custom multi-shot = out of scope).
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images', 'videos'],
      quality: 0.8,
      allowsEditing: false,
      allowsMultipleSelection: true,
    });
    if (!result.canceled && result.assets && result.assets.length > 0) {
      // Upload all selected assets in parallel
      await Promise.all(result.assets.map((asset) => uploadEvidence(asset)));
    }
  }, [uploadEvidence]);

  const removeEvidencePhoto = useCallback((uri: string) => {
    setEvidencePhotos((prev) => prev.filter((p) => p.uri !== uri));
  }, []);

  // ── 副产物 (完工出成块) ──
  const addByproduct = useCallback(() => {
    setByproducts((prev) => [...prev, { name: '', quantity: '', unit: '' }]);
  }, []);
  const updateByproduct = useCallback(
    (idx: number, field: keyof ByproductInput, val: string) => {
      setByproducts((prev) => prev.map((b, i) => (i === idx ? { ...b, [field]: val } : b)));
    },
    [],
  );
  const removeByproduct = useCallback((idx: number) => {
    setByproducts((prev) => prev.filter((_, i) => i !== idx));
  }, []);

  const addSegByproduct = useCallback(() => {
    setSegByproducts((prev) => [...prev, { name: '', quantity: '', unit: '' }]);
  }, []);
  const updateSegByproduct = useCallback(
    (idx: number, field: keyof ByproductInput, val: string) => {
      setSegByproducts((prev) => prev.map((b, i) => (i === idx ? { ...b, [field]: val } : b)));
    },
    [],
  );
  const removeSegByproduct = useCallback((idx: number) => {
    setSegByproducts((prev) => prev.filter((_, i) => i !== idx));
  }, []);

  // 提交后刷新 yield (供阶段重渲染 / 下道预填)
  const refetchYield = useCallback(async () => {
    const yieldRes = await yieldReportApi.getYield(batchId);
    if (yieldRes.success) setYieldData(yieldRes.data);
  }, [batchId]);

  // 已上传成功的证据 URL
  const uploadedEvidenceUrls = useMemo(
    () => evidencePhotos.map((p) => p.serverUrl).filter((u): u is string => !!u),
    [evidencePhotos],
  );

  const switchProductionStepMode = useCallback((nextMode: ProductionStepMode) => {
    if (productionStepMode === nextMode) return;
    if (evidenceUploading) {
      appAlert('证据上传中', '请等照片或视频上传完成后再切换步骤');
      return;
    }

    const applySwitch = () => {
      setEvidencePhotos([]);
      setProductionStepMode(nextMode);
    };

    if (evidencePhotos.length > 0) {
      appAlert(
        '当前证据还没提交',
        '这些照片/视频只属于当前步骤。请先提交当前步骤，或放弃这些证据后再切换。',
        [
          { text: '先不切换', style: 'cancel' },
          { text: '放弃证据并切换', style: 'destructive', onPress: applySwitch },
        ],
      );
      return;
    }

    applySwitch();
  }, [productionStepMode, evidenceUploading, evidencePhotos.length]);

  // ========================= 阶段 1: 提交投入 (reportKind=INPUT) =========================
  const handleSubmitInput = useCallback(async () => {
    if (!currentTask) return;
    if (submitBlockedNoWip) {
      appAlert('请选择半成品批次', '上道有多笔半成品, 请先选择本道要领用的那一笔再提交');
      return;
    }
    // B1: first-step (投入) requires at least one material batch to be selected.
    if (isFirstStep && materialBatchRefs.length === 0) {
      appAlert('请先选择领料批次', '首道投入需要指定领用的原料批次，请展开"领料批次 *"并选择至少一批');
      return;
    }
    if (evidenceUploading) {
      appAlert('证据上传中', '请等照片或视频上传完成再提交');
      return;
    }
    // Q1 单一数据源: 多批次时投入量 = Σ 各批次用量 (materialBatchRefs 已含各 qty), 不用 inputQty
    const isMultiBatchSubmit = isFirstStep && materialBatchRefs.length > 1;
    const input = isMultiBatchSubmit
      ? materialBatchRefs.reduce((s, r) => s + r.quantity, 0)
      : parseFloat(inputQty);
    if (Number.isNaN(input) || input <= 0) {
      appAlert('请填写本道投入量', '投入量必须大于 0');
      return;
    }
    const req: YieldReportRequest = {
      workProcessTaskId: currentTask.id,
      reportKind: 'INPUT',
      inputQuantity: input,
      inputUnit: unit,
      outputQuantity: 0,  // 后端按 reportKind=INPUT 强制忽略 output
      ...(isFirstStep && materialBatchRefs.length > 0
        ? {
            materialBatchRefs: materialBatchRefs.map((r: MaterialBatchRef) => ({
              materialBatchId: r.materialBatchId,
              quantity: r.quantity,
              unit: r.unit ?? unit,
            })),
          }
        : {}),
      ...(!isFirstStep && effectiveSourceWipNo ? { sourceWipNo: effectiveSourceWipNo } : {}),
      ...(uploadedEvidenceUrls.length > 0 ? { evidenceImages: uploadedEvidenceUrls } : {}),
    };
    setSubmitting(true);
    try {
      const res = await yieldReportApi.submitReport(batchId, req);
      if (!res.success) {
        appAlert('提交失败', res.message || '请重试');
        return;
      }
      await refetchYield();
      // 投入提交成功 → 该道转 IN_PRODUCTION; 清生产阶段输入残留
      setEvidencePhotos([]);
      appAlert('投入已提交', '本道进入生产阶段, 可分多段报工时, 完工时录产出');
    } catch (error) {
      handleError(error, { showAlert: false, logError: true });
      const e = error as { response?: { data?: { message?: string; hint?: string } } };
      const backendMsg = e.response?.data?.message;
      const hint = e.response?.data?.hint;
      const msg = backendMsg
        ? hint ? `${backendMsg}\n${hint}` : backendMsg
        : error instanceof Error ? error.message : '提交失败, 请重试';
      appAlert('提交失败', msg);
    } finally {
      setSubmitting(false);
    }
  }, [currentTask, submitBlockedNoWip, evidenceUploading, inputQty, unit, isFirstStep,
      materialBatchRefs, effectiveSourceWipNo, uploadedEvidenceUrls, batchId, refetchYield]);

  // ========================= 阶段 2a: 提交本段工时 (reportKind=SEGMENT) =========================
  const handleSubmitSegment = useCallback(async () => {
    if (!currentTask) return;
    if (evidenceUploading) {
      appAlert('证据上传中', '请等照片或视频上传完成再提交');
      return;
    }
    const hc = parseInt(segHeadcount, 10);
    if (!segStart.trim() || !segEnd.trim() || Number.isNaN(hc) || hc <= 0) {
      appAlert('请填写本段工时', '开始时间 / 结束时间 / 人数都要填 (人数 > 0)');
      return;
    }
    const processed = segProcessedQty.trim() ? parseFloat(segProcessedQty) : null;
    const stageOut = segStageOutputQty.trim() ? parseFloat(segStageOutputQty) : null;
    const segWaste = segWasteQty.trim() ? parseFloat(segWasteQty) : null;
    if ((processed != null && (Number.isNaN(processed) || processed <= 0))
      || (stageOut != null && (Number.isNaN(stageOut) || stageOut < 0))
      || (segWaste != null && (Number.isNaN(segWaste) || segWaste < 0))) {
      appAlert('请核对过程数量', '本段处理量必须大于 0；阶段产出和损耗不能为负数');
      return;
    }
    const validSegByproducts = segByproducts
      .filter((bp) => bp.name.trim() || bp.quantity.trim())
      .map((bp) => ({
        name: bp.name.trim(),
        quantity: parseFloat(bp.quantity),
        ...(bp.unit.trim() ? { unit: bp.unit.trim() } : { unit }),
      }));
    if (validSegByproducts.some((bp) => !bp.name || Number.isNaN(bp.quantity) || bp.quantity <= 0)) {
      appAlert('请核对本段副产物', '副产物需要同时填写名称和大于 0 的数量');
      return;
    }
    const seg = {
      startTime: segStart.trim(),
      endTime: segEnd.trim(),
      headcount: hc,
      ...(segNote.trim() ? { note: segNote.trim() } : {}),
      ...(processed != null ? { processedQuantity: processed, processedUnit: unit } : {}),
      ...(stageOut != null ? { stageOutputQuantity: stageOut, stageOutputUnit: unit } : {}),
      ...(segWaste != null ? { segmentWasteQuantity: segWaste, segmentWasteUnit: unit } : {}),
      ...(validSegByproducts.length > 0 ? { byproducts: validSegByproducts } : {}),
    };
    const req: YieldReportRequest = {
      workProcessTaskId: currentTask.id,
      reportKind: 'SEGMENT',
      inputQuantity: 0,  // 后端按 reportKind=SEGMENT 强制忽略 input/output
      outputQuantity: 0,
      laborSegments: [seg],
      ...(uploadedEvidenceUrls.length > 0 ? { evidenceImages: uploadedEvidenceUrls } : {}),
    };
    setSubmitting(true);
    try {
      const res = await yieldReportApi.submitReport(batchId, req);
      if (!res.success) {
        appAlert('提交失败', res.message || '请重试');
        return;
      }
      await refetchYield();
      // 留在生产阶段, 清空本段输入可再加一段
      setSegStart('');
      setSegEnd('');
      setSegHeadcount('');
      setSegNote('');
      setSegProcessedQty('');
      setSegStageOutputQty('');
      setSegWasteQty('');
      setSegByproducts([]);
      setEvidencePhotos([]);
    } catch (error) {
      handleError(error, { showAlert: false, logError: true });
      const e = error as { response?: { data?: { message?: string; hint?: string } } };
      const backendMsg = e.response?.data?.message;
      const hint = e.response?.data?.hint;
      const msg = backendMsg
        ? hint ? `${backendMsg}\n${hint}` : backendMsg
        : error instanceof Error ? error.message : '提交失败, 请重试';
      appAlert('提交失败', msg);
    } finally {
      setSubmitting(false);
    }
  }, [currentTask, evidenceUploading, segStart, segEnd, segHeadcount, segNote,
      segProcessedQty, segStageOutputQty, segWasteQty, segByproducts, unit,
      uploadedEvidenceUrls, batchId, refetchYield]);

  // ========================= 阶段 2b: 完工出成 (reportKind=OUTPUT) =========================
  // A4: 强制提交 (OVER_RECEIPT 确认后调用)
  const submitOutputWithForce = useCallback(async (req: YieldReportRequest) => {
    setSubmitting(true);
    try {
      const res = await yieldReportApi.submitReport(batchId, { ...req, forceSubmit: true });
      if (!res.success) {
        appAlert('提交失败', res.message || '请重试');
        return;
      }
      setLastAlert(res.data.alert ?? null);
      await refetchYield();
      setEvidencePhotos([]);
    } catch (forceError) {
      handleError(forceError, { showAlert: false, logError: true });
      const fe = forceError as { response?: { data?: { message?: string } } };
      appAlert('提交失败', fe.response?.data?.message ?? '请重试');
    } finally {
      setSubmitting(false);
    }
  }, [batchId, refetchYield]);

  const doSubmitOutput = useCallback(async () => {
    if (!currentTask) return;
    if (evidenceUploading) {
      appAlert('证据上传中', '请等照片或视频上传完成再提交');
      return;
    }
    const output = parseFloat(outputQty);
    if (Number.isNaN(output) || output <= 0) {
      appAlert('请填写本道产出量', '产出量必须大于 0');
      return;
    }
    const validByproducts = byproducts
      .filter((b) => b.name.trim() && parseFloat(b.quantity) > 0)
      .map((b) => ({
        name: b.name.trim(),
        quantity: parseFloat(b.quantity),
        ...(b.unit.trim() ? { unit: b.unit.trim() } : {}),
      }));
    const wasteNum = parseFloat(wasteQty);
    const sampleNum = parseInt(sampleRetainQty, 10);
    const req: YieldReportRequest = {
      workProcessTaskId: currentTask.id,
      reportKind: 'OUTPUT',
      inputQuantity: 0,  // 后端按 reportKind=OUTPUT 强制忽略 input
      outputQuantity: output,
      outputUnit: outUnit,
      ...(validByproducts.length > 0 ? { byproducts: validByproducts } : {}),
      ...(Number.isNaN(wasteNum) || wasteNum < 0 ? {} : { wasteQuantity: wasteNum }),
      ...(Number.isNaN(sampleNum) || sampleNum <= 0 ? {} : { sampleRetainQuantity: sampleNum }),
      ...(uploadedEvidenceUrls.length > 0 ? { evidenceImages: uploadedEvidenceUrls } : {}),
    };
    setSubmitting(true);
    try {
      const res = await yieldReportApi.submitReport(batchId, req);
      if (!res.success) {
        appAlert('提交失败', res.message || '请重试');
        return;
      }
      setLastAlert(res.data.alert ?? null);
      await refetchYield();
      setEvidencePhotos([]);
    } catch (error) {
      // A4: OVER_RECEIPT (HTTP 409) → 弹超收确认框
      const e = error as { response?: { data?: { success?: boolean; message?: string; errorCode?: string; actionHint?: string; hint?: string } } };
      const errorCode = e.response?.data?.errorCode;
      if (errorCode === 'OVER_RECEIPT') {
        handleError(error, { showAlert: false, logError: true });
        const actionHint = e.response?.data?.actionHint ?? e.response?.data?.message ?? '产出已超收告警上限, 确认要超收提交吗?';
        appAlert('超收确认', actionHint, [
          { text: '取消', style: 'cancel' },
          { text: '确认超收提交', onPress: () => submitOutputWithForce(req) },
        ]);
        return;
      }
      handleError(error, { showAlert: false, logError: true });
      const backendMsg = e.response?.data?.message;
      const hint = e.response?.data?.hint;
      const msg = backendMsg
        ? hint ? `${backendMsg}\n${hint}` : backendMsg
        : error instanceof Error ? error.message : '提交失败, 请重试';
      appAlert('提交失败', msg);
    } finally {
      setSubmitting(false);
    }
  }, [currentTask, evidenceUploading, outputQty, byproducts, wasteQty, sampleRetainQty,
      outUnit, uploadedEvidenceUrls, batchId, refetchYield, submitOutputWithForce]);

  // 完工二次确认 (Rule: 出成率锁定)
  const handleSubmitOutput = useCallback(() => {
    if (outputOverHardCap) {
      appAlert('产出量异常', '产出量超过物理上限, 请核对 (疑似单位/数量错误)');
      return;
    }
    appAlert(
      '完工出成确认',
      `${productType || ''} ${currentTask?.processName ?? ''}\n完工后本道出成率锁定, 确认要完工出成吗?`,
      [
        { text: '取消', style: 'cancel' },
        { text: '确认完工出成', style: 'default', onPress: () => doSubmitOutput() },
      ],
    );
  }, [outputOverHardCap, productType, currentTask, doSubmitOutput]);

  // ========================= 完成阶段: 下一道 =========================
  const goNextStep = useCallback(() => {
    // 找下一个未完成的道; 若已是末道且全完成 → 跳 done 卡 (整批汇总 + 完工入库)
    const nextUnfinished = tasks.findIndex(
      (t, i) => i > currentStepIndex && stepPhaseOf(t) !== 'COMPLETED',
    );
    if (nextUnfinished !== -1) {
      setCurrentStepIndex(nextUnfinished);
      return;
    }
    // 之后无未完成的道. 若全部道都 COMPLETED → done 卡; 否则回到首个未完成 (前面跳过的)
    const anyUnfinished = tasks.findIndex((t) => stepPhaseOf(t) !== 'COMPLETED');
    if (anyUnfinished === -1) {
      setScreenPhase('done');
    } else {
      setCurrentStepIndex(anyUnfinished);
    }
  }, [tasks, currentStepIndex, stepPhaseOf]);

  // P1-1: 结清 (triggerComplete 决定是否同时完工入库)
  const doSettle = useCallback(async (triggerComplete: boolean) => {
    setSubmitting(true);
    try {
      const res = await yieldReportApi.settleDay(batchId, { triggerComplete });
      if (!res.success) {
        appAlert('结清失败', res.message || '请重试');
        return;
      }
      if (res.data.completed) {
        const out = yieldData?.lastStepOutput;
        const unitL = yieldData?.lastStepOutputUnit ?? '';
        const wipHint = res.data.wipRemainingHint ? `\n${res.data.wipRemainingHint}` : '';
        appAlert(
          '已完工入库',
          `${productType || ''} ${batchNumber}\n本次结清 ${res.data.settledCount} 条报工\n` +
          `批次已完工, 末道产出 ${out ?? '—'}${unitL} 已入成品库, 生产计划实际产量已回填` +
          wipHint,
          [{ text: '返回选批次', onPress: () => navigation.goBack() }],
        );
      } else if (res.data.completeError) {
        appAlert(
          '已结清 (批次未完工)',
          `本次结清 ${res.data.settledCount} 条报工\n${res.data.completeError}`,
        );
      } else {
        appAlert('已标记今日结清', `本次结清 ${res.data.settledCount} 条报工 (批次未完工)`);
      }
    } catch (error) {
      handleError(error, { showAlert: false, logError: true });
      const e = error as { response?: { data?: { message?: string; hint?: string } } };
      const msg = e.response?.data?.message;
      const hint = e.response?.data?.hint;
      appAlert('结清失败', msg ? (hint ? `${msg}\n${hint}` : msg) : '请重试');
    } finally {
      setSubmitting(false);
    }
  }, [batchId, productType, batchNumber, yieldData, navigation]);

  const handleSettleDay = useCallback(() => {
    const alreadyCompleted = batchStatus === 'COMPLETED' || batchStatus === 'completed';
    if (alreadyCompleted) {
      appAlert('批次已完工', `${batchNumber} 已入库, 无需重复完工`, [
        { text: '返回选批次', onPress: () => navigation.goBack() },
      ]);
      return;
    }
    const out = yieldData?.lastStepOutput;
    const unitL = yieldData?.lastStepOutputUnit ?? '';
    appAlert(
      '完工入库确认',
      `${productType || ''} ${batchNumber}\n末道产出 ${out ?? '—'}${unitL}\n` +
      `确认后: 批次标完工 + 末道产出入成品库 + 回填生产计划实际产量`,
      [
        { text: '暂不完工(仅结清今日)', onPress: () => doSettle(false) },
        { text: '完工入库', style: 'default', onPress: () => doSettle(true) },
      ],
    );
  }, [batchStatus, productType, batchNumber, yieldData, doSettle, navigation]);

  const returnOrRefreshAssignedTask = useCallback(() => {
    if (autoAssigned) {
      navigation.replace('OperatorAssignedProcess');
      return;
    }
    navigation.goBack();
  }, [autoAssigned, navigation]);

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
          <NeoButton variant="outline" size="large" onPress={returnOrRefreshAssignedTask} style={styles.fullBtn}>
            {autoAssigned ? '刷新当前工序' : '返回选批次'}
          </NeoButton>
        </View>
      </ScreenWrapper>
    );
  }

  // ========================= done 卡: 整批汇总 + 完工入库 =========================
  if (screenPhase === 'done') {
    const cum = yieldData?.cumulativeYieldRate;
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
          {/* Task 7 (C6): 完工入库仅主管可见, operator 隐藏此按钮 */}
          {!isOperator && (
            <NeoButton variant="primary" size="large" onPress={handleSettleDay} disabled={submitting} loading={submitting} style={styles.fullBtn}>
              完工入库
            </NeoButton>
          )}
          <NeoButton variant="outline" size="large" onPress={returnOrRefreshAssignedTask} style={styles.fullBtn}>
            {autoAssigned ? '刷新当前工序' : '返回选批次'}
          </NeoButton>
        </ScrollView>
      </ScreenWrapper>
    );
  }

  // ========================= 共享头 (进度 + 卡片头) =========================
  const phaseBadge =
    currentPhase === 'AWAITING_INPUT' ? '① 投入阶段'
      : currentPhase === 'IN_PRODUCTION'
        ? productionStepMode === 'SEGMENT' ? '② 过程报工' : '③ 完工出成'
        : '③ 已完成';
  const alertText =
    lastAlert === 'ABOVE_MAX' ? '⚠ 出成率偏高, 请核对'
      : lastAlert === 'BELOW_MIN' ? '⚠ 出成率偏低, 请核对'
        : null;

  // 投入摘要 (生产/完成阶段只读显示)
  const reportedInput = currentStepYield?.totalInput ?? null;
  const reportedInputUnit = currentStepYield?.inputUnit ?? unit;
  const reportedSegments = currentStepYield?.laborSegments ?? [];
  const inputPhotosShown = currentStepYield?.inputPhotos ?? currentStepYield?.photos ?? [];

  const renderHeader = () => (
    <>
      <View style={styles.progressWrap}>
        <View style={styles.progressTopRow}>
          <Text style={styles.progressText}>
            {autoAssigned ? '当前分配工序' : `报工 ${currentStepIndex + 1} / ${totalSteps}`}
          </Text>
          <View style={styles.phaseBadge} testID="yield-phase-badge">
            <Text style={styles.phaseBadgeText}>{phaseBadge}</Text>
          </View>
        </View>
        <View style={styles.dotsRow}>
          {tasks.map((t, i) => {
            const ph = stepPhaseOf(t);
            return (
              <View
                key={t.id}
                style={[
                  styles.dot,
                  ph === 'COMPLETED' ? styles.dotDone
                    : i === currentStepIndex ? styles.dotActive
                      : styles.dotInactive,
                ]}
              />
            );
          })}
        </View>
      </View>

      <NeoCard variant="elevated" style={styles.headerCard}>
        <Text style={styles.product}>{productType || '—'}</Text>
        <Text style={styles.batchProcess}>
          {batchNumber}  ·  {currentTask?.processName ?? `第 ${currentTask?.processOrder} 道`}
        </Text>
        {planned != null ? (
          <Text style={styles.planned}>计划数量  {planned} {unit}</Text>
        ) : null}
        {currentTask?.standardYieldMin != null || currentTask?.standardYieldMax != null ? (
          <Text style={styles.stdRange}>
            标准出成率{' '}
            {currentTask?.standardYieldMin != null ? `${(currentTask.standardYieldMin * 100).toFixed(0)}%` : '—'}
            {' ~ '}
            {currentTask?.standardYieldMax != null ? `${(currentTask.standardYieldMax * 100).toFixed(0)}%` : '—'}
          </Text>
        ) : null}
      </NeoCard>
    </>
  );

  // 照片证据块 (三阶段共用; title 区分投入照 / 时段照 / 产出照)
  const renderEvidenceBlock = (title: string, takeTestID: string, pickTestID: string) => (
    <View style={styles.section} testID="yield-evidence-section">
      <Text style={styles.sectionTitle}>{title}</Text>
      {evidencePhotos.length > 0 ? (
        <View style={styles.thumbRow}>
          {evidencePhotos.map((p) => (
            <View style={styles.thumbItem} key={p.uri}>
              {p.mediaKind === 'video' ? (
                <View style={[styles.thumb, styles.videoThumb]}>
                  <Text style={styles.videoThumbIcon}>▶</Text>
                  <Text style={styles.videoThumbText}>视频</Text>
                </View>
              ) : (
                <Image source={{ uri: p.uri }} style={styles.thumb} />
              )}
              {p.uploading ? (
                <View style={styles.thumbOverlay}><ActivityIndicator color="#fff" /></View>
              ) : (
                <TouchableOpacity
                  style={styles.thumbRemove}
                  onPress={() => removeEvidencePhoto(p.uri)}
                  disabled={submitting}
                  accessibilityLabel="删除证据"
                >
                  <Text style={styles.thumbRemoveText}>✕</Text>
                </TouchableOpacity>
              )}
            </View>
          ))}
        </View>
      ) : null}
      <View style={styles.photoBtnRow}>
        <TouchableOpacity style={styles.photoBtn} onPress={takeEvidencePhoto} disabled={submitting} testID={takeTestID}>
          <Text style={styles.photoBtnText}>拍照/录像留证</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.photoBtnOutline} onPress={pickEvidencePhoto} disabled={submitting} testID={pickTestID}>
          <Text style={styles.photoBtnOutlineText}>从相册选</Text>
        </TouchableOpacity>
      </View>
    </View>
  );

  // 投入摘要块 (生产/完成阶段只读)
  const renderInputSummary = () => (
    <NeoCard variant="elevated" style={styles.summaryCard}>
      <Text style={styles.summaryTitle} testID="yield-input-summary">投入摘要</Text>
      <Text style={styles.summaryLine}>
        已投入  {reportedInput != null ? `${reportedInput} ${reportedInputUnit}` : '—'}
      </Text>
      {Array.isArray(inputPhotosShown) && inputPhotosShown.length > 0 ? (
        <View style={styles.thumbRow}>
          {inputPhotosShown.map((url: string, i: number) => (
            isEvidenceVideoUrl(url) ? (
              <View key={`in-${i}`} style={[styles.thumbReadonly, styles.videoThumb]}>
                <Text style={styles.videoThumbIcon}>▶</Text>
                <Text style={styles.videoThumbText}>视频</Text>
              </View>
            ) : (
              <Image key={`in-${i}`} source={{ uri: url }} style={styles.thumbReadonly} />
            )
          ))}
        </View>
      ) : null}
    </NeoCard>
  );

  return (
    <ScreenWrapper>
      <AppDialogHost />
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        {renderHeader()}

        {/* ===================== 阶段 1: 投入阶段 ===================== */}
        {currentPhase === 'AWAITING_INPUT' ? (
          <>
            <NeoCard variant="elevated" style={styles.card}>
              <Text style={styles.phaseHint}>第一步: 录入本道投入量, 领料/选半成品, 拍投料照</Text>

              {/* A2b: 首道领料批次选择 (仅首道) — B1: required prop signals red asterisk
               * Q1 单一数据源: singleBatchQty 把屏幕的 inputQty 传进 picker, 单批次模式下
               * picker 不显示独立用量输入框, 该批次 quantity = inputQty (投入量 IS 用量).
               * 多批次模式下 picker 显示各批独立输入, inputQty 由 Σ 自动算只读展示. */}
              {isFirstStep ? (
                <MaterialBatchPicker
                  unit={unit}
                  value={materialBatchRefs}
                  onChange={setMaterialBatchRefs}
                  singleBatchQty={inputQty}
                  disabled={submitting}
                  required
                />
              ) : null}

              {/* 单元D: 上道多笔 WIP → 显式单选; 单笔 → banner 显余额 */}
              {needsWipPicker ? (
                <WipBatchPicker
                  batchId={batchId}
                  selectedSourceWipNo={selectedWip?.sourceWipNo ?? null}
                  onChange={setSelectedWip}
                  disabled={submitting}
                />
              ) : !isFirstStep && wipAvailable != null ? (
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

              {/* Q1 单一数据源: 多批次时投入量 = Σ(各批次用量), 只读自动算; 单批次时正常可编辑
               * B4: defaultTrayWeighing pre-expands the tray calc for first-step (投入) */}
              {isFirstStep && materialBatchRefs.length > 1 ? (
                // 多批次选中: 投入量 = Σ 各批次用量, 显示只读汇总
                <View style={styles.multiQtyReadonly} testID="yield-input-qty-multi-readonly">
                  <Text style={styles.multiQtyLabel}>投入量 (多批次合计)</Text>
                  <Text style={styles.multiQtyValue}>
                    {materialBatchRefs.reduce((s, r) => s + r.quantity, 0).toFixed(2)} {unit}
                  </Text>
                  <Text style={styles.multiQtyHint}>投入量 = 各批次用量之和 (在上方各批次分别填写)</Text>
                </View>
              ) : (
                <YieldQuantityInput
                  label="投入量"
                  value={inputQty}
                  onChangeText={setInputQty}
                  unit={unit}
                  max={inputMax}
                  maxHint={inputMaxHint}
                  prefillNote={prefillNote}
                  disabled={submitting}
                  calculatorMode
                  defaultTrayWeighing={isFirstStep}
                  testID="yield-input-qty"
                />
              )}

              {/* A4 + P0-3: 投入超收预检提示 */}
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

              <View style={styles.divider} />
              {renderEvidenceBlock('投入证据 (照片/视频)', 'evidence-take-photo', 'evidence-pick-photo')}
            </NeoCard>

            <NeoButton
              variant="primary"
              size="large"
              onPress={handleSubmitInput}
              disabled={submitting || submitBlockedNoWip || evidenceUploading}
              loading={submitting}
              style={styles.fullBtn}
              testID="yield-submit-input-btn"
            >
              提交投入  ▶
            </NeoButton>
          </>
        ) : null}

        {/* ===================== 阶段 2: 生产阶段 ===================== */}
        {currentPhase === 'IN_PRODUCTION' ? (
          <>
            {renderInputSummary()}

            {productionStepMode === 'SEGMENT' ? (
              <>
            {/* 时段报工块 (一次提交一段, 累加) */}
            <NeoCard variant="elevated" style={styles.card}>
              <Text style={styles.sectionTitle} testID="yield-segment-block">时段报工 (几点到几点, 几个人)</Text>

              {/* 已报时段列表 */}
              {Array.isArray(reportedSegments) && reportedSegments.length > 0 ? (
                <View style={styles.reportedSegWrap} testID="yield-reported-segments">
                  <Text style={styles.reportedSegTitle}>已报 {reportedSegments.length} 段:</Text>
                  {reportedSegments.map((seg, i) => (
                    <Text key={`rseg-${i}`} style={styles.reportedSegLine}>
                      · {String(seg.startTime ?? '?')}~{String(seg.endTime ?? '?')}  {String(seg.headcount ?? '?')}人
                      {seg.processedQuantity ? `  处理 ${String(seg.processedQuantity)}${String(seg.processedUnit ?? unit)}` : ''}
                      {seg.stageOutputQuantity ? `  阶段产出 ${String(seg.stageOutputQuantity)}${String(seg.stageOutputUnit ?? unit)}` : ''}
                      {seg.segmentWasteQuantity ? `  损耗 ${String(seg.segmentWasteQuantity)}${String(seg.segmentWasteUnit ?? unit)}` : ''}
                      {seg.note ? `  (${String(seg.note)})` : ''}
                    </Text>
                  ))}
                </View>
              ) : (
                <Text style={styles.emptySegHint}>还没报工时段, 填下面一段提交</Text>
              )}

              {/* 本段录入 */}
              <View style={styles.segRow}>
                <TextInput
                  style={styles.segTimeInput}
                  value={segStart}
                  onChangeText={setSegStart}
                  placeholder="开始"
                  placeholderTextColor="#C0C4CC"
                  editable={!submitting}
                  testID="seg-start"
                />
                <Text style={styles.segSep}>~</Text>
                <TextInput
                  style={styles.segTimeInput}
                  value={segEnd}
                  onChangeText={setSegEnd}
                  placeholder="结束"
                  placeholderTextColor="#C0C4CC"
                  editable={!submitting}
                  testID="seg-end"
                />
                <TextInput
                  style={styles.segNumInput}
                  keyboardType="number-pad"
                  value={segHeadcount}
                  onChangeText={(v) => setSegHeadcount(v.replace(/[^0-9]/g, ''))}
                  placeholder="人数"
                  placeholderTextColor="#C0C4CC"
                  editable={!submitting}
                  testID="seg-headcount"
                />
              </View>
              <TextInput
                style={styles.segNoteInput}
                value={segNote}
                onChangeText={setSegNote}
                placeholder="备注 (选填)"
                placeholderTextColor="#C0C4CC"
                editable={!submitting}
                testID="seg-note"
              />

              <YieldQuantityInput
                label="本段处理量 (选填)"
                value={segProcessedQty}
                onChangeText={setSegProcessedQty}
                unit={unit}
                disabled={submitting}
                testID="seg-processed-qty"
              />
              <YieldQuantityInput
                label="阶段产出 (选填, 不是完工入库)"
                value={segStageOutputQty}
                onChangeText={setSegStageOutputQty}
                unit={unit}
                disabled={submitting}
                testID="seg-stage-output-qty"
              />
              <YieldQuantityInput
                label="过程损耗 (选填)"
                value={segWasteQty}
                onChangeText={setSegWasteQty}
                unit={unit}
                disabled={submitting}
                testID="seg-waste-qty"
              />

              <View style={styles.section} testID="seg-byproducts-section">
                <Text style={styles.sectionTitle}>本段副产物 (选填)</Text>
                {segByproducts.map((bp, idx) => (
                  <View style={styles.segRow} key={`seg-bp-${idx}`}>
                    <TextInput
                      style={styles.bpNameInput}
                      value={bp.name}
                      onChangeText={(v) => updateSegByproduct(idx, 'name', v)}
                      placeholder="名称"
                      placeholderTextColor="#C0C4CC"
                      editable={!submitting}
                      testID={`seg-bp-name-${idx}`}
                    />
                    <TextInput
                      style={styles.segNumInput}
                      keyboardType="decimal-pad"
                      value={bp.quantity}
                      onChangeText={(v) => updateSegByproduct(idx, 'quantity', v.replace(/[^0-9.]/g, ''))}
                      placeholder="数量"
                      placeholderTextColor="#C0C4CC"
                      editable={!submitting}
                      testID={`seg-bp-qty-${idx}`}
                    />
                    <TextInput
                      style={styles.bpUnitInput}
                      value={bp.unit}
                      onChangeText={(v) => updateSegByproduct(idx, 'unit', v)}
                      placeholder={unit}
                      placeholderTextColor="#C0C4CC"
                      editable={!submitting}
                      testID={`seg-bp-unit-${idx}`}
                    />
                    <TouchableOpacity
                      style={styles.rowRemoveBtn}
                      onPress={() => removeSegByproduct(idx)}
                      disabled={submitting}
                      accessibilityLabel="删除这行本段副产物"
                    >
                      <Text style={styles.rowRemoveText}>×</Text>
                    </TouchableOpacity>
                  </View>
                ))}
                <TouchableOpacity
                  style={styles.addRowBtn}
                  onPress={addSegByproduct}
                  disabled={submitting}
                  testID="add-seg-byproduct"
                >
                  <Text style={styles.addRowText}>＋ 加一行</Text>
                </TouchableOpacity>
              </View>

              <View style={styles.divider} />
              {renderEvidenceBlock('本段证据 (照片/视频, 选填)', 'seg-take-photo', 'seg-pick-photo')}

              <NeoButton
                variant="outline"
                size="large"
                onPress={handleSubmitSegment}
                disabled={submitting || evidenceUploading}
                loading={submitting}
                style={styles.blockBtn}
                testID="yield-submit-segment-btn"
              >
                ＋ 提交本段
              </NeoButton>
            </NeoCard>

            <NeoButton
              variant="primary"
              size="large"
              onPress={() => switchProductionStepMode('OUTPUT')}
              disabled={submitting || evidenceUploading}
              style={styles.fullBtn}
              testID="yield-go-output-step-btn"
            >
              本工序已做完, 去填完工出成
            </NeoButton>
              </>
            ) : null}

            {productionStepMode === 'OUTPUT' ? (
              <>
            {/* 完工出成块 */}
            <NeoCard variant="elevated" style={styles.card}>
              <Text style={styles.sectionTitle} testID="yield-output-block">完工出成 (本道做完才填)</Text>

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
                calculatorMode
                testID="yield-output-qty"
              />

              {outputOverHardCap ? (
                <View style={styles.hardcapBanner} testID="yield-hardcap-banner">
                  <Text style={styles.hardcapText}>
                    产出量超过物理上限 {Math.round(outputHardCap ?? 0)} {outUnit}, 请核对 (疑似单位/数量错误)
                  </Text>
                </View>
              ) : null}

              <View style={styles.divider} />
              {renderEvidenceBlock('产出证据 (照片/视频)', 'out-take-photo', 'out-pick-photo')}

              <View style={styles.divider} />

              {/* 副产物 */}
              <View style={styles.section} testID="yield-byproducts-section">
                <Text style={styles.sectionTitle}>副产物 (料头/肥油/骨头)</Text>
                {byproducts.map((bp, idx) => (
                  <View style={styles.segRow} key={`bp-${idx}`}>
                    <TextInput
                      style={styles.bpNameInput}
                      value={bp.name}
                      onChangeText={(v) => updateByproduct(idx, 'name', v)}
                      placeholder="名称"
                      placeholderTextColor="#C0C4CC"
                      editable={!submitting}
                      testID={`bp-name-${idx}`}
                    />
                    <TextInput
                      style={styles.segNumInput}
                      keyboardType="decimal-pad"
                      value={bp.quantity}
                      onChangeText={(v) => updateByproduct(idx, 'quantity', v.replace(/[^0-9.]/g, ''))}
                      placeholder="数量"
                      placeholderTextColor="#C0C4CC"
                      editable={!submitting}
                      testID={`bp-qty-${idx}`}
                    />
                    <TextInput
                      style={styles.bpUnitInput}
                      value={bp.unit}
                      onChangeText={(v) => updateByproduct(idx, 'unit', v)}
                      placeholder={unit}
                      placeholderTextColor="#C0C4CC"
                      editable={!submitting}
                      testID={`bp-unit-${idx}`}
                    />
                    <TouchableOpacity
                      style={styles.rowRemoveBtn}
                      onPress={() => removeByproduct(idx)}
                      disabled={submitting}
                      accessibilityLabel="删除这行副产物"
                    >
                      <Text style={styles.rowRemoveText}>✕</Text>
                    </TouchableOpacity>
                  </View>
                ))}
                <TouchableOpacity
                  style={styles.addRowBtn}
                  onPress={addByproduct}
                  disabled={submitting}
                  testID="add-byproduct"
                >
                  <Text style={styles.addRowText}>＋ 加一行</Text>
                </TouchableOpacity>
              </View>

              {/* 损耗 */}
              <YieldQuantityInput
                label="损耗量 (选填)"
                value={wasteQty}
                onChangeText={setWasteQty}
                unit={unit}
                disabled={submitting}
                testID="yield-waste-qty"
              />

              {/* 留样 */}
              <YieldQuantityInput
                label={isLastStep ? '留样 (末道装盒, 盒/份)' : '留样 (选填, 盒/份)'}
                value={sampleRetainQty}
                onChangeText={setSampleRetainQty}
                unit="份"
                disabled={submitting}
                testID="yield-sample-retain"
              />

              {alertText ? (
                <View style={styles.alertBanner} testID="yield-alert-banner">
                  <Text style={styles.alertText}>{alertText}</Text>
                </View>
              ) : null}

              <NeoButton
                variant="primary"
                size="large"
                onPress={handleSubmitOutput}
                disabled={submitting || outputOverHardCap || evidenceUploading}
                loading={submitting}
                style={styles.blockBtn}
                testID="yield-submit-output-btn"
              >
                完工出成  ✓
              </NeoButton>
            </NeoCard>
            <NeoButton
              variant="outline"
              size="large"
              onPress={() => switchProductionStepMode('SEGMENT')}
              disabled={submitting || evidenceUploading}
              style={styles.fullBtn}
              testID="yield-back-segment-step-btn"
            >
              返回过程报工
            </NeoButton>
              </>
            ) : null}
          </>
        ) : null}

        {/* ===================== 阶段 3: 完成阶段 ===================== */}
        {currentPhase === 'COMPLETED' ? (
          <>
            <NeoCard variant="elevated" style={styles.card}>
              <View style={styles.completedHeader} testID="yield-completed-summary">
                <Text style={styles.completedTitle}>✓ 本道已完成</Text>
              </View>

              <View style={styles.summaryRow}>
                <Text style={styles.summaryKey}>投入</Text>
                <Text style={styles.summaryVal}>
                  {currentStepYield?.totalInput != null ? `${currentStepYield.totalInput} ${currentStepYield.inputUnit ?? unit}` : '—'}
                </Text>
              </View>
              <View style={styles.summaryRow}>
                <Text style={styles.summaryKey}>产出</Text>
                <Text style={styles.summaryVal}>
                  {currentStepYield?.totalOutput != null ? `${currentStepYield.totalOutput} ${currentStepYield.outputUnit ?? outUnit}` : '—'}
                </Text>
              </View>
              <View style={styles.summaryRow}>
                <Text style={styles.summaryKey}>本道出成率</Text>
                <Text style={styles.summaryValHi} testID="yield-step-rate">
                  {currentStepYield?.yieldRate != null
                    ? `${(currentStepYield.yieldRate * 100).toFixed(2)}%`
                    : currentStepYield?.unitComparable === false ? '跨单位不可比' : '—'}
                </Text>
              </View>
              <View style={styles.summaryRow}>
                <Text style={styles.summaryKey}>本道成本</Text>
                <Text style={styles.summaryVal} testID="yield-step-cost">
                  {currentStepYield != null
                    && currentStepYield.laborCost == null
                    && currentStepYield.materialCost == null
                    && currentStepYield.stepCost == null
                    ? '未配工价 / 无原料单价'
                    : `人工${fmtMoney(currentStepYield?.laborCost)} + 材料${fmtMoney(currentStepYield?.materialCost)} = ${fmtMoney(currentStepYield?.stepCost)}`}
                </Text>
              </View>

              {/* 全工时段 */}
              {Array.isArray(reportedSegments) && reportedSegments.length > 0 ? (
                <View style={styles.reportedSegWrap}>
                  <Text style={styles.reportedSegTitle}>工时段 ({reportedSegments.length} 段):</Text>
                  {reportedSegments.map((seg, i) => (
                    <Text key={`cseg-${i}`} style={styles.reportedSegLine}>
                      · {String(seg.startTime ?? '?')}~{String(seg.endTime ?? '?')}  {String(seg.headcount ?? '?')}人
                      {seg.note ? `  (${String(seg.note)})` : ''}
                    </Text>
                  ))}
                </View>
              ) : null}

              {/* 投入照片 */}
              {Array.isArray(currentStepYield?.inputPhotos) && (currentStepYield?.inputPhotos?.length ?? 0) > 0 ? (
                <View style={styles.photoGroup}>
                  <Text style={styles.photoGroupTitle}>投入照片</Text>
                  <View style={styles.thumbRow}>
                    {currentStepYield!.inputPhotos!.map((url: string, i: number) => (
                      <Image key={`cin-${i}`} source={{ uri: url }} style={styles.thumbReadonly} />
                    ))}
                  </View>
                </View>
              ) : null}

              {/* 产出照片 */}
              {Array.isArray(currentStepYield?.outputPhotos) && (currentStepYield?.outputPhotos?.length ?? 0) > 0 ? (
                <View style={styles.photoGroup}>
                  <Text style={styles.photoGroupTitle}>产出照片</Text>
                  <View style={styles.thumbRow}>
                    {currentStepYield!.outputPhotos!.map((url: string, i: number) => (
                      <Image key={`cout-${i}`} source={{ uri: url }} style={styles.thumbReadonly} />
                    ))}
                  </View>
                </View>
              ) : null}
            </NeoCard>

            <NeoButton
              variant="primary"
              size="large"
              onPress={autoAssigned ? returnOrRefreshAssignedTask : goNextStep}
              disabled={submitting}
              style={styles.fullBtn}
              testID="yield-next-step-btn"
            >
              {autoAssigned ? '刷新当前工序' : isLastStep ? '查看整批汇总  ▶' : '下一道  ▶'}
            </NeoButton>
          </>
        ) : null}
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
  // 进度
  progressWrap: { marginBottom: 16 },
  progressTopRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 },
  progressText: { fontSize: 16, fontWeight: '600', color: '#303133' },
  phaseBadge: { backgroundColor: '#FFF3E8', borderRadius: 14, paddingHorizontal: 12, paddingVertical: 4 },
  phaseBadgeText: { fontSize: 14, color: '#E8732E', fontWeight: '700' },
  dotsRow: { flexDirection: 'row', flexWrap: 'wrap' },
  dot: { width: 12, height: 12, borderRadius: 6, marginRight: 8, marginBottom: 6 },
  dotActive: { backgroundColor: '#E8732E' },
  dotDone: { backgroundColor: '#67C23A' },
  dotInactive: { backgroundColor: '#DCDFE6' },
  // 卡片
  headerCard: { marginBottom: 12 },
  card: { marginBottom: 16 },
  summaryCard: { marginBottom: 12, backgroundColor: '#F8FBF5' },
  product: { fontSize: 22, fontWeight: '700', color: '#1A1A1A' },
  batchProcess: { fontSize: 15, color: '#606266', marginTop: 6 },
  planned: { fontSize: 14, color: '#909399', marginTop: 6 },
  stdRange: { fontSize: 13, color: '#409EFF', marginTop: 4 },
  phaseHint: { fontSize: 14, color: '#E8732E', fontWeight: '600', marginBottom: 12 },
  divider: { height: 1, backgroundColor: '#EBEEF5', marginVertical: 16 },
  // 投入摘要
  summaryTitle: { fontSize: 15, fontWeight: '700', color: '#67C23A', marginBottom: 8 },
  summaryLine: { fontSize: 16, color: '#303133', fontWeight: '600' },
  // 告警 / 提示条
  alertBanner: { backgroundColor: '#FDF6EC', borderRadius: 8, padding: 12, marginTop: 4 },
  alertText: { fontSize: 14, color: '#E6A23C', fontWeight: '500' },
  limitsHint: { backgroundColor: '#F0F9EB', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 6, marginTop: 4 },
  limitsHintText: { fontSize: 13, color: '#67C23A' },
  limitsHintMuted: { backgroundColor: '#F4F4F5', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 6, marginTop: 4 },
  limitsHintMutedText: { fontSize: 13, color: '#909399' },
  hardcapBanner: { backgroundColor: '#FEF0F0', borderRadius: 8, padding: 12, marginTop: 4 },
  hardcapText: { fontSize: 14, color: '#F56C6C', fontWeight: '600' },
  wipBanner: { backgroundColor: '#ECF5FF', borderRadius: 8, padding: 12, marginBottom: 8 },
  wipBannerText: { fontSize: 14, color: '#409EFF', fontWeight: '600' },
  wipBannerSub: { fontSize: 12, color: '#909399', marginTop: 4 },
  // 按钮
  fullBtn: { width: '100%', marginBottom: 12 },
  blockBtn: { width: '100%', marginTop: 8 },
  // done 卡
  doneCard: { marginBottom: 20, alignItems: 'center' },
  doneTitle: { fontSize: 20, fontWeight: '700', color: '#67C23A', marginBottom: 12 },
  doneProduct: { fontSize: 18, fontWeight: '600', color: '#1A1A1A' },
  doneBatch: { fontSize: 14, color: '#909399', marginTop: 4, marginBottom: 16 },
  crossUnitBanner: { backgroundColor: '#FDF6EC', borderRadius: 8, padding: 12, marginTop: 8 },
  crossUnitText: { fontSize: 14, color: '#E6A23C', fontWeight: '500', textAlign: 'center' },
  inProgressBanner: { backgroundColor: '#FDF6EC', borderRadius: 8, padding: 12, marginTop: 12 },
  inProgressText: { fontSize: 13, color: '#E6A23C', fontWeight: '500', textAlign: 'center' },
  lockedNote: { fontSize: 13, color: '#67C23A', marginTop: 10, textAlign: 'center' },
  doneRow: { flexDirection: 'row', alignItems: 'baseline', marginTop: 8 },
  doneLabel: { fontSize: 15, color: '#606266', marginRight: 12 },
  doneValue: { fontSize: 28, fontWeight: '700', color: '#E8732E' },
  doneFlow: { fontSize: 15, color: '#606266', marginTop: 12 },
  doneCostWrap: { marginTop: 12 },
  doneCost: { fontSize: 14, color: '#303133', fontWeight: '600', textAlign: 'center' },
  doneCostMuted: { fontSize: 13, color: '#909399', textAlign: 'center' },
  // 完成阶段 summary
  completedHeader: { marginBottom: 12 },
  completedTitle: { fontSize: 18, fontWeight: '700', color: '#67C23A' },
  summaryRow: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 10 },
  summaryKey: { fontSize: 15, color: '#606266' },
  summaryVal: { fontSize: 15, color: '#303133', fontWeight: '600', flexShrink: 1, textAlign: 'right', marginLeft: 12 },
  summaryValHi: { fontSize: 18, color: '#E8732E', fontWeight: '700' },
  photoGroup: { marginTop: 8 },
  photoGroupTitle: { fontSize: 14, fontWeight: '600', color: '#303133', marginBottom: 8 },
  // 时段
  section: { marginBottom: 16 },
  sectionTitle: { fontSize: 16, fontWeight: '600', color: '#303133', marginBottom: 10 },
  reportedSegWrap: { backgroundColor: '#F0F9EB', borderRadius: 8, padding: 10, marginBottom: 12 },
  reportedSegTitle: { fontSize: 13, color: '#67C23A', fontWeight: '700', marginBottom: 4 },
  reportedSegLine: { fontSize: 13, color: '#606266', marginTop: 2 },
  emptySegHint: { fontSize: 13, color: '#909399', marginBottom: 12 },
  segRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 8, minWidth: 0 },
  segTimeInput: {
    flex: 1, minWidth: 0, height: 48, borderWidth: 1, borderColor: '#DCDFE6', borderRadius: 8,
    backgroundColor: '#FFFFFF', textAlign: 'center', fontSize: 16, color: '#1A1A1A',
    paddingHorizontal: 6,
  },
  segSep: { fontSize: 18, color: '#909399', marginHorizontal: 6 },
  segNumInput: {
    width: 72, height: 48, borderWidth: 1, borderColor: '#DCDFE6', borderRadius: 8,
    backgroundColor: '#FFFFFF', textAlign: 'center', fontSize: 16, color: '#1A1A1A',
    marginLeft: 8, paddingHorizontal: 4,
  },
  segNoteInput: {
    height: 44, borderWidth: 1, borderColor: '#DCDFE6', borderRadius: 8,
    backgroundColor: '#FFFFFF', fontSize: 15, color: '#1A1A1A', paddingHorizontal: 10, marginBottom: 4,
  },
  bpNameInput: {
    flex: 1, height: 48, borderWidth: 1, borderColor: '#DCDFE6', borderRadius: 8,
    backgroundColor: '#FFFFFF', fontSize: 16, color: '#1A1A1A', paddingHorizontal: 10,
  },
  bpUnitInput: {
    width: 56, height: 48, borderWidth: 1, borderColor: '#DCDFE6', borderRadius: 8,
    backgroundColor: '#FFFFFF', textAlign: 'center', fontSize: 15, color: '#1A1A1A',
    marginLeft: 8, paddingHorizontal: 4,
  },
  rowRemoveBtn: {
    width: 40, height: 40, borderRadius: 20, backgroundColor: '#FEF0F0',
    alignItems: 'center', justifyContent: 'center', marginLeft: 8,
  },
  rowRemoveText: { fontSize: 16, color: '#F56C6C', fontWeight: '700' },
  addRowBtn: {
    height: 48, borderRadius: 8, borderWidth: 1, borderColor: '#409EFF', borderStyle: 'dashed',
    alignItems: 'center', justifyContent: 'center', marginTop: 2,
  },
  addRowText: { fontSize: 16, color: '#409EFF', fontWeight: '600' },
  // 图片证据
  thumbRow: { flexDirection: 'row', flexWrap: 'wrap', marginBottom: 10 },
  thumbItem: { width: 80, height: 80, borderRadius: 8, overflow: 'hidden', marginRight: 8, marginBottom: 8 },
  thumb: { width: 80, height: 80 },
  thumbReadonly: { width: 80, height: 80, borderRadius: 8, marginRight: 8, marginBottom: 8 },
  videoThumb: {
    backgroundColor: '#1F2937',
    alignItems: 'center',
    justifyContent: 'center',
  },
  videoThumbIcon: { fontSize: 18, color: '#FFFFFF', fontWeight: '800', lineHeight: 22 },
  videoThumbText: { marginTop: 2, fontSize: 12, color: '#FFFFFF', fontWeight: '700' },
  thumbOverlay: {
    ...StyleSheet.absoluteFillObject, backgroundColor: 'rgba(0,0,0,0.4)',
    alignItems: 'center', justifyContent: 'center',
  },
  thumbRemove: {
    position: 'absolute', top: 2, right: 2, width: 22, height: 22, borderRadius: 11,
    backgroundColor: 'rgba(0,0,0,0.55)', alignItems: 'center', justifyContent: 'center',
  },
  thumbRemoveText: { fontSize: 13, color: '#FFFFFF', fontWeight: '700' },
  photoBtnRow: { flexDirection: 'row', alignItems: 'center' },
  photoBtn: {
    flex: 1, height: 52, borderRadius: 8, backgroundColor: '#E8732E',
    alignItems: 'center', justifyContent: 'center', marginRight: 10,
  },
  photoBtnText: { fontSize: 16, color: '#FFFFFF', fontWeight: '700' },
  photoBtnOutline: {
    height: 52, paddingHorizontal: 18, borderRadius: 8, borderWidth: 1, borderColor: '#E8732E',
    alignItems: 'center', justifyContent: 'center',
  },
  photoBtnOutlineText: { fontSize: 15, color: '#E8732E', fontWeight: '600' },
  // Q1 多批次只读投入量
  multiQtyReadonly: {
    backgroundColor: '#F0F9EB', borderRadius: 8, padding: 12, marginBottom: 16,
  },
  multiQtyLabel: { fontSize: 14, color: '#67C23A', fontWeight: '600', marginBottom: 4 },
  multiQtyValue: { fontSize: 26, fontWeight: '700', color: '#303133' },
  multiQtyHint: { fontSize: 12, color: '#909399', marginTop: 4 },
});

export default YieldStepReportScreen;
