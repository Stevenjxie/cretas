<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Aim,
  Check,
  Download,
  EditPen,
  Refresh,
  RefreshRight,
  Warning,
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  exportLabelQcTrainingData,
  getLabelQcStatusCounts,
  getLabelQcTask,
  listLabelQcTasks,
  retryLabelQcTask,
  reviewLabelQcTask,
  type LabelQcBoundingBox,
  type LabelQcLabel,
  type LabelQcPhoto,
  type LabelQcTaskDetail,
  type LabelQcTaskStatus,
  type LabelQcTaskSummary,
} from '@/api/labelQc';
import {
  appendHumanBox,
  buildReviewDraft,
  normalizedBox,
  toReviewRequest,
  validateReviewDraft,
  type LabelQcPhotoDraft,
  type LabelQcReviewDraft,
} from './reviewModel';

const LABEL_OPTIONS: Array<{ value: LabelQcLabel; label: string }> = [
  { value: 'MISSING_WHITE_LABEL', label: '缺白标' },
  { value: 'MISSING_COLOR_LABEL', label: '缺彩标' },
  { value: 'NO_DEFECT', label: '正常（AI 误报）' },
  { value: 'UNJUDGEABLE', label: '无法判断' },
];

const STATUS_LABELS: Record<LabelQcTaskStatus, string> = {
  DRAFT: '草稿',
  UPLOADING: '上传中',
  QUEUED: '排队中',
  ANALYZING: 'AI 初筛中',
  NEEDS_REVIEW: '待人工审核',
  REVIEWED: '已审核',
  ANALYSIS_FAILED: 'AI 异常',
};

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const loading = ref(false);
const rows = ref<LabelQcTaskSummary[]>([]);
const counts = ref<Partial<Record<LabelQcTaskStatus, number>>>({});
const selectedStatuses = ref<LabelQcTaskStatus[]>(['NEEDS_REVIEW', 'ANALYSIS_FAILED']);
const pagination = ref({ page: 1, size: 20, total: 0 });

const drawerVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<LabelQcTaskDetail | null>(null);
const drafts = ref<LabelQcPhotoDraft[]>([]);
const activePhotoIndex = ref(0);
const submitting = ref(false);
const retryingTaskId = ref<string | null>(null);
const drawingMode = ref(false);
const stageRef = ref<HTMLElement | null>(null);
const drawStart = ref<{ x: number; y: number } | null>(null);
const drawCurrent = ref<{ x: number; y: number } | null>(null);

const activePhoto = computed<LabelQcPhoto | null>(
  () => detail.value?.photos[activePhotoIndex.value] ?? null,
);
const activeDraft = computed<LabelQcPhotoDraft | null>(
  () => drafts.value[activePhotoIndex.value] ?? null,
);
const canReview = computed(() => {
  const status = detail.value?.task.status;
  return status === 'NEEDS_REVIEW' || status === 'ANALYSIS_FAILED';
});
const reviewProgress = computed(() => {
  if (drafts.value.length === 0) return 0;
  const complete = drafts.value.filter((photo) => photo.items.length > 0).length;
  return Math.round((complete / drafts.value.length) * 100);
});

async function load() {
  if (!factoryId.value) {
    ElMessage.warning('缺少工厂登录上下文，请重新登录');
    return;
  }
  loading.value = true;
  try {
    const [listResponse, countResponse] = await Promise.all([
      listLabelQcTasks(factoryId.value, {
        statuses: selectedStatuses.value.length ? selectedStatuses.value : undefined,
        page: pagination.value.page,
        size: pagination.value.size,
      }),
      getLabelQcStatusCounts(factoryId.value),
    ]);
    if (listResponse.success) {
      rows.value = listResponse.data.content;
      pagination.value.total = listResponse.data.totalElements;
    }
    if (countResponse.success) {
      counts.value = countResponse.data.counts;
    }
  } finally {
    loading.value = false;
  }
}

function changeFilter() {
  pagination.value.page = 1;
  void load();
}

function changePage(page: number) {
  pagination.value.page = page;
  void load();
}

function statusType(status: LabelQcTaskStatus): 'danger' | 'warning' | 'success' | 'info' {
  if (status === 'REVIEWED') return 'success';
  if (status === 'ANALYSIS_FAILED') return 'danger';
  if (status === 'NEEDS_REVIEW') return 'warning';
  return 'info';
}

function statusText(status: LabelQcTaskStatus): string {
  return STATUS_LABELS[status];
}

function labelText(label?: LabelQcLabel | null): string {
  return LABEL_OPTIONS.find((option) => option.value === label)?.label ?? '未判断';
}

function defectColor(label?: LabelQcLabel | null): string {
  if (label === 'MISSING_COLOR_LABEL') return '#F97316';
  if (label === 'MISSING_WHITE_LABEL') return '#DC2626';
  if (label === 'UNJUDGEABLE') return '#7C3AED';
  return '#16A34A';
}

function percentage(value?: number | null): string {
  return value == null ? '—' : `${Math.round(value * 100)}%`;
}

async function openReview(row: LabelQcTaskSummary) {
  if (!factoryId.value) return;
  drawerVisible.value = true;
  detailLoading.value = true;
  detail.value = null;
  drafts.value = [];
  activePhotoIndex.value = 0;
  drawingMode.value = false;
  try {
    const response = await getLabelQcTask(factoryId.value, row.id);
    if (response.success) {
      detail.value = response.data;
      drafts.value = buildReviewDraft(response.data);
    }
  } finally {
    detailLoading.value = false;
  }
}

function selectPhoto(index: number) {
  activePhotoIndex.value = index;
  cancelDrawing();
}

function markPhotoNormal() {
  const draft = activeDraft.value;
  if (!draft) return;
  const aiItems = draft.items.filter((item) => Boolean(item.annotationId));
  if (aiItems.length > 0) {
    draft.items = aiItems.map((item) => ({
      ...item,
      label: 'NO_DEFECT',
      notes: item.notes || '人工复核：未发现缺标',
    }));
  } else {
    draft.items = [{
      key: `negative-${draft.photoId}-${Date.now()}`,
      source: 'HUMAN',
      label: 'NO_DEFECT',
      bbox: null,
      notes: '人工复核：未发现缺标',
    }];
  }
  drawingMode.value = false;
}

function removeHumanItem(item: LabelQcReviewDraft) {
  const draft = activeDraft.value;
  if (!draft || item.source !== 'HUMAN') return;
  draft.items = draft.items.filter((candidate) => candidate.key !== item.key);
}

function cancelDrawing() {
  drawingMode.value = false;
  drawStart.value = null;
  drawCurrent.value = null;
}

function pointerPosition(event: PointerEvent): { x: number; y: number } | null {
  const stage = stageRef.value;
  if (!stage) return null;
  const rect = stage.getBoundingClientRect();
  return {
    x: Math.max(0, Math.min(event.clientX - rect.left, rect.width)),
    y: Math.max(0, Math.min(event.clientY - rect.top, rect.height)),
  };
}

function startDrawing(event: PointerEvent) {
  if (!drawingMode.value || !canReview.value) return;
  const position = pointerPosition(event);
  if (!position) return;
  drawStart.value = position;
  drawCurrent.value = position;
  stageRef.value?.setPointerCapture(event.pointerId);
}

function moveDrawing(event: PointerEvent) {
  if (!drawStart.value) return;
  drawCurrent.value = pointerPosition(event);
}

function endDrawing(event: PointerEvent) {
  const stage = stageRef.value;
  const start = drawStart.value;
  const end = pointerPosition(event);
  if (!stage || !start || !end || !activeDraft.value) {
    cancelDrawing();
    return;
  }
  const rect = stage.getBoundingClientRect();
  const bbox = normalizedBox(start.x, start.y, end.x, end.y, rect.width, rect.height);
  drawStart.value = null;
  drawCurrent.value = null;
  if (!bbox) {
    ElMessage.warning('请拖出一个清晰的问题框，不要只点击图片');
    return;
  }
  appendHumanBox(activeDraft.value, bbox, `human-${Date.now()}`);
  drawingMode.value = false;
}

function boxStyle(box: LabelQcBoundingBox, label?: LabelQcLabel | null) {
  return {
    left: `${box.xMin * 100}%`,
    top: `${box.yMin * 100}%`,
    width: `${(box.xMax - box.xMin) * 100}%`,
    height: `${(box.yMax - box.yMin) * 100}%`,
    borderColor: defectColor(label),
  };
}

const previewStyle = computed(() => {
  const stage = stageRef.value;
  const start = drawStart.value;
  const current = drawCurrent.value;
  if (!stage || !start || !current) return null;
  const rect = stage.getBoundingClientRect();
  const box = normalizedBox(start.x, start.y, current.x, current.y, rect.width, rect.height);
  return box ? boxStyle(box, 'MISSING_WHITE_LABEL') : null;
});

async function submitReview() {
  if (!factoryId.value || !detail.value) return;
  const validation = validateReviewDraft(drafts.value);
  if (validation) {
    ElMessage.warning(validation);
    return;
  }
  try {
    await ElMessageBox.confirm(
      '提交后本任务将锁定为人工真值，并进入后续 YOLO 训练数据。是否确认？',
      '确认完成审核',
      {
        type: 'warning',
        confirmButtonText: '确认提交',
        cancelButtonText: '继续检查',
      },
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    const response = await reviewLabelQcTask(
      factoryId.value,
      detail.value.task.id,
      toReviewRequest(drafts.value),
    );
    if (response.success) {
      detail.value = response.data;
      ElMessage.success('人工审核已完成，结论已保存为训练真值');
      drawerVisible.value = false;
      await load();
    }
  } finally {
    submitting.value = false;
  }
}

async function retryAnalysis(row: LabelQcTaskSummary) {
  if (!factoryId.value) return;
  retryingTaskId.value = row.id;
  try {
    const response = await retryLabelQcTask(factoryId.value, row.id);
    if (response.success) {
      ElMessage.success('已重新进入 AI 初筛队列');
      await load();
    }
  } finally {
    retryingTaskId.value = null;
  }
}

async function retryCurrentTask() {
  if (!detail.value) return;
  await retryAnalysis(detail.value.task);
  drawerVisible.value = false;
}

async function exportTrainingData() {
  if (!factoryId.value) return;
  const to = new Date();
  const from = new Date(to);
  from.setDate(from.getDate() - 7);
  const response = await exportLabelQcTrainingData(factoryId.value, {
    from: from.toISOString(),
    to: to.toISOString(),
    limit: 500,
  });
  if (!response.success) return;
  const content = JSON.stringify(response.data, null, 2);
  const blob = new Blob([content], { type: 'application/json;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `label-qc-training-${to.toISOString().slice(0, 10)}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
  ElMessage.success(`已导出 ${response.data.length} 张人工审核照片`);
}

onMounted(load);
</script>

<template>
  <div class="label-qc-page">
    <header class="page-header">
      <div>
        <div class="eyebrow">QUALITY CONTROL · HUMAN IN THE LOOP</div>
        <h1>包装标签拍检</h1>
        <p>AI 负责宁可多报的初筛，人工逐张确认后才形成最终结论和训练数据。</p>
      </div>
      <div class="header-actions">
        <el-button :icon="Download" @click="exportTrainingData">导出近 7 天训练集</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="summary-grid" aria-label="任务统计">
      <article class="summary-card urgent">
        <div class="summary-icon"><Warning /></div>
        <div>
          <span>待人工审核</span>
          <strong>{{ counts.NEEDS_REVIEW ?? 0 }}</strong>
        </div>
      </article>
      <article class="summary-card">
        <div class="summary-icon"><Aim /></div>
        <div>
          <span>AI 初筛中</span>
          <strong>{{ (counts.QUEUED ?? 0) + (counts.ANALYZING ?? 0) }}</strong>
        </div>
      </article>
      <article class="summary-card success">
        <div class="summary-icon"><Check /></div>
        <div>
          <span>已人工审核</span>
          <strong>{{ counts.REVIEWED ?? 0 }}</strong>
        </div>
      </article>
    </section>

    <section class="queue-card">
      <div class="queue-toolbar">
        <div>
          <h2>审核队列</h2>
          <span>默认只看需要处理的任务</span>
        </div>
        <el-select
          v-model="selectedStatuses"
          multiple
          collapse-tags
          collapse-tags-tooltip
          placeholder="全部状态"
          style="width: 300px"
          @change="changeFilter"
        >
          <el-option
            v-for="(label, status) in STATUS_LABELS"
            :key="status"
            :label="label"
            :value="status"
          />
        </el-select>
      </div>

      <el-table v-loading="loading" :data="rows" row-key="id" class="queue-table">
        <el-table-column label="SKU / 批次" min-width="230">
          <template #default="{ row }">
            <div class="sku-cell">
              <strong>{{ row.skuName }}</strong>
              <span>{{ row.skuCode }} · {{ row.batchNumber }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="productionDate" label="生产日期" width="120" />
        <el-table-column label="照片 / AI 疑点" width="140">
          <template #default="{ row }">
            <span>{{ row.photoCount }} 张 / </span>
            <strong class="candidate-count">{{ row.aiCandidateCount }} 处</strong>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="提交时间" min-width="170">
          <template #default="{ row }">
            {{ new Date(row.createdAt).toLocaleString() }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'NEEDS_REVIEW' || row.status === 'ANALYSIS_FAILED'"
              type="primary"
              :icon="EditPen"
              @click="openReview(row)"
            >
              人工审核
            </el-button>
            <el-button v-else text @click="openReview(row)">查看</el-button>
            <el-button
              v-if="row.status === 'ANALYSIS_FAILED'"
              text
              :icon="RefreshRight"
              :loading="retryingTaskId === row.id"
              @click="retryAnalysis(row)"
            >
              重试 AI
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <Check />
            <strong>当前筛选下没有待处理任务</strong>
            <span>手机端新提交的照片会自动进入这里。</span>
          </div>
        </template>
      </el-table>

      <div class="pagination-row">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="pagination.total"
          :page-size="pagination.size"
          :current-page="pagination.page"
          @current-change="changePage"
        />
      </div>
    </section>

    <el-drawer
      v-model="drawerVisible"
      size="92%"
      direction="rtl"
      :close-on-click-modal="false"
      class="review-drawer"
      @closed="cancelDrawing"
    >
      <template #header>
        <div v-if="detail" class="drawer-header">
          <div>
            <div class="eyebrow">人工审核 · 第 {{ activePhotoIndex + 1 }}/{{ detail.photos.length }} 张</div>
            <h2>{{ detail.task.skuName }} · {{ detail.task.batchNumber }}</h2>
            <p>
              SKU {{ detail.task.skuCode }}
              <span>·</span>
              生产日期 {{ detail.task.productionDate }}
              <span>·</span>
              提交人 #{{ detail.task.createdBy }}
            </p>
          </div>
          <div class="progress-pill">逐张确认 {{ reviewProgress }}%</div>
        </div>
      </template>

      <div v-loading="detailLoading" class="review-shell">
        <template v-if="detail && activePhoto && activeDraft">
          <aside class="photo-rail" aria-label="照片列表">
            <button
              v-for="(photo, index) in detail.photos"
              :key="photo.id"
              class="photo-thumb"
              :class="{ active: index === activePhotoIndex }"
              type="button"
              @click="selectPhoto(index)"
            >
              <img v-if="photo.imageUrl" :src="photo.imageUrl" :alt="`第 ${index + 1} 张照片`">
              <span class="photo-number">{{ index + 1 }}</span>
              <span class="photo-state">
                {{ drafts[index]?.items.length ? '已给结论' : '待确认' }}
              </span>
            </button>
          </aside>

          <main class="image-workspace">
            <div class="image-toolbar">
              <div>
                <strong>第 {{ activePhotoIndex + 1 }} 张原图</strong>
                <span v-if="activePhoto.aiModel">
                  AI {{ activePhoto.aiModel }} · {{ activePhoto.promptVersion }}
                </span>
              </div>
              <div class="image-actions">
                <el-button
                  v-if="canReview"
                  :type="drawingMode ? 'danger' : 'primary'"
                  plain
                  :icon="Aim"
                  @click="drawingMode ? cancelDrawing() : drawingMode = true"
                >
                  {{ drawingMode ? '取消画框' : '补画漏检框' }}
                </el-button>
                <el-button v-if="canReview" :icon="Check" @click="markPhotoNormal">
                  标记本图正常
                </el-button>
              </div>
            </div>

            <el-alert
              v-if="activePhoto.analysisError"
              :title="activePhoto.analysisError"
              type="warning"
              show-icon
              :closable="false"
            />
            <el-button
              v-if="activePhoto.analysisError && canReview"
              class="retry-analysis"
              :icon="RefreshRight"
              :loading="retryingTaskId === detail.task.id"
              @click="retryCurrentTask"
            >
              重新执行整单 AI 初筛
            </el-button>
            <el-alert
              v-if="drawingMode"
              title="在漏贴标签的盒子位置按住并拖动，松开后生成问题框。"
              type="info"
              show-icon
              :closable="false"
            />

            <div
              ref="stageRef"
              class="image-stage"
              :class="{ drawing: drawingMode }"
              @pointerdown="startDrawing"
              @pointermove="moveDrawing"
              @pointerup="endDrawing"
              @pointercancel="cancelDrawing"
            >
              <img
                v-if="activePhoto.imageUrl"
                :src="activePhoto.imageUrl"
                alt="待审核包装标签照片"
                draggable="false"
              >
              <div
                v-for="(item, index) in activeDraft.items"
                v-show="item.bbox"
                :key="item.key"
                class="annotation-box"
                :style="item.bbox ? boxStyle(item.bbox, item.label) : undefined"
              >
                <span :style="{ backgroundColor: defectColor(item.label) }">
                  {{ index + 1 }} · {{ labelText(item.label) }}
                </span>
              </div>
              <div v-if="previewStyle" class="annotation-box preview" :style="previewStyle" />
            </div>
          </main>

          <aside class="decision-panel">
            <div class="decision-intro">
              <div>
                <h3>本图结论</h3>
                <span>{{ activeDraft.items.length }} 项</span>
              </div>
              <p>AI 只是候选；请确认每一项，漏检时在左侧补框。</p>
            </div>

            <div v-if="activeDraft.items.length === 0" class="decision-empty">
              尚未给出结论，请补画问题框或标记本图正常。
            </div>

            <article
              v-for="(item, index) in activeDraft.items"
              :key="item.key"
              class="decision-card"
              :style="{ borderLeftColor: defectColor(item.label) }"
            >
              <header>
                <strong>结论 {{ index + 1 }}</strong>
                <el-tag v-if="item.source === 'AI'" size="small" type="info">
                  AI {{ percentage(item.aiConfidence) }}
                </el-tag>
                <el-tag v-else size="small" type="success">人工补充</el-tag>
              </header>
              <p v-if="item.source === 'AI'" class="ai-origin">
                AI 原判：{{ labelText(item.aiLabel) }}
                <span v-if="item.aiEvidence">· {{ item.aiEvidence }}</span>
              </p>
              <el-radio-group v-model="item.label" class="label-choice" :disabled="!canReview">
                <el-radio-button
                  v-for="option in LABEL_OPTIONS"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}
                </el-radio-button>
              </el-radio-group>
              <el-input
                v-model="item.notes"
                type="textarea"
                :rows="2"
                maxlength="500"
                show-word-limit
                :disabled="!canReview"
                placeholder="可选：记录判断依据或返工说明"
              />
              <el-button
                v-if="canReview && item.source === 'HUMAN'"
                text
                type="danger"
                @click="removeHumanItem(item)"
              >
                删除此人工标注
              </el-button>
            </article>

            <div v-if="canReview" class="submit-area">
              <el-alert
                title="宁可多报，不可漏报；提交前请检查所有照片。"
                type="warning"
                :closable="false"
                show-icon
              />
              <el-button
                type="primary"
                size="large"
                :loading="submitting"
                @click="submitReview"
              >
                完成整单人工审核
              </el-button>
            </div>
          </aside>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.label-qc-page {
  min-height: 100%;
  padding: 24px;
  background:
    radial-gradient(circle at 92% 3%, rgba(37, 99, 235, 0.08), transparent 28%),
    #f5f7fa;
  color: #172033;
}

.page-header,
.queue-toolbar,
.drawer-header,
.image-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.page-header h1,
.drawer-header h2 {
  margin: 4px 0 6px;
  font-size: 28px;
  letter-spacing: -0.03em;
}

.page-header p,
.drawer-header p,
.queue-toolbar span,
.image-toolbar span {
  margin: 0;
  color: #657087;
}

.eyebrow {
  color: #2563eb;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.header-actions,
.image-actions {
  display: flex;
  gap: 10px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin: 24px 0 18px;
}

.summary-card {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 108px;
  padding: 20px;
  border: 1px solid #e5eaf2;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 8px 25px rgba(30, 41, 59, 0.05);
}

.summary-icon {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 12px;
  background: #eaf1ff;
  color: #2563eb;
}

.summary-icon :deep(svg) {
  width: 22px;
}

.summary-card.urgent .summary-icon {
  background: #fff3e8;
  color: #ea580c;
}

.summary-card.success .summary-icon {
  background: #eaf8ef;
  color: #16a34a;
}

.summary-card span,
.summary-card strong {
  display: block;
}

.summary-card span {
  color: #657087;
  font-size: 13px;
}

.summary-card strong {
  margin-top: 3px;
  font-size: 30px;
}

.queue-card {
  overflow: hidden;
  border: 1px solid #e3e8f0;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(30, 41, 59, 0.05);
}

.queue-toolbar {
  padding: 20px 22px;
  border-bottom: 1px solid #edf0f5;
}

.queue-toolbar h2 {
  margin: 0 0 4px;
  font-size: 18px;
}

.queue-table {
  width: 100%;
}

.sku-cell strong,
.sku-cell span {
  display: block;
}

.sku-cell span {
  margin-top: 4px;
  color: #69748a;
  font-size: 12px;
}

.candidate-count {
  color: #dc2626;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  padding: 18px 22px;
}

.empty-state {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 36px;
  color: #7a8496;
}

.empty-state :deep(svg) {
  width: 34px;
  color: #16a34a;
}

.drawer-header {
  width: 100%;
  padding-right: 32px;
}

.drawer-header h2 {
  font-size: 22px;
}

.drawer-header p span {
  margin: 0 8px;
}

.progress-pill {
  padding: 9px 14px;
  border-radius: 999px;
  background: #eaf1ff;
  color: #1d4ed8;
  font-weight: 700;
}

.review-shell {
  min-height: calc(100vh - 100px);
}

.review-shell {
  height: 100%;
}

.review-shell {
  display: grid;
  grid-template-columns: 116px minmax(520px, 1fr) minmax(340px, 430px);
  gap: 16px;
  padding: 0 20px 20px;
}

.photo-rail,
.decision-panel,
.image-workspace {
  min-height: 0;
  overflow-y: auto;
}

.photo-rail {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.photo-thumb {
  position: relative;
  overflow: hidden;
  padding: 0;
  border: 2px solid transparent;
  border-radius: 10px;
  background: #eef1f5;
  cursor: pointer;
}

.photo-thumb.active {
  border-color: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.13);
}

.photo-thumb img {
  display: block;
  width: 100%;
  aspect-ratio: 3 / 4;
  object-fit: cover;
}

.photo-number,
.photo-state {
  position: absolute;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.82);
  color: #fff;
  font-size: 11px;
}

.photo-number {
  top: 6px;
  left: 6px;
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
}

.photo-state {
  right: 5px;
  bottom: 5px;
  padding: 3px 7px;
}

.image-workspace {
  padding: 16px;
  border: 1px solid #e3e8f0;
  border-radius: 14px;
  background: #f8fafc;
}

.image-toolbar {
  margin-bottom: 12px;
}

.image-toolbar strong,
.image-toolbar span {
  display: block;
}

.image-toolbar span {
  margin-top: 4px;
  font-size: 12px;
}

.image-stage {
  position: relative;
  width: 100%;
  margin-top: 12px;
  overflow: hidden;
  border-radius: 10px;
  background: #111827;
  user-select: none;
  touch-action: none;
}

.retry-analysis {
  margin-top: 10px;
}

.image-stage.drawing {
  cursor: crosshair;
}

.image-stage > img {
  display: block;
  width: 100%;
  height: auto;
  pointer-events: none;
}

.annotation-box {
  position: absolute;
  z-index: 2;
  border: 3px solid;
  border-radius: 4px;
  pointer-events: none;
}

.annotation-box > span {
  position: absolute;
  top: -27px;
  left: -3px;
  min-width: max-content;
  padding: 4px 7px;
  border-radius: 4px 4px 4px 0;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}

.annotation-box.preview {
  z-index: 3;
  border-style: dashed;
  background: rgba(220, 38, 38, 0.12);
}

.decision-panel {
  padding: 16px;
  border: 1px solid #e3e8f0;
  border-radius: 14px;
  background: #fff;
}

.decision-intro > div,
.decision-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.decision-intro h3 {
  margin: 0;
}

.decision-intro p,
.ai-origin {
  color: #69748a;
  font-size: 12px;
  line-height: 1.6;
}

.decision-card {
  margin-top: 12px;
  padding: 14px;
  border: 1px solid #e6eaf0;
  border-left: 4px solid;
  border-radius: 10px;
}

.label-choice {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
  margin: 10px 0;
}

.label-choice :deep(.el-radio-button__inner) {
  width: 100%;
  border: 1px solid #d9deea;
  border-radius: 7px !important;
  box-shadow: none !important;
}

.decision-empty {
  margin-top: 12px;
  padding: 20px;
  border: 1px dashed #cbd5e1;
  border-radius: 10px;
  color: #64748b;
  text-align: center;
}

.submit-area {
  position: sticky;
  bottom: 0;
  display: grid;
  gap: 10px;
  margin-top: 16px;
  padding-top: 12px;
  background: #fff;
}

@media (max-width: 1200px) {
  .review-shell {
    grid-template-columns: 90px minmax(460px, 1fr) 340px;
  }
}

@media (max-width: 900px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }

  .page-header,
  .queue-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .review-shell {
    display: block;
  }

  .photo-rail {
    flex-direction: row;
    margin-bottom: 12px;
    overflow-x: auto;
  }

  .photo-thumb {
    min-width: 84px;
  }

  .decision-panel {
    margin-top: 12px;
  }
}
</style>
