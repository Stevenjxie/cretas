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
  pendingItemCount,
  pointBox,
  resizeBox,
  restoreRejectedAiCandidate,
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
  type: 'pan' | 'move' | 'resize';
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

function itemColor(item: LabelQcReviewDraft): string {
  if (!item.label) return item.source === 'AI' ? '#f5a524' : '#2f6fdd';
  if (item.label === 'MISSING_WHITE_LABEL') return '#e54d42';
  if (item.label === 'MISSING_COLOR_LABEL') return '#d97706';
  if (item.label === 'UNJUDGEABLE') return '#6b7280';
  return '#16a36a';
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

function startViewportPointer(event: PointerEvent): void {
  if (event.button !== 0) return;
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
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
  const interaction = pointerInteraction.value;
  if (!interaction || interaction.pointerId !== event.pointerId) return;
  const deltaX = event.clientX - interaction.startX;
  const deltaY = event.clientY - interaction.startY;
  if (Math.abs(deltaX) + Math.abs(deltaY) > 4) interaction.moved = true;
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

function endPointer(event: PointerEvent): void {
  const interaction = pointerInteraction.value;
  if (!interaction || interaction.pointerId !== event.pointerId) return;
  if (interaction.type === 'pan' && !interaction.moved) {
    addHumanBoxAt(event.clientX, event.clientY);
  }
  pointerInteraction.value = null;
}

function changeZoom(delta: number): void {
  const next = Math.min(4, Math.max(1, Number((zoom.value + delta).toFixed(2))));
  zoom.value = next;
  if (next === 1) pan.value = { x: 0, y: 0 };
}

function handleWheel(event: WheelEvent): void {
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
// 质检员一天要过几百张，鼠标往返右侧按钮是主要耗时。左手键盘 + 右手鼠标点框，
// 是这类逐张审核界面的标准姿势。
const SHORTCUTS = [
  { keys: 'Enter', text: '确认本图结论' },
  { keys: 'N', text: '整图正常' },
  { keys: '← / →', text: '上一张 / 下一张' },
  { keys: '1 / 2 / 3', text: '盒子 / 白标 / 彩标' },
  { keys: 'Esc', text: '取消选中框' },
] as const;

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
      event.preventDefault();
      toggleLayer('tray');
      break;
    case '2':
      event.preventDefault();
      toggleLayer('white');
      break;
    case '3':
      event.preventDefault();
      toggleLayer('color');
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

        <div class="gesture-hint">
          <Aim />
          <span><strong>点照片空白处补框</strong> · 拖框移动 · 拖右下角缩放 · 滚轮放大照片后拖动画面</span>
        </div>

        <div
          ref="viewportRef"
          class="image-viewport"
          @pointerdown="startViewportPointer"
          @pointermove="movePointer"
          @pointerup="endPointer"
          @pointercancel="endPointer"
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
