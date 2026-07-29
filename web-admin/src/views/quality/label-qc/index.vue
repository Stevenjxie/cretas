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
  archiveLabelQcTask,
  backupLabelQcTask,
  decideLabelQcTraining,
  exportLabelQcTrainingData,
  getLabelQcStatusCounts,
  getLabelQcTask,
  listLabelQcTasks,
  retryLabelQcTask,
  restoreLabelQcTask,
  reviewLabelQcTask,
  type LabelQcReviewRequest,
  type LabelQcTaskDetail,
  type LabelQcTaskStatus,
  type LabelQcTaskSummary,
} from '@/api/labelQc';
import LabelQcReviewWorkbench from './LabelQcReviewWorkbench.vue';

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
const submitting = ref(false);
const retryingTaskId = ref<string | null>(null);
const reviewDirty = ref(false);
const allowDrawerClose = ref(false);
const queueMode = ref<'REVIEW' | 'MANAGE' | 'ARCHIVED'>('REVIEW');
const actionTaskId = ref<string | null>(null);

const canManageTraining = computed(() => {
  const currentUser = authStore.user;
  return currentUser?.userType === 'factory'
    && currentUser.factoryUser.permissions.includes('system:read_write');
});

const canReview = computed(() => {
  const status = detail.value?.task.status;
  return status === 'NEEDS_REVIEW' || status === 'ANALYSIS_FAILED';
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
        archived: queueMode.value === 'ARCHIVED',
        page: pagination.value.page,
        size: pagination.value.size,
      }),
      getLabelQcStatusCounts(factoryId.value),
    ]);
    if (listResponse.success) {
      rows.value = listResponse.data.content;
      pagination.value.total = listResponse.data.totalElements;
    }
    if (countResponse.success) counts.value = countResponse.data.counts;
  } finally {
    loading.value = false;
  }
}

function changeFilter() {
  pagination.value.page = 1;
  void load();
}

function changeQueueMode(value: string | number | boolean | undefined) {
  const mode = String(value) as 'REVIEW' | 'MANAGE' | 'ARCHIVED';
  queueMode.value = mode;
  selectedStatuses.value = mode === 'REVIEW'
    ? ['NEEDS_REVIEW', 'ANALYSIS_FAILED']
    : ['REVIEWED'];
  changeFilter();
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

async function openReview(row: LabelQcTaskSummary) {
  if (!factoryId.value) return;
  reviewDirty.value = false;
  allowDrawerClose.value = false;
  drawerVisible.value = true;
  detailLoading.value = true;
  detail.value = null;
  try {
    const response = await getLabelQcTask(factoryId.value, row.id);
    if (response.success) detail.value = response.data;
  } finally {
    detailLoading.value = false;
  }
}

async function submitReview(payload: LabelQcReviewRequest) {
  if (!factoryId.value || !detail.value) return;
  try {
    await ElMessageBox.confirm(
      `即将提交 ${payload.photos.length} 张照片的人工真值。提交后仍需技术管理员确认，才会进入训练集。`,
      '确认完成整单审核',
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
      {
        ...payload,
        expectedVersion: detail.value.task.version,
        reviewRequestId: globalThis.crypto?.randomUUID?.()
          ?? `web-${detail.value.task.id}-${Date.now()}`,
      },
    );
    if (response.success) {
      const finishedId = detail.value.task.id;
      detail.value = response.data;
      reviewDirty.value = false;
      allowDrawerClose.value = true;
      await load();

      // 连审：一批做完直接进下一批，不用退回列表再点。审核几十批时这一步
      // 省掉的往返比看起来多。没有下一批才关抽屉。
      // 只在“待人工审核”队列里连审；管理/归档模式下保持原来的关闭行为。
      const next = queueMode.value === 'REVIEW'
        ? rows.value.find((row) => row.id !== finishedId && row.status === 'NEEDS_REVIEW')
        : undefined;
      if (next) {
        ElMessage.success(`本批已完成，继续下一批：${next.batchNumber}`);
        await openReview(next);
      } else {
        drawerVisible.value = false;
        ElMessage.success('人工审核已完成，待审队列已清空');
      }
    }
  } finally {
    submitting.value = false;
  }
}

async function archiveTask(row: LabelQcTaskSummary) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(
      '归档不会删除照片和标注，之后可以在“归档记录”中恢复。',
      '确认归档这条任务？',
      {
        type: 'warning',
        confirmButtonText: '确认归档',
        cancelButtonText: '取消',
      },
    );
  } catch {
    return;
  }
  actionTaskId.value = row.id;
  try {
    const response = await archiveLabelQcTask(factoryId.value, row.id);
    if (response.success) {
      ElMessage.success('已归档，照片和人工标注均已保留');
      await load();
    }
  } finally {
    actionTaskId.value = null;
  }
}

async function restoreTask(row: LabelQcTaskSummary) {
  if (!factoryId.value) return;
  actionTaskId.value = row.id;
  try {
    const response = await restoreLabelQcTask(factoryId.value, row.id);
    if (response.success) {
      ElMessage.success('已恢复到“已审核整理”');
      await load();
    }
  } finally {
    actionTaskId.value = null;
  }
}

function downloadJson(payload: unknown, filename: string) {
  const content = JSON.stringify(payload, null, 2);
  const blob = new Blob([content], { type: 'application/json;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

async function backupTask(row: LabelQcTaskSummary) {
  if (!factoryId.value) return;
  actionTaskId.value = row.id;
  try {
    const response = await backupLabelQcTask(factoryId.value, row.id);
    if (!response.success) return;
    downloadJson(
      response.data,
      `label-qc-backup-${row.skuCode}-${row.batchNumber}.json`,
    );
    ElMessage.success('完整备份已下载，并记录本次备份时间');
    await load();
  } finally {
    actionTaskId.value = null;
  }
}

async function decideTraining(row: LabelQcTaskSummary, approved: boolean) {
  if (!factoryId.value || !canManageTraining.value) return;
  try {
    await ElMessageBox.confirm(
      approved
        ? '批准后，这条人工真值会进入技术管理员可导出的训练集。此操作不会自动训练或发布模型。'
        : '拒绝后，这条数据仍会保留，但不会进入训练集。',
      approved ? '确认进入训练集？' : '确认拒绝训练？',
      {
        type: approved ? 'warning' : 'info',
        confirmButtonText: approved ? '确认批准' : '确认拒绝',
        cancelButtonText: '取消',
      },
    );
  } catch {
    return;
  }
  actionTaskId.value = row.id;
  try {
    const response = await decideLabelQcTraining(factoryId.value, row.id, {
      approved,
      expectedVersion: row.version,
      notes: approved ? '技术管理员已复核' : '技术管理员拒绝进入训练集',
    });
    if (response.success) {
      ElMessage.success(approved ? '已批准进入训练集' : '已拒绝进入训练集');
      await load();
    }
  } finally {
    actionTaskId.value = null;
  }
}

async function retryAnalysis(row: LabelQcTaskSummary): Promise<boolean> {
  if (!factoryId.value) return false;
  retryingTaskId.value = row.id;
  try {
    const response = await retryLabelQcTask(factoryId.value, row.id);
    if (response.success) {
      ElMessage.success('已重新进入 AI 初筛队列');
      await load();
      return true;
    }
    return false;
  } finally {
    retryingTaskId.value = null;
  }
}

async function retryCurrentTask() {
  if (!detail.value) return;
  if (reviewDirty.value) {
    try {
      await ElMessageBox.confirm(
        '重新执行 AI 初筛会放弃当前尚未提交的人工标注。',
        '确认重新初筛？',
        {
          type: 'warning',
          confirmButtonText: '放弃草稿并重试',
          cancelButtonText: '继续审核',
        },
      );
    } catch {
      return;
    }
  }
  const retried = await retryAnalysis(detail.value.task);
  if (!retried) return;
  reviewDirty.value = false;
  allowDrawerClose.value = true;
  drawerVisible.value = false;
}

function updateReviewDirty(dirty: boolean): void {
  reviewDirty.value = dirty;
}

async function handleReviewBeforeClose(done: () => void): Promise<void> {
  if (allowDrawerClose.value) {
    allowDrawerClose.value = false;
    reviewDirty.value = false;
    done();
    return;
  }
  if (!reviewDirty.value) {
    done();
    return;
  }
  try {
    await ElMessageBox.confirm(
      '当前还有未提交的人工标注，关闭后本次修改会丢失。',
      '放弃未提交草稿？',
      {
        type: 'warning',
        confirmButtonText: '放弃草稿',
        cancelButtonText: '继续审核',
        distinguishCancelAndClose: true,
      },
    );
    reviewDirty.value = false;
    done();
  } catch {
    // 保留抽屉和当前标注，审核员可以继续操作。
  }
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
  downloadJson(
    response.data,
    `label-qc-training-approved-${to.toISOString().slice(0, 10)}.json`,
  );
  ElMessage.success(`已导出 ${response.data.length} 张已批准训练照片`);
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
        <el-button
          v-if="canManageTraining"
          :icon="Download"
          @click="exportTrainingData"
        >
          导出已批准训练集
        </el-button>
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
          <h2>
            {{ queueMode === 'REVIEW' ? '审核队列' : queueMode === 'MANAGE' ? '已审核整理' : '归档记录' }}
          </h2>
          <span>
            {{
              queueMode === 'REVIEW'
                ? '默认只看需要处理的任务'
                : queueMode === 'MANAGE'
                  ? '备份、归档，并由技术管理员确认训练'
                  : '归档不删除，可随时恢复'
            }}
          </span>
        </div>
        <div class="queue-controls">
          <el-radio-group
            v-model="queueMode"
            size="large"
            @change="changeQueueMode"
          >
            <el-radio-button value="REVIEW">待人工审核</el-radio-button>
            <el-radio-button value="MANAGE">已审核整理</el-radio-button>
            <el-radio-button value="ARCHIVED">归档记录</el-radio-button>
          </el-radio-group>
          <el-select
            v-if="queueMode === 'REVIEW'"
            v-model="selectedStatuses"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="处理状态"
            style="width: 240px"
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
      </div>

      <el-table v-loading="loading" :data="rows" row-key="id" class="queue-table">
        <el-table-column label="SKU / 批次" min-width="210">
          <template #default="{ row }">
            <div class="sku-cell">
              <strong>{{ row.skuName }}</strong>
              <span>{{ row.skuCode }} · {{ row.batchNumber }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="productionDate" label="生产日期" width="105" />
        <el-table-column label="照片 / AI 疑点" width="120">
          <template #default="{ row }">
            <span>{{ row.photoCount }} 张 / </span>
            <strong class="candidate-count">{{ row.aiCandidateCount }} 处</strong>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="95">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          v-if="queueMode !== 'REVIEW'"
          label="训练确认"
          width="110"
        >
          <template #default="{ row }">
            <el-tag
              :type="
                row.trainingStatus === 'APPROVED'
                  ? 'success'
                  : row.trainingStatus === 'REJECTED'
                    ? 'danger'
                    : 'warning'
              "
              effect="plain"
            >
              {{
                row.trainingStatus === 'APPROVED'
                  ? '已批准'
                  : row.trainingStatus === 'REJECTED'
                    ? '已拒绝'
                    : '待技术确认'
              }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="提交时间" min-width="145">
          <template #default="{ row }">
            {{ new Date(row.createdAt).toLocaleString() }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          :width="queueMode === 'REVIEW' ? 210 : 330"
          fixed="right"
        >
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
            <template v-if="row.status === 'REVIEWED'">
              <el-button
                text
                :loading="actionTaskId === row.id"
                @click="backupTask(row)"
              >
                下载备份
              </el-button>
              <el-button
                v-if="queueMode !== 'ARCHIVED'"
                text
                type="warning"
                :loading="actionTaskId === row.id"
                @click="archiveTask(row)"
              >
                归档
              </el-button>
              <el-button
                v-else
                text
                type="primary"
                :loading="actionTaskId === row.id"
                @click="restoreTask(row)"
              >
                恢复
              </el-button>
              <el-button
                v-if="canManageTraining && row.trainingStatus !== 'APPROVED'"
                text
                type="success"
                :loading="actionTaskId === row.id"
                @click="decideTraining(row, true)"
              >
                批准训练
              </el-button>
              <el-button
                v-if="canManageTraining && row.trainingStatus !== 'REJECTED'"
                text
                type="danger"
                :loading="actionTaskId === row.id"
                @click="decideTraining(row, false)"
              >
                拒绝训练
              </el-button>
            </template>
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
      size="96%"
      direction="rtl"
      :close-on-click-modal="false"
      :destroy-on-close="true"
      :before-close="handleReviewBeforeClose"
      class="review-drawer"
    >
      <template #header>
        <div class="drawer-heading">
          <div>
            <strong>逐张人工审核</strong>
            <span>每个 AI 疑点必须确认或拒绝，每张照片必须给出整图结论</span>
          </div>
          <div class="drawer-statuses">
            <div v-if="reviewDirty" class="draft-status">● 未提交草稿</div>
            <div class="review-rule">宁可多报 · 不可漏报</div>
          </div>
        </div>
      </template>

      <div v-loading="detailLoading" class="drawer-body">
        <LabelQcReviewWorkbench
          v-if="detail"
          :detail="detail"
          :can-review="canReview"
          :submitting="submitting"
          :retrying="retryingTaskId === detail.task.id"
          @submit="submitReview"
          @retry="retryCurrentTask"
          @dirty-change="updateReviewDirty"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.label-qc-page {
  min-height: 100%;
  padding: 24px;
  color: #172a23;
  background:
    linear-gradient(135deg, rgba(0, 169, 135, .045), transparent 32%),
    #f4f6f3;
}

.page-header,
.queue-toolbar,
.drawer-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.page-header h1 {
  margin: 4px 0 6px;
  font-size: 28px;
  letter-spacing: -.03em;
}

.page-header p,
.queue-toolbar span {
  margin: 0;
  color: #65736e;
}

.eyebrow {
  color: #08745f;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: .08em;
}

.header-actions {
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
  min-height: 108px;
  align-items: center;
  gap: 14px;
  padding: 20px;
  border: 1px solid #dfe7e2;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 8px 25px rgba(24, 46, 38, .05);
}

.summary-icon {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 12px;
  color: #08745f;
  background: #e3f6ef;
}

.summary-icon :deep(svg) {
  width: 22px;
}

.summary-card.urgent .summary-icon {
  color: #b86600;
  background: #fff1d8;
}

.summary-card.success .summary-icon {
  color: #08745f;
  background: #def6ec;
}

.summary-card span,
.summary-card strong {
  display: block;
}

.summary-card span {
  color: #65736e;
  font-size: 13px;
}

.summary-card strong {
  margin-top: 3px;
  font-size: 30px;
}

.queue-card {
  overflow: hidden;
  border: 1px solid #dfe7e2;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(24, 46, 38, .05);
}

.queue-toolbar {
  padding: 20px 22px;
  border-bottom: 1px solid #edf1ee;
}

.queue-toolbar h2 {
  margin: 0 0 4px;
  font-size: 18px;
}

.queue-controls {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
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
  color: #69766f;
  font-size: 12px;
}

.candidate-count {
  color: #c25e00;
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
  color: #7a8781;
}

.empty-state :deep(svg) {
  width: 34px;
  color: #00a987;
}

.drawer-heading {
  width: 100%;
  padding-right: 28px;
}

.drawer-heading > div:first-child {
  display: grid;
  gap: 3px;
}

.drawer-heading strong {
  color: #172a23;
  font-size: 18px;
}

.drawer-heading span {
  color: #68766f;
  font-size: 12px;
}

.review-rule {
  padding: 7px 11px;
  border: 1px solid #f1c47c;
  border-radius: 8px;
  color: #965600;
  background: #fff7e7;
  font-size: 12px;
  font-weight: 800;
}

.drawer-statuses {
  display: flex;
  align-items: center;
  gap: 8px;
}

.draft-status {
  padding: 7px 11px;
  border: 1px solid #efb3ad;
  border-radius: 8px;
  color: #a83d34;
  background: #fff1ef;
  font-size: 12px;
  font-weight: 800;
}

.drawer-body {
  height: 100%;
  min-height: 400px;
}

:deep(.review-drawer .el-drawer__header) {
  height: 62px;
  margin: 0;
  padding: 10px 18px;
  border-bottom: 1px solid #dfe7e2;
}

:deep(.review-drawer .el-drawer__body) {
  overflow: hidden;
  padding: 0;
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

  .queue-controls {
    align-items: stretch;
    justify-content: flex-start;
  }
}
</style>
