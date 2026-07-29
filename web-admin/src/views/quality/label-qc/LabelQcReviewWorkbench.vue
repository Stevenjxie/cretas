<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Aim,
  Check,
  Close,
  Delete,
  Minus,
  Plus,
  RefreshLeft,
  Right,
  Warning,
} from '@element-plus/icons-vue';
import type {
  LabelQcBoundingBox,
  LabelQcLabel,
  LabelQcPhoto,
  LabelQcReviewRequest,
  LabelQcTaskDetail,
} from '@/api/labelQc';
import {
  appendHumanBox,
  buildReviewDraft,
  completedPhotoCount,
  firstIncompletePhotoIndex,
  isPhotoComplete,
  markPhotoNormal,
  markPhotoReviewed,
  moveBox,
  normalizedBox,
  pendingItemCount,
  pointBox,
  resizeBox,
  restoreRejectedAiCandidate,
  strokeBounds,
  toReviewRequest,
  validateReviewDraft,
  type LabelQcPhotoDraft,
  type LabelQcReviewDraft,
} from './reviewModel';

const props = defineProps<{
  detail: LabelQcTaskDetail;
  canReview: boolean;
  submitting: boolean;
  retrying: boolean;
}>();

const emit = defineEmits<{
  submit: [payload: LabelQcReviewRequest];
  retry: [];
  dirtyChange: [dirty: boolean];
}>();

const LABEL_TEXT: Record<LabelQcLabel, string> = {
  MISSING_WHITE_LABEL: '缺白标',
  MISSING_COLOR_LABEL: '缺彩标',
  NO_DEFECT: '无问题',
  UNJUDGEABLE: '无法判断',
};

const drafts = ref<LabelQcPhotoDraft[]>([]);
const activePhotoIndex = ref(0);
const selectedKey = ref<string | null>(null);
const viewportRef = ref<HTMLElement | null>(null);
const planeRef = ref<HTMLElement | null>(null);
const viewportSize = ref({ width: 800, height: 600 });
const zoom = ref(1);
const pan = ref({ x: 0, y: 0 });
const isDirty = ref(false);
let resizeObserver: ResizeObserver | null = null;

type PointerInteraction = {
  type: 'pan' | 'move' | 'resize' | 'draw';
  pointerId: number;
  startX: number;
  startY: number;
  moved: boolean;
  startPan?: { x: number; y: number };
  itemKey?: string;
  startBox?: LabelQcBoundingBox;
};

const pointerInteraction = ref<PointerInteraction | null>(null);

const activePhoto = computed<LabelQcPhoto>(
  () => props.detail.photos[activePhotoIndex.value]!,
);
const activeDraft = computed<LabelQcPhotoDraft>(
  () => drafts.value[activePhotoIndex.value]!,
);
const selectedItem = computed<LabelQcReviewDraft | null>(
  () => activeDraft.value?.items.find((item) => item.key === selectedKey.value) ?? null,
);
const visibleItems = computed(() => (
  activeDraft.value?.items.filter((item) => (
    Boolean(item.bbox) && !(item.source === 'AI' && item.label === 'NO_DEFECT')
  )) ?? []
));
const pendingItems = computed(() => (
  activeDraft.value?.items.filter((item) => !item.label) ?? []
));
const rejectedAiItems = computed(() => (
  activeDraft.value?.items.filter((item) => (
    item.source === 'AI' && item.label === 'NO_DEFECT'
  )) ?? []
));
const currentHumanItemCount = computed(() => (
  activeDraft.value?.items.filter((item) => item.source === 'HUMAN').length ?? 0
));
const normalAiImpactCount = computed(() => (
  activeDraft.value?.items.filter((item) => (
    item.source === 'AI' && item.label !== 'NO_DEFECT'
  )).length ?? 0
));
const completedCount = computed(() => completedPhotoCount(drafts.value));
const allComplete = computed(() => (
  drafts.value.length > 0 && completedCount.value === drafts.value.length
));
const currentPhotoComplete = computed(() => (
  activeDraft.value ? isPhotoComplete(activeDraft.value) : false
));
const reviewPercent = computed(() => (
  drafts.value.length
    ? Math.round((completedCount.value / drafts.value.length) * 100)
    : 0
));
const currentAiTotalCount = computed(() => (
  activeDraft.value?.items.filter((item) => item.source === 'AI').length ?? 0
));
const currentAiPendingCount = computed(() => (
  activeDraft.value?.items.filter((item) => item.source === 'AI' && !item.label).length ?? 0
));
const aiReviewStatusText = computed(() => {
  if (currentAiPendingCount.value > 0) {
    return `${currentAiPendingCount.value} 个 AI 疑点待复核`;
  }
  if (currentAiTotalCount.value > 0) {
    return `${currentAiTotalCount.value} 个 AI 疑点已处理`;
  }
  return 'AI 未发现疑点，仍需人工确认';
});
const nextButtonText = computed(() => {
  if (!currentPhotoComplete.value) return '请先完成本图';
  if (activePhotoIndex.value < drafts.value.length - 1) return '下一张';
  return '回到未完成照片';
});

const imagePlaneStyle = computed(() => {
  const photo = activePhoto.value;
  const viewport = viewportSize.value;
  const photoRatio = Math.max(0.1, photo.imageWidth / photo.imageHeight);
  const viewportRatio = viewport.width / viewport.height;
  let width = viewport.width;
  let height = width / photoRatio;
  if (photoRatio < viewportRatio) {
    height = viewport.height;
    width = height * photoRatio;
  }
  return {
    width: `${width}px`,
    height: `${height}px`,
    left: `${(viewport.width - width) / 2}px`,
    top: `${(viewport.height - height) / 2}px`,
    transform: `translate(${pan.value.x}px, ${pan.value.y}px) scale(${zoom.value})`,
  };
});

function labelText(label?: LabelQcLabel | null): string {
  return label ? LABEL_TEXT[label] : '待确认';
}

function labelColor(label: LabelQcLabel): string {
  if (label === 'MISSING_WHITE_LABEL') return '#e54d42';
  if (label === 'MISSING_COLOR_LABEL') return '#d97706';
  if (label === 'UNJUDGEABLE') return '#6b7280';
  return '#16a36a';
}

function itemColor(item: LabelQcReviewDraft): string {
  if (!item.label) return item.source === 'AI' ? '#f5a524' : '#2f6fdd';
  return labelColor(item.label);
}

/** 预览用的颜色跟着"粘"住的类型走，画之前就知道这一笔会被标成什么 */
const drawColor = computed(() => (activeLabel.value ? labelColor(activeLabel.value) : '#2f6fdd'));

const draftRectStyle = computed<Record<string, string>>(() => {
  const rect = draftRect.value;
  if (!rect) return {};
  return {
    left: `${Math.min(rect.x0, rect.x1)}px`,
    top: `${Math.min(rect.y0, rect.y1)}px`,
    width: `${Math.abs(rect.x1 - rect.x0)}px`,
    height: `${Math.abs(rect.y1 - rect.y0)}px`,
    borderColor: drawColor.value,
  };
});

function brushDotStyle(point: { x: number; y: number }): Record<string, string> {
  const size = brushRadius.value * 2;
  return {
    left: `${point.x - brushRadius.value}px`,
    top: `${point.y - brushRadius.value}px`,
    width: `${size}px`,
    height: `${size}px`,
    borderColor: drawColor.value,
    backgroundColor: drawColor.value,
  };
}

function confidence(value?: number | null): string {
  return value == null ? '—' : `${Math.round(value * 100)}%`;
}

function annotationStyle(item: LabelQcReviewDraft) {
  const box = item.bbox!;
  return {
    left: `${box.xMin * 100}%`,
    top: `${box.yMin * 100}%`,
    width: `${(box.xMax - box.xMin) * 100}%`,
    height: `${(box.yMax - box.yMin) * 100}%`,
    borderColor: itemColor(item),
  };
}

function choosePreferredItem(): void {
  const current = activeDraft.value;
  if (!current) {
    selectedKey.value = null;
    return;
  }
  const existing = current.items.find((item) => item.key === selectedKey.value);
  if (existing && !(existing.source === 'AI' && existing.label === 'NO_DEFECT')) return;
  selectedKey.value = current.items.find((item) => !item.label)?.key
    ?? current.items.find((item) => !(item.source === 'AI' && item.label === 'NO_DEFECT'))?.key
    ?? null;
}

function resetView(): void {
  zoom.value = 1;
  pan.value = { x: 0, y: 0 };
}

function selectPhoto(index: number): void {
  activePhotoIndex.value = index;
  selectedKey.value = null;
  resetView();
  void nextTick(choosePreferredItem);
}

function setDirty(value: boolean): void {
  if (isDirty.value === value) return;
  isDirty.value = value;
  emit('dirtyChange', value);
}

function touchPhoto(): void {
  if (activeDraft.value) activeDraft.value.reviewed = false;
  setDirty(true);
}

function selectItem(item: LabelQcReviewDraft): void {
  selectedKey.value = item.key;
}

function selectNextPending(afterKey?: string): void {
  const items = activeDraft.value?.items ?? [];
  const start = Math.max(-1, items.findIndex((item) => item.key === afterKey));
  for (let offset = 1; offset <= items.length; offset += 1) {
    const item = items[(start + offset) % items.length];
    if (item && !item.label) {
      selectedKey.value = item.key;
      return;
    }
  }
  choosePreferredItem();
}

function resolveSelected(label: LabelQcLabel): void {
  const item = selectedItem.value;
  if (!item || !props.canReview) return;
  item.label = label;
  touchPhoto();
  const key = item.key;
  void nextTick(() => selectNextPending(key));
}

function confirmAiCandidate(): void {
  const item = selectedItem.value;
  if (!item || item.source !== 'AI') return;
  resolveSelected(item.aiLabel ?? 'UNJUDGEABLE');
}

function rejectAiCandidate(): void {
  const item = selectedItem.value;
  if (!item || item.source !== 'AI') return;
  item.notes = item.notes || '人工复核：AI 疑点不成立';
  resolveSelected('NO_DEFECT');
}

function undoRejectedAiCandidate(item: LabelQcReviewDraft): void {
  const draft = activeDraft.value;
  if (!draft || !props.canReview) return;
  const restored = restoreRejectedAiCandidate(draft, item.key);
  if (!restored) return;
  selectedKey.value = restored.key;
  setDirty(true);
  ElMessage.info('已撤销拒绝，请重新确认这个 AI 疑点');
}

function setHumanLabel(label: Exclude<LabelQcLabel, 'NO_DEFECT'>): void {
  const item = selectedItem.value;
  if (!item || item.source !== 'HUMAN') return;
  resolveSelected(label);
}

function deleteHumanItem(): void {
  const item = selectedItem.value;
  const draft = activeDraft.value;
  if (!item || !draft || item.source !== 'HUMAN' || !props.canReview) return;
  draft.items = draft.items.filter((candidate) => candidate.key !== item.key);
  draft.reviewed = false;
  selectedKey.value = null;
  setDirty(true);
  void nextTick(choosePreferredItem);
}

function addHumanBoxAt(clientX: number, clientY: number): void {
  if (!props.canReview || !planeRef.value || !activeDraft.value) return;
  const rect = planeRef.value.getBoundingClientRect();
  const x = (clientX - rect.left) / rect.width;
  const y = (clientY - rect.top) / rect.height;
  if (x < 0 || x > 1 || y < 0 || y > 1) return;
  const item = appendHumanBox(
    activeDraft.value,
    pointBox(x, y),
    `human-${activeDraft.value.photoId}-${Date.now()}`,
  );
  selectedKey.value = item.key;
  setDirty(true);
  ElMessage.success('已补一个人工框，请在右侧选择问题类型');
}

/** 屏幕坐标 → 图片平面内的像素坐标（已含缩放/平移，因为用的是 plane 的实际 rect） */
function toPlanePoint(clientX: number, clientY: number): { x: number; y: number } | null {
  const rect = planeRef.value?.getBoundingClientRect();
  if (!rect || rect.width <= 0 || rect.height <= 0) return null;
  return { x: clientX - rect.left, y: clientY - rect.top };
}

function startViewportPointer(event: PointerEvent): void {
  // 中键始终是平移：画框/涂抹时左键被占用，总得留一条挪画面的路
  const wantsPan = event.button === 1 || toolMode.value === 'select';
  if (event.button !== 0 && event.button !== 1) return;
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);

  if (!wantsPan && props.canReview) {
    const point = toPlanePoint(event.clientX, event.clientY);
    if (!point) return;
    if (toolMode.value === 'box') {
      draftRect.value = { x0: point.x, y0: point.y, x1: point.x, y1: point.y };
    } else {
      brushStroke.value = [point];
    }
    pointerInteraction.value = {
      type: 'draw',
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      moved: false,
    };
    return;
  }

  pointerInteraction.value = {
    type: 'pan',
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    moved: false,
    startPan: { ...pan.value },
  };
}

function startBoxPointer(
  event: PointerEvent,
  item: LabelQcReviewDraft,
  type: 'move' | 'resize',
): void {
  event.stopPropagation();
  if (!props.canReview || !item.bbox) {
    selectItem(item);
    return;
  }
  selectItem(item);
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  pointerInteraction.value = {
    type,
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    moved: false,
    itemKey: item.key,
    startBox: { ...item.bbox },
  };
}

function movePointer(event: PointerEvent): void {
  if (toolMode.value === 'brush') {
    cursorPoint.value = toPlanePoint(event.clientX, event.clientY);
  }
  const interaction = pointerInteraction.value;
  if (!interaction || interaction.pointerId !== event.pointerId) return;
  const deltaX = event.clientX - interaction.startX;
  const deltaY = event.clientY - interaction.startY;
  if (Math.abs(deltaX) + Math.abs(deltaY) > 4) interaction.moved = true;
  if (interaction.type === 'draw') {
    const point = toPlanePoint(event.clientX, event.clientY);
    if (!point) return;
    if (draftRect.value) {
      draftRect.value = { ...draftRect.value, x1: point.x, y1: point.y };
    } else if (brushStroke.value.length) {
      brushStroke.value = [...brushStroke.value, point];
    }
    return;
  }
  if (interaction.type === 'pan') {
    if (zoom.value > 1 && interaction.startPan) {
      pan.value = {
        x: interaction.startPan.x + deltaX,
        y: interaction.startPan.y + deltaY,
      };
    }
    return;
  }
  const planeRect = planeRef.value?.getBoundingClientRect();
  const item = activeDraft.value?.items.find((candidate) => candidate.key === interaction.itemKey);
  if (!planeRect || !item || !interaction.startBox) return;
  const normalizedDeltaX = deltaX / planeRect.width;
  const normalizedDeltaY = deltaY / planeRect.height;
  item.bbox = interaction.type === 'move'
    ? moveBox(interaction.startBox, normalizedDeltaX, normalizedDeltaY)
    : resizeBox(interaction.startBox, normalizedDeltaX, normalizedDeltaY);
  touchPhoto();
}

/**
 * 提交一个新的人工框。带着当前"粘"住的类型一起落 —— 画完即定性, 不用再回右侧点一次,
 * 这正是连续标注提速的关键。没选类型时留空, 走原来的"待确认"流程。
 */
function commitHumanBox(bbox: LabelQcBoundingBox | null): void {
  const draft = activeDraft.value;
  if (!bbox || !draft || !props.canReview) return;
  const item = appendHumanBox(draft, bbox, `human-${draft.photoId}-${Date.now()}`);
  if (activeLabel.value) {
    item.label = activeLabel.value;
    selectedKey.value = null;
  } else {
    selectedKey.value = item.key;
  }
  setDirty(true);
}

function endPointer(event: PointerEvent): void {
  const interaction = pointerInteraction.value;
  if (!interaction || interaction.pointerId !== event.pointerId) return;
  pointerInteraction.value = null;

  if (interaction.type === 'draw') {
    const rect = planeRef.value?.getBoundingClientRect();
    const region = draftRect.value ?? strokeBounds(brushStroke.value, brushRadius.value);
    draftRect.value = null;
    brushStroke.value = [];
    if (!rect || !region) return;
    // 太小的拖拽多半是误触而不是标注意图, normalizedBox 会返回 null
    commitHumanBox(
      normalizedBox(region.x0, region.y0, region.x1, region.y1, rect.width, rect.height),
    );
    return;
  }

  if (interaction.type === 'pan' && !interaction.moved && toolMode.value === 'select') {
    addHumanBoxAt(event.clientX, event.clientY);
  }
}

function changeZoom(delta: number): void {
  const next = Math.min(4, Math.max(1, Number((zoom.value + delta).toFixed(2))));
  zoom.value = next;
  if (next === 1) pan.value = { x: 0, y: 0 };
}

function handleWheel(event: WheelEvent): void {
  // 画笔模式下滚轮改笔刷大小 —— 调笔刷远比缩放频繁, 缩放还有按钮和双击复位
  if (toolMode.value === 'brush') {
    const next = brushRadius.value + (event.deltaY < 0 ? 3 : -3);
    brushRadius.value = Math.min(BRUSH_MAX, Math.max(BRUSH_MIN, next));
    return;
  }
  changeZoom(event.deltaY < 0 ? 0.2 : -0.2);
}

function confirmCurrentPhoto(): void {
  if (!activeDraft.value) return;
  const error = markPhotoReviewed(activeDraft.value);
  if (error) {
    ElMessage.warning(`${error}，请先处理右侧“当前必须操作”`);
    choosePreferredItem();
    return;
  }
  setDirty(true);
  ElMessage.success(`第 ${activePhotoIndex.value + 1} 张整图结论已确认`);
}

async function confirmCurrentPhotoNormal(): Promise<void> {
  if (!activeDraft.value || !props.canReview) return;
  if (currentHumanItemCount.value > 0) {
    const firstHuman = activeDraft.value.items.find((item) => item.source === 'HUMAN');
    if (firstHuman) selectedKey.value = firstHuman.key;
    ElMessage.warning(`还有 ${currentHumanItemCount.value} 个人工补框，请先确认问题或删除框`);
    return;
  }
  if (normalAiImpactCount.value > 0) {
    try {
      await ElMessageBox.confirm(
        `这会把本图 ${normalAiImpactCount.value} 个 AI 疑点全部记录为误报，并作为后续训练真值。`,
        '确认整图正常？',
        {
          type: 'warning',
          confirmButtonText: `确认拒绝 ${normalAiImpactCount.value} 个疑点`,
          cancelButtonText: '继续逐个检查',
          distinguishCancelAndClose: true,
        },
      );
    } catch {
      return;
    }
  }
  try {
    markPhotoNormal(activeDraft.value);
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : '本图仍有未处理的人工补框');
    return;
  }
  selectedKey.value = null;
  setDirty(true);
  ElMessage.success('已标记本图正常，所有 AI 疑点均按误报记录');
}

function reopenCurrentPhoto(): void {
  if (!activeDraft.value || !props.canReview) return;
  activeDraft.value.reviewed = false;
  setDirty(true);
  choosePreferredItem();
}

// ---- AI 初筛参考层 ----------------------------------------------------------
// 模型除了给出"哪盒疑似缺标"的候选，还知道每盒里识别到了哪些标签及其位置。
// 把这三类画成只读参考层，质检员就能看到"白标在这、彩标在这、缺的位置是空的"，
// 而不是只看到一个盒子框。参考层不参与人工判定，只是背景信息。
type ScreenLayer = 'tray' | 'white' | 'color';

type ScreenLabelBox = { type: string; confidence?: number; bbox: number[] };
type ScreenTray = {
  index: number;
  bbox: number[];
  trayConfidence?: number;
  screenVerdict?: string;
  labels?: ScreenLabelBox[];
};

const LAYER_META: Record<ScreenLayer, { key: string; text: string; color: string }> = {
  tray: { key: '1', text: '盒子', color: '#2f6fdd' },
  white: { key: '2', text: '白标', color: '#06b6d4' },
  color: { key: '3', text: '彩标', color: '#a855f7' },
};

const visibleLayers = ref<Record<ScreenLayer, boolean>>({
  tray: true, white: true, color: true,
});

const screenTrays = computed<ScreenTray[]>(() => {
  const raw = activePhoto.value?.screeningDetail;
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as { trays?: ScreenTray[] };
    return Array.isArray(parsed?.trays) ? parsed.trays : [];
  } catch {
    // 明细坏了不能拖垮整个复核台，静默降级为"没有参考层"
    return [];
  }
});

const hasScreenDetail = computed(() => screenTrays.value.length > 0);

type RefBox = { key: string; layer: ScreenLayer; style: Record<string, string>; title: string };

const referenceBoxes = computed<RefBox[]>(() => {
  const out: RefBox[] = [];
  for (const tray of screenTrays.value) {
    if (visibleLayers.value.tray && tray.bbox?.length === 4) {
      out.push({
        key: `tray-${tray.index}`,
        layer: 'tray',
        style: boxStyleFrom(tray.bbox, LAYER_META.tray.color),
        title: `盒子 #${tray.index + 1}`,
      });
    }
    for (const [i, label] of (tray.labels ?? []).entries()) {
      const layer: ScreenLayer = label.type === 'white' ? 'white' : 'color';
      if (!visibleLayers.value[layer] || label.bbox?.length !== 4) continue;
      out.push({
        key: `lb-${tray.index}-${i}`,
        layer,
        style: boxStyleFrom(label.bbox, LAYER_META[layer].color),
        title: `${LAYER_META[layer].text} ${label.confidence != null
          ? Math.round(label.confidence * 100) + '%' : ''}`,
      });
    }
  }
  return out;
});

function boxStyleFrom(bbox: number[], color: string): Record<string, string> {
  const [x0, y0, x1, y1] = bbox;
  return {
    left: `${x0 * 100}%`,
    top: `${y0 * 100}%`,
    width: `${(x1 - x0) * 100}%`,
    height: `${(y1 - y0) * 100}%`,
    borderColor: color,
  };
}

function toggleLayer(layer: ScreenLayer): void {
  visibleLayers.value[layer] = !visibleLayers.value[layer];
}

// ---- 键盘快捷键 -------------------------------------------------------------
// ---- 标注工具：键盘定类型，鼠标画位置 --------------------------------------
// 质检员一天要过几百张，鼠标往返右侧按钮是主要耗时。左手键盘选类型 + 右手鼠标
// 画位置，是这类逐张标注界面的标准姿势。
//
// 工具与类型都是"粘"的：选一次之后连续画都沿用，不用每画一个框回去点一次。
type ToolMode = 'select' | 'box' | 'brush';

const toolMode = ref<ToolMode>('select');
const activeLabel = ref<LabelQcLabel | null>(null);
const brushRadius = ref(26);
const BRUSH_MIN = 6;
const BRUSH_MAX = 160;

/** 正在拖的框 / 正在涂的笔迹，都只是预览，松手才落成标注 */
const draftRect = ref<{ x0: number; y0: number; x1: number; y1: number } | null>(null);
const brushStroke = ref<{ x: number; y: number }[]>([]);
const cursorPoint = ref<{ x: number; y: number } | null>(null);

const TOOLS: { mode: ToolMode; key: string; text: string }[] = [
  { mode: 'select', key: 'V', text: '选择' },
  { mode: 'box', key: 'R', text: '拉框' },
  { mode: 'brush', key: 'B', text: '画笔' },
];

const QUICK_LABELS: { label: LabelQcLabel; key: string; text: string }[] = [
  { label: 'MISSING_WHITE_LABEL', key: '1', text: '缺白标' },
  { label: 'MISSING_COLOR_LABEL', key: '2', text: '缺彩标' },
  { label: 'NO_DEFECT', key: '3', text: '此框正常' },
  { label: 'UNJUDGEABLE', key: '4', text: '不可判定' },
];

const SHORTCUTS = [
  { keys: '1 / 2 / 3 / 4', text: '缺白标 / 缺彩标 / 正常 / 不可判定' },
  { keys: 'V / R / B', text: '选择 / 拉框 / 画笔' },
  { keys: '滚轮', text: '画笔模式调笔刷大小，否则缩放' },
  { keys: 'Enter', text: '确认本图结论' },
  { keys: 'N', text: '整图正常' },
  { keys: '← / →', text: '上一张 / 下一张' },
  { keys: 'Q / W / E', text: '盒子 / 白标 / 彩标 图层' },
  { keys: 'Esc', text: '取消选中框' },
] as const;

function setTool(mode: ToolMode): void {
  toolMode.value = mode;
  if (mode !== 'select') selectedKey.value = null;
}

/**
 * 按类型键：选中框时立刻定结论并跳下一个待判框；没选中框时只是把类型"粘"住，
 * 接下来画的框自动带上它。同一个键在两种情形下都符合"我要标这个类型"的直觉。
 */
function pickLabel(label: LabelQcLabel): void {
  activeLabel.value = label;
  if (selectedItem.value) resolveSelected(label);
}

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
}

function onShortcutKey(event: KeyboardEvent): void {
  // 输入框内打字、以及带修饰键的组合，一律不拦截
  if (isTypingTarget(event.target) || event.ctrlKey || event.metaKey || event.altKey) return;
  if (!props.canReview) return;

  switch (event.key) {
    case 'Enter':
      event.preventDefault();
      if (activeDraft.value?.reviewed) nextPhoto();
      else confirmCurrentPhoto();
      break;
    case 'n':
    case 'N':
      event.preventDefault();
      void confirmCurrentPhotoNormal();
      break;
    case 'ArrowLeft':
      event.preventDefault();
      previousPhoto();
      break;
    case 'ArrowRight':
      event.preventDefault();
      nextPhoto();
      break;
    case '1':
    case '2':
    case '3':
    case '4': {
      const quick = QUICK_LABELS[Number(event.key) - 1];
      if (quick) {
        event.preventDefault();
        pickLabel(quick.label);
      }
      break;
    }
    case 'q':
    case 'Q':
      event.preventDefault();
      toggleLayer('tray');
      break;
    case 'w':
    case 'W':
      event.preventDefault();
      toggleLayer('white');
      break;
    case 'e':
    case 'E':
      event.preventDefault();
      toggleLayer('color');
      break;
    case 'v':
    case 'V':
      event.preventDefault();
      setTool('select');
      break;
    case 'r':
    case 'R':
      event.preventDefault();
      setTool('box');
      break;
    case 'b':
    case 'B':
      event.preventDefault();
      setTool('brush');
      break;
    case 'Escape':
      if (selectedKey.value) {
        event.preventDefault();
        selectedKey.value = null;
      }
      break;
    default:
      break;
  }
}

onMounted(() => window.addEventListener('keydown', onShortcutKey));
onBeforeUnmount(() => window.removeEventListener('keydown', onShortcutKey));

function previousPhoto(): void {
  if (activePhotoIndex.value > 0) selectPhoto(activePhotoIndex.value - 1);
}

function nextPhoto(): void {
  if (activePhotoIndex.value < drafts.value.length - 1) {
    selectPhoto(activePhotoIndex.value + 1);
    return;
  }
  const incompleteIndex = firstIncompletePhotoIndex(drafts.value, activePhotoIndex.value);
  if (incompleteIndex >= 0) {
    selectPhoto(incompleteIndex);
    ElMessage.warning(`已回到第 ${incompleteIndex + 1} 张未完成照片`);
  }
}

function submitReview(): void {
  const validation = validateReviewDraft(drafts.value);
  if (validation) {
    const incompleteIndex = firstIncompletePhotoIndex(drafts.value);
    if (incompleteIndex >= 0) selectPhoto(incompleteIndex);
    ElMessage.warning(validation);
    return;
  }
  emit('submit', toReviewRequest(drafts.value));
}

function updateViewportSize(): void {
  const element = viewportRef.value;
  if (!element) return;
  viewportSize.value = {
    width: element.clientWidth,
    height: element.clientHeight,
  };
}

watch(
  () => props.detail,
  (detail) => {
    drafts.value = buildReviewDraft(detail);
    activePhotoIndex.value = 0;
    selectedKey.value = null;
    setDirty(false);
    resetView();
    void nextTick(() => {
      updateViewportSize();
      choosePreferredItem();
    });
  },
  { immediate: true },
);

onMounted(() => {
  resizeObserver = new ResizeObserver(updateViewportSize);
  if (viewportRef.value) resizeObserver.observe(viewportRef.value);
  updateViewportSize();
});

onBeforeUnmount(() => resizeObserver?.disconnect());
</script>

<template>
  <section class="review-workbench" aria-label="标签拍检人工审核工作台">
    <header class="workbench-status">
      <div class="task-context">
        <span class="context-kicker">正在审核</span>
        <strong>{{ detail.task.skuName }}</strong>
        <span>{{ detail.task.skuCode }} · {{ detail.task.batchNumber }} · {{ detail.task.productionDate }}</span>
      </div>
      <div class="progress-block">
        <span>{{ completedCount }}/{{ drafts.length }} 张已完成</span>
        <div class="progress-track"><i :style="{ width: `${reviewPercent}%` }" /></div>
        <strong>{{ reviewPercent }}%</strong>
      </div>
    </header>

    <div class="workbench-grid">
      <aside class="photo-rail" aria-label="待审核照片">
        <div class="rail-heading">照片</div>
        <button
          v-for="(photo, index) in detail.photos"
          :key="photo.id"
          type="button"
          class="photo-thumb"
          :class="{
            active: index === activePhotoIndex,
            complete: drafts[index] && isPhotoComplete(drafts[index]!),
          }"
          :aria-label="`第 ${index + 1} 张，${drafts[index] && isPhotoComplete(drafts[index]!) ? '已完成' : '待确认'}`"
          @click="selectPhoto(index)"
        >
          <img v-if="photo.imageUrl" :src="photo.imageUrl" :alt="`第 ${index + 1} 张照片`">
          <span class="photo-index">{{ index + 1 }}</span>
          <span class="photo-badge">
            <Check v-if="drafts[index] && isPhotoComplete(drafts[index]!)" />
            <Warning v-else />
          </span>
        </button>
      </aside>

      <main class="image-column">
        <div class="image-toolbar">
          <div>
            <span class="toolbar-kicker">第 {{ activePhotoIndex + 1 }} 张原图</span>
            <strong>{{ aiReviewStatusText }}</strong>
          </div>
          <div class="zoom-tools" aria-label="照片缩放">
            <button type="button" aria-label="缩小照片" @click="changeZoom(-0.25)"><Minus /></button>
            <span>{{ Math.round(zoom * 100) }}%</span>
            <button type="button" aria-label="放大照片" @click="changeZoom(0.25)"><Plus /></button>
            <button type="button" class="reset-view" @click="resetView"><RefreshLeft />复位</button>
          </div>
        </div>

        <div v-if="canReview" class="annotate-bar">
          <div class="tool-group" role="group" aria-label="标注工具">
            <button
              v-for="tool in TOOLS"
              :key="tool.mode"
              type="button"
              class="tool-btn"
              :class="{ on: toolMode === tool.mode }"
              :aria-pressed="toolMode === tool.mode"
              @click="setTool(tool.mode)"
            >
              {{ tool.text }}<kbd>{{ tool.key }}</kbd>
            </button>
          </div>
          <span class="bar-sep" />
          <div class="tool-group" role="group" aria-label="标注类型">
            <button
              v-for="quick in QUICK_LABELS"
              :key="quick.label"
              type="button"
              class="tool-btn label-btn"
              :class="{ on: activeLabel === quick.label }"
              :style="activeLabel === quick.label
                ? { backgroundColor: labelColor(quick.label), borderColor: labelColor(quick.label) }
                : { borderColor: labelColor(quick.label) }"
              :aria-pressed="activeLabel === quick.label"
              @click="pickLabel(quick.label)"
            >
              {{ quick.text }}<kbd>{{ quick.key }}</kbd>
            </button>
          </div>
          <span v-if="toolMode === 'brush'" class="brush-size">
            笔刷 {{ brushRadius }}px<em>滚轮调整</em>
          </span>
        </div>

        <div class="gesture-hint">
          <Aim />
          <span v-if="toolMode === 'box'">
            <strong>按住左键拖出一个框</strong> · 键盘 1/2/3/4 先选类型，画完即定性 · 中键拖动画面
          </span>
          <span v-else-if="toolMode === 'brush'">
            <strong>按住左键涂抹</strong> · 滚轮调笔刷大小 · 涂过的范围会圈成一个框 · 中键拖动画面
          </span>
          <span v-else>
            <strong>点照片空白处补框</strong> · 拖框移动 · 拖右下角缩放 · 滚轮放大照片后拖动画面
          </span>
        </div>

        <div
          ref="viewportRef"
          class="image-viewport"
          :class="[`tool-${toolMode}`, { drawing: !!draftRect || !!brushStroke.length }]"
          @pointerdown="startViewportPointer"
          @pointermove="movePointer"
          @pointerup="endPointer"
          @pointercancel="endPointer"
          @pointerleave="cursorPoint = null"
          @wheel.prevent="handleWheel"
          @dblclick="resetView"
        >
          <div ref="planeRef" class="image-plane" :style="imagePlaneStyle">
            <img
              v-if="activePhoto.imageUrl"
              :src="activePhoto.imageUrl"
              alt="待审核包装标签照片"
              draggable="false"
            >
            <!-- AI 初筛参考层：只读，画在人工标注框下面 -->
            <div
              v-for="ref in referenceBoxes"
              :key="ref.key"
              class="reference-box"
              :class="`layer-${ref.layer}`"
              :style="ref.style"
              :title="ref.title"
            />
            <div
              v-for="item in visibleItems"
              :key="item.key"
              class="annotation-box"
              :class="{
                selected: selectedKey === item.key,
                pending: !item.label,
                human: item.source === 'HUMAN',
              }"
              :style="annotationStyle(item)"
              @pointerdown="startBoxPointer($event, item, 'move')"
            >
              <button
                type="button"
                class="box-label"
                :style="{ backgroundColor: itemColor(item) }"
                @pointerdown.stop="selectItem(item)"
              >
                {{ item.source === 'AI' ? 'AI' : '人工' }} · {{ labelText(item.label ?? item.aiLabel) }}
              </button>
              <button
                v-if="canReview"
                type="button"
                class="resize-handle"
                aria-label="缩放标注框"
                @pointerdown="startBoxPointer($event, item, 'resize')"
              />
            </div>

            <!-- 落笔预览：拉框的橡皮筋 / 涂抹的笔迹 / 笔刷光标，都不接指针事件 -->
            <div v-if="draftRect" class="draft-rect" :style="draftRectStyle" />
            <div
              v-for="(dot, index) in brushStroke"
              :key="`stroke-${index}`"
              class="brush-dot"
              :style="brushDotStyle(dot)"
            />
            <div
              v-if="toolMode === 'brush' && cursorPoint && !brushStroke.length"
              class="brush-cursor"
              :style="brushDotStyle(cursorPoint)"
            />
          </div>

          <div v-if="zoom > 1" class="zoom-indicator">已放大 · 拖动空白处移动画面</div>
        </div>
      </main>

      <aside class="decision-rail">
        <div class="decision-scroll">
          <section class="must-act-card" :class="{ resolved: selectedItem?.label }">
          <div class="must-act-heading">
            <span>当前必须操作</span>
            <em v-if="pendingItems.length">{{ pendingItems.length }} 个待确认</em>
            <em v-else class="done">框已处理</em>
          </div>

          <template v-if="selectedItem?.source === 'AI'">
            <div class="candidate-title">
              <i :style="{ backgroundColor: itemColor(selectedItem) }">AI</i>
              <div>
                <strong>{{ labelText(selectedItem.aiLabel) }}</strong>
                <span>置信度 {{ confidence(selectedItem.aiConfidence) }}</span>
              </div>
            </div>
            <p v-if="selectedItem.aiEvidence" class="evidence">{{ selectedItem.aiEvidence }}</p>
            <div class="primary-decisions">
              <button type="button" class="confirm" :disabled="!canReview" @click="confirmAiCandidate">
                <Check /> 确认：{{ labelText(selectedItem.aiLabel) }}
              </button>
              <button type="button" class="reject" :disabled="!canReview" @click="rejectAiCandidate">
                <Close /> 拒绝并移除框
              </button>
            </div>
            <div class="correction-row">
              <button type="button" :disabled="!canReview" @click="resolveSelected('MISSING_WHITE_LABEL')">改为缺白标</button>
              <button type="button" :disabled="!canReview" @click="resolveSelected('MISSING_COLOR_LABEL')">改为缺彩标</button>
              <button type="button" :disabled="!canReview" @click="resolveSelected('UNJUDGEABLE')">无法判断</button>
            </div>
          </template>

          <template v-else-if="selectedItem?.source === 'HUMAN'">
            <div class="candidate-title human-title">
              <i>人</i>
              <div>
                <strong>人工补充框</strong>
                <span>请选择这个框的问题类型</span>
              </div>
            </div>
            <div class="human-decisions">
              <button type="button" :disabled="!canReview" @click="setHumanLabel('MISSING_WHITE_LABEL')">缺白标</button>
              <button type="button" :disabled="!canReview" @click="setHumanLabel('MISSING_COLOR_LABEL')">缺彩标</button>
              <button type="button" :disabled="!canReview" @click="setHumanLabel('UNJUDGEABLE')">无法判断</button>
              <button type="button" class="delete" :disabled="!canReview" @click="deleteHumanItem"><Delete />删除框</button>
            </div>
          </template>

          <div v-else class="no-selection">
            <Check v-if="pendingItems.length === 0" />
            <Aim v-else />
            <strong>{{ pendingItems.length === 0 ? '本图所有框已处理' : '点击照片中的框开始审核' }}</strong>
            <span>{{ pendingItems.length === 0 ? '现在请给出整图结论' : '橙色框为 AI 待确认疑点' }}</span>
          </div>

          <el-input
            v-if="selectedItem"
            v-model="selectedItem.notes"
            class="review-note"
            :disabled="!canReview"
            maxlength="200"
            placeholder="可选：补充判断依据"
            @update:model-value="touchPhoto"
          />
          </section>

          <section v-if="rejectedAiItems.length" class="rejected-history">
            <div class="rejected-heading">
              <div>
                <strong>已拒绝 AI 疑点</strong>
                <span>这些框不会显示在照片上，可在提交前撤销</span>
              </div>
              <em>{{ rejectedAiItems.length }}</em>
            </div>
            <div
              v-for="item in rejectedAiItems"
              :key="item.key"
              class="rejected-item"
            >
              <span>{{ labelText(item.aiLabel) }} · 置信度 {{ confidence(item.aiConfidence) }}</span>
              <button
                type="button"
                :disabled="!canReview"
                @click="undoRejectedAiCandidate(item)"
              >
                撤销
              </button>
            </div>
          </section>

          <section v-if="activePhoto.analysisError" class="analysis-error">
            <Warning />
            <div>
              <strong>本图 AI 初筛异常</strong>
              <span>{{ activePhoto.analysisError }}</span>
            </div>
            <button type="button" :disabled="retrying" @click="emit('retry')">
              {{ retrying ? '重试中…' : '重试 AI' }}
            </button>
          </section>
        </div>

        <section class="whole-photo-card" :class="{ complete: currentPhotoComplete }">
          <div class="step-label">第 2 步</div>
          <div>
            <strong>给出本图结论</strong>
            <span v-if="pendingItemCount(activeDraft)">
              先处理上方 {{ pendingItemCount(activeDraft) }} 个待确认框
            </span>
            <span v-else>确认本图除已标注外没有其他问题</span>
          </div>
          <template v-if="!currentPhotoComplete">
            <button
              type="button"
              class="whole-confirm"
              :disabled="!canReview || pendingItemCount(activeDraft) > 0"
              @click="confirmCurrentPhoto"
            >
              <Check /> 确认本图结论
            </button>
            <button
              type="button"
              class="whole-normal"
              :disabled="!canReview || currentHumanItemCount > 0"
              @click="confirmCurrentPhotoNormal"
            >
              <template v-if="currentHumanItemCount">
                请先处理 {{ currentHumanItemCount }} 个人工补框
              </template>
              <template v-else-if="normalAiImpactCount">
                整图正常 · 拒绝 {{ normalAiImpactCount }} 个 AI 疑点
              </template>
              <template v-else>
                整图正常 · 本图没有其他问题
              </template>
            </button>
          </template>
          <button v-else type="button" class="reopen" :disabled="!canReview" @click="reopenCurrentPhoto">
            <Check /> 本图已完成 · 点击重新检查
          </button>
        </section>
      </aside>
    </div>

    <footer class="review-navigation">
      <button type="button" class="previous" :disabled="activePhotoIndex === 0" @click="previousPhoto">
        上一张
      </button>
      <div class="current-state" :class="{ complete: currentPhotoComplete }">
        <span>第 {{ activePhotoIndex + 1 }}/{{ drafts.length }} 张</span>
        <strong>{{ currentPhotoComplete ? '本图已完成' : '本图待结论' }}</strong>
        <div v-if="canReview" class="shortcut-hints">
          <span v-for="s in SHORTCUTS" :key="s.keys" class="shortcut">
            <kbd>{{ s.keys }}</kbd>{{ s.text }}
          </span>
        </div>
        <div v-if="hasScreenDetail" class="layer-toggles">
          <button
            v-for="(meta, layer) in LAYER_META"
            :key="layer"
            type="button"
            class="layer-toggle"
            :class="{ off: !visibleLayers[layer as ScreenLayer] }"
            :style="{ '--layer-color': meta.color }"
            @click="toggleLayer(layer as ScreenLayer)"
          >
            <i class="dot" /><kbd>{{ meta.key }}</kbd>{{ meta.text }}
          </button>
        </div>
      </div>
      <button
        v-if="!allComplete"
        type="button"
        class="next"
        :disabled="!currentPhotoComplete"
        @click="nextPhoto"
      >
        {{ nextButtonText }}
        <Right />
      </button>
      <button
        v-else
        type="button"
        class="submit"
        :disabled="!canReview || submitting"
        @click="submitReview"
      >
        <Check /> {{ submitting ? '提交中…' : '提交整单人工审核' }}
      </button>
    </footer>
  </section>
</template>

<style scoped>
.review-workbench {
  --ink: #12261f;
  --muted: #62706b;
  --line: #dfe6e1;
  --paper: #f7f5ef;
  --green: #00a987;
  --green-dark: #08745f;
  --orange: #f5a524;
  display: grid;
  height: calc(100vh - 92px);
  min-height: 650px;
  grid-template-rows: 62px minmax(0, 1fr) 72px;
  overflow: hidden;
  color: var(--ink);
  background: var(--paper);
}

button {
  font: inherit;
}

.workbench-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 8px 20px;
  border-bottom: 1px solid var(--line);
  background: #fffefa;
}

.task-context {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 10px;
}

.task-context strong {
  overflow: hidden;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-context > span:last-child {
  color: var(--muted);
  font-size: 12px;
}

.context-kicker,
.toolbar-kicker,
.rail-heading {
  color: var(--green-dark);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.progress-block {
  display: grid;
  min-width: 270px;
  grid-template-columns: auto 110px 38px;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}

.progress-track {
  height: 6px;
  overflow: hidden;
  border-radius: 99px;
  background: #dfe9e4;
}

.progress-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--green);
  transition: width .2s ease;
}

.workbench-grid {
  display: grid;
  min-height: 0;
  grid-template-columns: 92px minmax(430px, 1fr) minmax(300px, 350px);
  gap: 14px;
  padding: 14px;
}

.photo-rail {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
  scrollbar-width: thin;
}

.rail-heading {
  padding: 2px 4px 4px;
}

.photo-thumb {
  position: relative;
  flex: 0 0 102px;
  overflow: hidden;
  padding: 0;
  border: 2px solid transparent;
  border-radius: 9px;
  background: #dfe4e1;
  cursor: pointer;
  transition: border-color .15s ease, transform .15s ease;
}

.photo-thumb:hover {
  transform: translateY(-1px);
}

.photo-thumb.active {
  border-color: var(--green);
  box-shadow: 0 0 0 2px rgba(0, 169, 135, .14);
}

.photo-thumb.complete .photo-badge {
  background: var(--green);
}

.photo-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.photo-index,
.photo-badge {
  position: absolute;
  display: grid;
  place-items: center;
  border-radius: 99px;
  color: #fff;
}

.photo-index {
  top: 5px;
  left: 5px;
  width: 23px;
  height: 23px;
  background: rgba(18, 38, 31, .86);
  font-size: 11px;
  font-weight: 800;
}

.photo-badge {
  right: 5px;
  bottom: 5px;
  width: 22px;
  height: 22px;
  background: var(--orange);
}

.photo-badge :deep(svg) {
  width: 13px;
}

.image-column {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-rows: auto auto minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid #253a33;
  border-radius: 14px;
  background: #152620;
  box-shadow: 0 10px 28px rgba(18, 38, 31, .13);
}

.image-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 14px;
  color: #f6faf8;
}

.image-toolbar > div:first-child {
  display: grid;
  gap: 2px;
}

.image-toolbar .toolbar-kicker {
  color: #8bd6c2;
}

.zoom-tools {
  display: flex;
  align-items: center;
  gap: 4px;
}

.zoom-tools button {
  display: inline-flex;
  height: 30px;
  align-items: center;
  gap: 4px;
  justify-content: center;
  padding: 0 8px;
  border: 1px solid #4b625a;
  border-radius: 7px;
  color: #eafff8;
  background: #263c34;
  cursor: pointer;
}

.zoom-tools button:not(.reset-view) {
  width: 30px;
  padding: 0;
}

.zoom-tools :deep(svg) {
  width: 14px;
}

.zoom-tools > span {
  min-width: 46px;
  color: #a9bbb5;
  font-size: 11px;
  text-align: center;
}

.gesture-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-block: 1px solid #3d514a;
  color: #c7d6d1;
  background: #20332d;
  font-size: 12px;
}

.gesture-hint :deep(svg) {
  width: 16px;
  color: #62d0b5;
}

.gesture-hint strong {
  color: #fff;
}

.image-viewport {
  position: relative;
  min-height: 0;
  overflow: hidden;
  cursor: crosshair;
  touch-action: none;
  user-select: none;
  background:
    radial-gradient(circle at center, rgba(255, 255, 255, .04), transparent 48%),
    #0d1915;
}

.image-plane {
  position: absolute;
  transform-origin: center;
  transition: transform .08s linear;
}

.image-plane > img {
  display: block;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

/* ---- 标注工具条 ---- */
.annotate-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.tool-group { display: flex; gap: 6px; }

.bar-sep {
  width: 1px;
  height: 18px;
  background: var(--el-border-color);
}

.tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--el-border-color);
  border-radius: 999px;
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-primary);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.tool-btn kbd {
  padding: 0 4px;
  border-radius: 3px;
  background: rgba(0, 0, 0, .09);
  font-size: 10px;
  font-family: inherit;
}

.tool-btn.on { background: #2f6fdd; border-color: #2f6fdd; color: #fff; }
.tool-btn.on kbd { background: rgba(255, 255, 255, .25); }
.tool-btn.label-btn.on { color: #fff; }

.brush-size {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.brush-size em { font-style: normal; opacity: .7; }

/* 光标形状直接表达当前工具：十字=画框，无光标=画笔(用自绘的圆代替) */
.image-viewport.tool-box { cursor: crosshair; }
.image-viewport.tool-brush { cursor: none; }

/* ---- 落笔预览：一律不接指针事件，否则会截断正在进行的拖拽 ---- */
.draft-rect {
  position: absolute;
  z-index: 5;
  border: 2px dashed;
  border-radius: 4px;
  background: rgba(47, 111, 221, .10);
  pointer-events: none;
}

.brush-dot,
.brush-cursor {
  position: absolute;
  z-index: 5;
  border-radius: 50%;
  pointer-events: none;
}

.brush-dot { opacity: .28; }

.brush-cursor {
  background: transparent !important;
  border: 2px solid;
  opacity: .85;
}

/* AI 初筛参考层：更细、半透明、不可交互，避免和人工标注框抢视觉 */
.reference-box {
  position: absolute;
  z-index: 1;
  border: 1px solid;
  border-radius: 3px;
  pointer-events: none;
  opacity: .85;
}

.reference-box.layer-tray {
  border-style: dashed;
  opacity: .6;
}

/* 单层细框：原先是 3px 边框 + 1px 白描边 (选中时再叠 3px 白 + 6px 绿 = 一圈 9px)，
   在密排的肉盒上糊成一片。改为 2px 单线，选中只加深不加层。 */
.annotation-box {
  position: absolute;
  z-index: 2;
  border: 2px solid;
  border-radius: 4px;
  cursor: move;
}

.annotation-box.pending {
  border-style: dashed;
  background: rgba(245, 165, 36, .08);
}

.annotation-box.human {
  background: rgba(47, 111, 221, .10);
}

.annotation-box.selected {
  border-width: 3px;
  z-index: 4;
  filter: drop-shadow(0 0 3px rgba(0, 0, 0, .55));
}

.box-label {
  position: absolute;
  top: -27px;
  left: -3px;
  min-width: max-content;
  padding: 4px 7px;
  border: 0;
  border-radius: 5px 5px 5px 0;
  color: #fff;
  font-size: 11px;
  font-weight: 800;
  cursor: pointer;
}

.resize-handle {
  position: absolute;
  right: -9px;
  bottom: -9px;
  width: 18px;
  height: 18px;
  border: 3px solid #fff;
  border-radius: 99px;
  background: #17332a;
  cursor: nwse-resize;
}

.zoom-indicator {
  position: absolute;
  right: 12px;
  bottom: 12px;
  z-index: 8;
  padding: 6px 9px;
  border-radius: 7px;
  color: #d9eee7;
  background: rgba(12, 30, 24, .82);
  font-size: 11px;
  pointer-events: none;
}

.decision-rail {
  display: grid;
  min-height: 0;
  grid-template-rows: minmax(0, 1fr) auto;
  gap: 12px;
  overflow: hidden;
}

.decision-scroll {
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-width: thin;
}

.must-act-card,
.whole-photo-card,
.analysis-error {
  border: 1px solid var(--line);
  border-radius: 13px;
  background: #fff;
}

.must-act-card {
  padding: 14px;
  border-top: 4px solid var(--orange);
}

.must-act-card.resolved {
  border-top-color: var(--green);
}

.must-act-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.must-act-heading > span {
  font-size: 14px;
  font-weight: 900;
}

.must-act-heading em {
  padding: 4px 8px;
  border-radius: 99px;
  color: #9a5c00;
  background: #fff1d3;
  font-size: 11px;
  font-style: normal;
  font-weight: 800;
}

.must-act-heading em.done {
  color: var(--green-dark);
  background: #dff6ef;
}

.candidate-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 14px 0 8px;
}

.candidate-title i {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 9px;
  color: #fff;
  font-size: 12px;
  font-style: normal;
  font-weight: 900;
}

.candidate-title div {
  display: grid;
  gap: 2px;
}

.candidate-title span,
.whole-photo-card span,
.analysis-error span,
.no-selection span {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.45;
}

.human-title i {
  background: #2f6fdd;
}

.evidence {
  margin: 8px 0;
  padding: 8px;
  border-radius: 7px;
  color: #6f5940;
  background: #fff8e9;
  font-size: 11px;
  line-height: 1.5;
}

.primary-decisions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 10px;
}

.primary-decisions button,
.whole-photo-card button,
.human-decisions button,
.correction-row button {
  display: inline-flex;
  min-height: 40px;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 8px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
}

.primary-decisions :deep(svg),
.whole-photo-card :deep(svg),
.human-decisions :deep(svg) {
  width: 15px;
}

.primary-decisions .confirm {
  border: 1px solid var(--green);
  color: #fff;
  background: var(--green);
}

.primary-decisions .reject {
  border: 1px solid #f0aaa5;
  color: #b83127;
  background: #fff1ef;
}

.correction-row,
.human-decisions {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  margin-top: 8px;
}

.correction-row button,
.human-decisions button {
  min-height: 34px;
  border: 1px solid #d7ded9;
  color: #34443e;
  background: #f7f8f5;
  font-size: 11px;
}

.human-decisions {
  grid-template-columns: 1fr 1fr;
  margin-top: 14px;
}

.human-decisions .delete {
  color: #b83127;
  border-color: #f0aaa5;
  background: #fff5f3;
}

button:disabled {
  cursor: not-allowed !important;
  opacity: .45;
}

.no-selection {
  display: grid;
  justify-items: center;
  gap: 5px;
  padding: 26px 8px 17px;
  text-align: center;
}

.no-selection :deep(svg) {
  width: 30px;
  color: var(--green);
}

.review-note {
  margin-top: 10px;
}

.whole-photo-card {
  display: grid;
  gap: 9px;
  padding: 14px;
  border-color: #c8dfd7;
  background: #f1fbf7;
  box-shadow: 0 -6px 18px rgba(18, 38, 31, .07);
}

.whole-photo-card.complete {
  border-color: #8ed5c2;
  background: #e5f8f1;
}

.whole-photo-card > div:not(.step-label) {
  display: grid;
  gap: 3px;
}

.step-label {
  justify-self: start;
  padding: 4px 8px;
  border-radius: 99px;
  color: #fff;
  background: var(--green);
  font-size: 10px;
  font-weight: 900;
}

.whole-photo-card .whole-confirm {
  border: 1px solid var(--green);
  color: #fff;
  background: var(--green);
}

.whole-photo-card .whole-normal,
.whole-photo-card .reopen {
  border: 1px solid #8bcdbb;
  color: var(--green-dark);
  background: #fff;
}

.rejected-history {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid #ead7b2;
  border-radius: 11px;
  background: #fffaf0;
}

.rejected-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.rejected-heading > div {
  display: grid;
  gap: 2px;
}

.rejected-heading strong {
  font-size: 12px;
}

.rejected-heading span {
  color: var(--muted);
  font-size: 10px;
  line-height: 1.4;
}

.rejected-heading em {
  display: grid;
  min-width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 99px;
  color: #8a5013;
  background: #ffe8bc;
  font-size: 11px;
  font-style: normal;
  font-weight: 900;
}

.rejected-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #f0e3ca;
}

.rejected-item span {
  overflow: hidden;
  color: #685845;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rejected-item button {
  flex: 0 0 auto;
  padding: 4px 8px;
  border: 1px solid #d9ad70;
  border-radius: 6px;
  color: #80501e;
  background: #fff;
  font-size: 10px;
  font-weight: 800;
  cursor: pointer;
}

.analysis-error {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 9px;
  margin-top: 12px;
  padding: 12px;
  color: #8a5013;
  background: #fff8e9;
}

.analysis-error :deep(svg) {
  width: 20px;
}

.analysis-error div {
  display: grid;
}

.analysis-error button {
  border: 1px solid #d8aa71;
  border-radius: 7px;
  color: #8a5013;
  background: #fff;
}

.review-navigation {
  display: grid;
  grid-template-columns: 126px minmax(160px, 1fr) minmax(190px, 250px);
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-top: 1px solid var(--line);
  background: #fffefa;
  box-shadow: 0 -8px 24px rgba(18, 38, 31, .07);
}

.shortcut-hints {
  display: flex;
  gap: 14px;
  margin-top: 6px;
  flex-wrap: wrap;
  justify-content: center;
}

.shortcut {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--el-text-color-secondary, #8a94a6);
  white-space: nowrap;
}

.layer-toggles {
  display: flex;
  gap: 8px;
  margin-top: 6px;
  justify-content: center;
}

.layer-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  border: 1px solid var(--el-border-color, #d9dde5);
  border-radius: 99px;
  background: var(--el-bg-color, #fff);
  font-size: 11px;
  color: var(--el-text-color-regular, #4a5262);
  cursor: pointer;
}

.layer-toggle .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--layer-color);
}

.layer-toggle.off {
  opacity: .45;
}

.layer-toggle.off .dot {
  background: transparent;
  border: 1px solid var(--layer-color);
}

.shortcut kbd,
.layer-toggle kbd {
  display: inline-block;
  min-width: 18px;
  padding: 1px 6px;
  border: 1px solid var(--el-border-color, #d9dde5);
  border-bottom-width: 2px;
  border-radius: 4px;
  background: var(--el-fill-color-light, #f4f6f9);
  font: 600 11px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--el-text-color-regular, #4a5262);
}

.review-navigation > button {
  display: inline-flex;
  height: 48px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 10px;
  font-weight: 800;
  cursor: pointer;
}

.review-navigation .previous {
  border: 1px solid #d9dedb;
  color: #53615c;
  background: #f8f7f3;
}

.review-navigation .next,
.review-navigation .submit {
  border: 1px solid var(--green);
  color: #fff;
  background: var(--green);
}

.review-navigation :deep(svg) {
  width: 17px;
}

.current-state {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 9px;
}

.current-state span {
  color: var(--muted);
  font-size: 12px;
}

.current-state strong {
  color: #a45e0b;
}

.current-state.complete strong {
  color: var(--green-dark);
}

@media (max-width: 1180px) {
  .workbench-grid {
    grid-template-columns: 76px minmax(380px, 1fr) 290px;
    gap: 10px;
    padding: 10px;
  }

  .photo-thumb {
    flex-basis: 88px;
  }

  .decision-rail {
    font-size: 12px;
  }

  .primary-decisions {
    grid-template-columns: 1fr;
  }

  .correction-row {
    grid-template-columns: 1fr 1fr;
  }

  .correction-row button:last-child {
    grid-column: 1 / -1;
  }
}

@media (max-width: 880px) {
  .review-workbench {
    height: auto;
    min-height: calc(100vh - 70px);
    grid-template-rows: auto auto 72px;
    overflow: visible;
  }

  .workbench-status {
    align-items: flex-start;
    flex-direction: column;
  }

  .progress-block {
    width: 100%;
  }

  .workbench-grid {
    grid-template-columns: 1fr;
  }

  .photo-rail {
    flex-direction: row;
    overflow-x: auto;
  }

  .rail-heading {
    display: none;
  }

  .photo-thumb {
    min-width: 74px;
    height: 82px;
    flex-basis: 74px;
  }

  .image-column {
    height: 620px;
  }
}
</style>
