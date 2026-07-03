<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Check, Close, Refresh } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import {
  getPendingApprovals, approveReport, rejectReport, batchApproveReports,
  type ApprovalItem
} from '@/api/processProduction';
import OpinionInputDialog from '@/components/approval/OpinionInputDialog.vue';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));

const loading = ref(false);
const tableData = ref<ApprovalItem[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const selectedIds = ref<number[]>([]);

// P2-6: Auto-refresh every 30 seconds
let refreshTimer: ReturnType<typeof setInterval> | null = null;
onMounted(() => {
  loadData();
  refreshTimer = setInterval(loadData, 30000);
});
onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer);
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await getPendingApprovals(factoryId.value, {
      page: pagination.value.page,
      size: pagination.value.size
    });
    if (response.success && response.data) {
      tableData.value = (response.data.content || []) as ApprovalItem[];
      pagination.value.total = response.data.totalElements || 0;
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor toast already covers 4xx/5xx.
    handleCatchError(e, '加载待审批列表失败,请检查网络');
  } finally {
    loading.value = false;
  }
}

// 报工审批"通过"防呆 (fool-proof-design.md Rule 1+2+4): 原先点击即提交, 零上下文零确认,
// 且产品/工序列曾为空白 → 对批量盲审的误点零容错(审批结果影响成本/工资下游核算, 不可逆)。
// 改为: 点击"通过"只打开确认弹框(展示完整上下文), 真正提交挪到 confirmApprove(), 并用
// approveSubmitting 防重复点击(Rule 4 幂等 UX 层 — 弹框内确认按钮 disabled+loading)。
const approveDialogVisible = ref(false);
const approveTargetRow = ref<ApprovalItem | null>(null);
const approveSubmitting = ref(false);

function handleApprove(row: ApprovalItem) {
  if (!factoryId.value) return;
  approveTargetRow.value = row;
  approveDialogVisible.value = true;
}

async function confirmApprove() {
  const row = approveTargetRow.value;
  // approveSubmitting 兜底: 弹框确认按钮已 :loading disabled, 但仍防御双击竞态.
  if (!factoryId.value || !row || approveSubmitting.value) return;
  approveSubmitting.value = true;
  try {
    await approveReport(factoryId.value, row.id);
    ElMessage.success('已通过');
    approveDialogVisible.value = false;
    approveTargetRow.value = null;
    loadData();
  } catch (e) {
    // R26 P1 (qa-prompt v2.4 Rule 7+8 real-window finding): pre-fix this catch
    // unconditionally fired ElMessage.error('审批失败') on top of axios interceptor's
    // rich actionHint toast → user saw BOTH "审批失败" + "请刷新报工列表查看最新审批状态".
    // Per R24 follow-up pattern: skip fallback when interceptor handled (rich error
    // identifiable by err.actionHint OR err.status === 409 — both routes axios already toasted).
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && !err.actionHint)) {
      ElMessage.error('审批失败');
    }
    // R26 follow-up (reviewer #16 concern #4): only reload on 409 (already-processed
    // race — backend state diverged from UI cache). On 502/network error, loadData()
    // would just retry-and-fail too, doubling failed-request rate during outage.
    if (err?.status === 409) {
      approveDialogVisible.value = false;
      approveTargetRow.value = null;
      loadData();
    }
  } finally {
    approveSubmitting.value = false;
  }
}

function cancelApprove() {
  if (approveSubmitting.value) return;
  approveDialogVisible.value = false;
  approveTargetRow.value = null;
}

// C-OPINION-1: 弹框 state + pending row reference (替代 ElMessageBox.prompt)
const rejectDialogVisible = ref(false);
const rejectTargetRow = ref<ApprovalItem | null>(null);

function handleReject(row: ApprovalItem) {
  if (!factoryId.value) return;
  rejectTargetRow.value = row;
  rejectDialogVisible.value = true;
}

async function handleRejectConfirm(reason: string) {
  const row = rejectTargetRow.value;
  if (!factoryId.value || !row) return;
  try {
    await rejectReport(factoryId.value, row.id, reason);
    ElMessage.success('已驳回');
    loadData();
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    if (e !== 'cancel') console.error('[操作失败]', e);
  } finally {
    rejectTargetRow.value = null;
  }
}

// Rule 4 幂等 UX 层: 批量确认本身已由 ElMessageBox.confirm 挡下误点; batchApproveSubmitting
// 额外防"确认对话框关闭动画期间"的第二次点击(慢网络下 el-dialog 的关闭过渡可能让按钮短暂
// 仍可点击)。
const batchApproveSubmitting = ref(false);

async function handleBatchApprove() {
  if (!factoryId.value || selectedIds.value.length === 0 || batchApproveSubmitting.value) return;
  try {
    await ElMessageBox.confirm(
      `确定批量通过 ${selectedIds.value.length} 条报工记录？`,
      '批量审批',
      { type: 'warning', confirmButtonText: '确认批量审批', cancelButtonText: '取消' }
    );
    batchApproveSubmitting.value = true;
    await batchApproveReports(factoryId.value, selectedIds.value);
    ElMessage.success(`已批量通过 ${selectedIds.value.length} 条`);
    selectedIds.value = [];
    loadData();
  } catch (e) {
    if (e === 'cancel') return;
    // R26 P1: skip fallback when interceptor handled rich error (same gate as handleApprove).
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && !err.actionHint)) {
      ElMessage.error('批量审批失败');
    }
    // R26 follow-up (reviewer #16 concern #4): only reload on 409 race.
    if (err?.status === 409) loadData();
  } finally {
    batchApproveSubmitting.value = false;
  }
}

function handleSelectionChange(rows: ApprovalItem[]) {
  selectedIds.value = rows.map(r => r.id);
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-';
  return dateStr.substring(0, 10);
}

/**
 * Gap 2 补录时效防呆: 计算报工日期距今天数 (负数 = 过去).
 * 后端 BackdateWindowValidator 默认 T-2 极限, T-3+ 锁死.
 */
function backdateDays(reportDate: string | null): number | null {
  if (!reportDate) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const d = new Date(reportDate.substring(0, 10));
  d.setHours(0, 0, 0, 0);
  const diff = Math.round((d.getTime() - today.getTime()) / 86400000);
  return diff;
}

/** T-2 极限: diff === -1 or -2 = 补录窗口内; diff <= -3 = 锁死; diff >= 0 = 当日/未来 */
function backdateTagType(reportDate: string | null): 'warning' | 'danger' | '' {
  const diff = backdateDays(reportDate);
  if (diff === null || diff >= 0) return '';
  if (diff >= -2) return 'warning'; // T-1 ~ T-2: 允许补录
  return 'danger'; // T-3+: 后端会拒绝
}

function backdateTagText(reportDate: string | null): string {
  const diff = backdateDays(reportDate);
  if (diff === null || diff >= 0) return '';
  if (diff === -1) return '补录 T-1';
  if (diff === -2) return '补录 T-2 极限';
  return `补录 T${diff} 已锁`;
}

function formatQty(value: number | null | undefined) {
  if (value === null || value === undefined) return '-';
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

function formatTime(value: string | null | undefined) {
  if (!value) return '-';
  return String(value).substring(0, 5);
}

function formatTimeRange(row: ApprovalItem) {
  const start = formatTime(row.productionStartTime);
  const end = formatTime(row.productionEndTime);
  if (start === '-' && end === '-') return '-';
  return `${start} - ${end}`;
}

function reportModeLabel(mode: string | null | undefined) {
  if (mode === 'MODE_1') return '按工序';
  if (mode === 'MODE_2') return '按批次';
  if (mode === 'MODE_3') return '按人头';
  return mode || '-';
}

function reportKindLabel(kind: string | null | undefined) {
  if (kind === 'INPUT') return '投入';
  if (kind === 'SEGMENT') return '过程';
  if (kind === 'OUTPUT') return '完工';
  return kind || '整合报工';
}

function taskDisplayId(row: ApprovalItem) {
  return row.processTaskId || row.workProcessTaskId || '-';
}

function textValue(value: unknown) {
  if (value === null || value === undefined || String(value).trim() === '') return '-';
  return String(value);
}

function segmentQty(seg: Record<string, unknown>, qtyKey: string, unitKey: string) {
  const qty = seg[qtyKey];
  if (qty === null || qty === undefined || String(qty).trim() === '') return '-';
  return `${formatQty(Number(qty))} ${textValue(seg[unitKey]) === '-' ? '' : textValue(seg[unitKey])}`.trim();
}

function hasProcessDetail(row: ApprovalItem) {
  return (row.laborSegments?.length ?? 0) > 0
    || (row.byproducts?.length ?? 0) > 0
    || row.wasteQuantity !== null && row.wasteQuantity !== undefined
    || row.sampleRetainQuantity !== null && row.sampleRetainQuantity !== undefined;
}

const customFieldLabels: Record<string, string> = {
  evidenceWorkflow: '录入口径',
  batchNumber: '生产批次',
  sampleBoxes: '留样盒数',
  remainingBoxes: '剩余盒数',
  trimWeightKg: '料头/损耗kg',
  byproductText: '副产物说明',
  upstreamInputKg: '上游投入kg',
  upstreamOutputKg: '上游产出kg',
  grossWeightKg: '毛重kg',
  tareWeightKg: '皮重kg',
  netWeightKg: '净重kg',
  laborSegmentsText: '多时段人工',
  sourceExcel: '来源订单',
  evidenceFiles: '原始证据文件',
};

function customFieldLabel(key: string) {
  return customFieldLabels[key] || key;
}

function formatCustomFieldValue(value: unknown) {
  if (value === null || value === undefined) return '-';
  if (typeof value === 'number') {
    return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
  }
  if (typeof value === 'string') {
    return value === 'TEXT_INPUT_WITH_MEDIA_EVIDENCE' ? '文字录入 + 照片/视频证据' : value;
  }
  return JSON.stringify(value);
}

function customFieldEntries(row: ApprovalItem) {
  if (!row.customFields || Object.keys(row.customFields).length === 0) {
    return [] as Array<{ key: string; label: string; value: string }>;
  }
  return Object.entries(row.customFields)
    .filter(([, value]) => value !== null && value !== undefined && String(value).trim() !== '')
    .map(([key, value]) => ({
      key,
      label: customFieldLabel(key),
      value: formatCustomFieldValue(value),
    }));
}

function isVideoEvidence(url: string): boolean {
  return /\.(mp4|mov|webm)(\?|#|$)/i.test(url) || url.includes('/videos/');
}

function evidenceImages(urls: string[]): string[] {
  return urls.filter((url) => !isVideoEvidence(url));
}

function evidenceImageIndex(urls: string[], url: string): number {
  const idx = evidenceImages(urls).indexOf(url);
  return idx < 0 ? 0 : idx;
}
</script>

<template>
  <div class="page-container">
    <el-card>
      <div class="toolbar">
        <div class="toolbar-left">
          <h2 style="margin: 0">报工审批</h2>
          <el-tag type="warning">待审批 {{ pagination.total }}</el-tag>
        </div>
        <div class="toolbar-right">
          <el-button :icon="Refresh" @click="loadData" />
          <el-button
            v-if="canWrite && selectedIds.length > 0"
            type="success"
            :icon="Check"
            :loading="batchApproveSubmitting"
            :disabled="batchApproveSubmitting"
            @click="handleBatchApprove"
          >
            批量通过 ({{ selectedIds.length }})
          </el-button>
        </div>
      </div>
    </el-card>

    <el-card style="margin-top: 16px">
      <el-table
        :data="tableData"
        v-loading="loading"
        row-key="id"
        stripe
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="50" />
        <el-table-column type="expand" width="44">
          <template #default="{ row }">
            <div class="report-detail">
              <el-descriptions :column="3" border size="small">
                <el-descriptions-item label="产品">{{ row.productName || '-' }}</el-descriptions-item>
                <el-descriptions-item label="投入量">{{ formatQty(row.inputQuantity) }}</el-descriptions-item>
                <el-descriptions-item label="产出量">{{ formatQty(row.outputQuantity) }}</el-descriptions-item>
                <el-descriptions-item label="人数">{{ row.totalWorkers ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="工时分钟">{{ row.totalWorkMinutes ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="工序时间">{{ formatTimeRange(row) }}</el-descriptions-item>
                <el-descriptions-item label="报工模式">{{ reportModeLabel(row.reportMode) }}</el-descriptions-item>
                <el-descriptions-item label="报工阶段">{{ reportKindLabel(row.reportKind) }}</el-descriptions-item>
                <el-descriptions-item label="来源WIP">{{ row.sourceWipNo || '-' }}</el-descriptions-item>
                <el-descriptions-item label="任务ID">{{ taskDisplayId(row) }}</el-descriptions-item>
                <el-descriptions-item label="证据数量">{{ row.photos?.length ?? 0 }}</el-descriptions-item>
                <el-descriptions-item label="备注" :span="3">{{ row.notes || '-' }}</el-descriptions-item>
                <el-descriptions-item v-if="hasProcessDetail(row)" label="过程报工明细" :span="3">
                  <div class="process-detail-block">
                    <div v-if="row.laborSegments?.length" class="process-segments">
                      <div
                        v-for="(seg, idx) in row.laborSegments"
                        :key="idx"
                        class="process-segment-line"
                      >
                        <el-tag size="small" effect="plain">第 {{ idx + 1 }} 段</el-tag>
                        <span>{{ textValue(seg.startTime) }}~{{ textValue(seg.endTime) }}</span>
                        <span>{{ textValue(seg.headcount) }} 人</span>
                        <span>处理 {{ segmentQty(seg, 'processedQuantity', 'processedUnit') }}</span>
                        <span>阶段产出 {{ segmentQty(seg, 'stageOutputQuantity', 'stageOutputUnit') }}</span>
                        <span>过程损耗 {{ segmentQty(seg, 'segmentWasteQuantity', 'segmentWasteUnit') }}</span>
                        <span v-if="seg.note">备注 {{ textValue(seg.note) }}</span>
                      </div>
                    </div>
                    <div v-if="row.byproducts?.length" class="custom-field-tags">
                      <el-tag
                        v-for="(bp, idx) in row.byproducts"
                        :key="idx"
                        type="warning"
                        effect="plain"
                        class="custom-field-tag"
                      >
                        副产物 {{ textValue(bp.name) }}: {{ formatQty(Number(bp.quantity)) }} {{ textValue(bp.unit) }}
                      </el-tag>
                    </div>
                    <div class="custom-field-tags">
                      <el-tag v-if="row.wasteQuantity !== null && row.wasteQuantity !== undefined" type="danger" effect="plain" class="custom-field-tag">
                        完工损耗: {{ formatQty(row.wasteQuantity) }}
                      </el-tag>
                      <el-tag v-if="row.sampleRetainQuantity !== null && row.sampleRetainQuantity !== undefined" type="info" effect="plain" class="custom-field-tag">
                        留样: {{ row.sampleRetainQuantity }}
                      </el-tag>
                    </div>
                  </div>
                </el-descriptions-item>
                <el-descriptions-item label="文字录入明细" :span="3">
                  <div v-if="customFieldEntries(row).length" class="custom-field-tags">
                    <el-tag
                      v-for="item in customFieldEntries(row)"
                      :key="item.key"
                      type="info"
                      effect="plain"
                      class="custom-field-tag"
                    >
                      {{ item.label }}: {{ item.value }}
                    </el-tag>
                  </div>
                  <span v-else>-</span>
                </el-descriptions-item>
              </el-descriptions>
              <div class="evidence-panel">
                <div class="evidence-title">现场证据</div>
                <div v-if="row.photos && row.photos.length" class="evidence-media">
                  <template v-for="(url, i) in row.photos" :key="i">
                    <video
                      v-if="isVideoEvidence(url)"
                      :src="url"
                      class="evidence-item"
                      controls
                    />
                    <el-image
                      v-else
                      :src="url"
                      fit="cover"
                      :preview-src-list="evidenceImages(row.photos)"
                      :initial-index="evidenceImageIndex(row.photos, url)"
                      class="evidence-item"
                      preview-teleported
                    />
                  </template>
                </div>
                <div v-else class="evidence-empty">暂无现场证据</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="reporterName" label="报工人" width="100" />
        <el-table-column prop="reportDate" label="报工日期" width="160">
          <template #default="{ row }">
            <div style="display:flex;flex-direction:column;gap:4px;align-items:flex-start">
              <span>{{ formatDate(row.reportDate) }}</span>
              <el-tooltip
                v-if="backdateTagType(row.reportDate)"
                :content="backdateTagType(row.reportDate) === 'danger'
                  ? 'T-3 及更早已锁死，后端将拒绝此报工。请联系主管通过 ERP 后台处理。'
                  : 'T-1 ~ T-2 补录窗口内，可正常审批。'"
                placement="top"
              >
                <el-tag
                  :type="backdateTagType(row.reportDate)"
                  size="small"
                  style="cursor:default"
                >{{ backdateTagText(row.reportDate) }}</el-tag>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="productName" label="产品" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.productName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="processCategory" label="工序" min-width="110" show-overflow-tooltip />
        <el-table-column prop="inputQuantity" label="投入" width="90">
          <template #default="{ row }">{{ formatQty(row.inputQuantity) }}</template>
        </el-table-column>
        <el-table-column prop="outputQuantity" label="产出" width="90">
          <template #default="{ row }">
            <span :class="{ 'text-warning': row.isSupplemental }">
              {{ formatQty(row.outputQuantity) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="totalWorkers" label="人数" width="80">
          <template #default="{ row }">{{ row.totalWorkers ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="totalWorkMinutes" label="工时" width="80">
          <template #default="{ row }">{{ row.totalWorkMinutes ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="isSupplemental" label="类型" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.isSupplemental" type="warning" size="small">补报</el-tag>
            <el-tag v-else type="info" size="small">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="processTaskId" label="任务ID" width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="160" fixed="right" v-if="canWrite">
          <template #default="{ row }">
            <el-button
              type="success"
              text
              size="small"
              :icon="Check"
              :disabled="approveSubmitting && approveTargetRow?.id === row.id"
              @click="handleApprove(row)"
            >
              通过
            </el-button>
            <el-button type="danger" text size="small" :icon="Close" @click="handleReject(row)">
              驳回
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="pagination.total > 0"
        style="margin-top: 16px; justify-content: flex-end"
        :current-page="pagination.page"
        :page-size="pagination.size"
        :total="pagination.total"
        layout="total, prev, pager, next"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- C-OPINION-1: 驳回意见弹框 (R3 必选 dropdown + R2 context line) -->
    <OpinionInputDialog
      v-if="factoryId"
      v-model:visible="rejectDialogVisible"
      title="驳回报工"
      :context-line="
        rejectTargetRow
          ? `${rejectTargetRow.reporterName ?? '?'} - ${rejectTargetRow.processCategory ?? '?'} (${formatDate(rejectTargetRow.reportDate)})`
          : ''
      "
      :factory-id="factoryId"
      decision-type="CUSTOM"
      other-placeholder="请输入驳回原因"
      confirm-text="驳回"
      @confirm="handleRejectConfirm"
    />

    <!-- fool-proof-design.md Rule 1+2: 通过前必先展示完整上下文再确认, 不可一键秒批.
         镜像 production/batches/detail.vue「申请撤回整单」弹框的 el-alert 上下文 pattern. -->
    <el-dialog
      v-model="approveDialogVisible"
      title="确认审批通过"
      width="480px"
      :close-on-click-modal="false"
      :close-on-press-escape="!approveSubmitting"
      destroy-on-close
      @close="cancelApprove"
    >
      <el-alert
        v-if="approveTargetRow"
        :title="`${approveTargetRow.productName || '产品未知'} - ${approveTargetRow.processCategory || '工序未知'}`"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #default>
          <div class="approve-context">
            <span>报工人: <strong>{{ approveTargetRow.reporterName || '-' }}</strong></span>
            <span>报工日期: <strong>{{ formatDate(approveTargetRow.reportDate) }}</strong></span>
            <span>投入量: <strong>{{ formatQty(approveTargetRow.inputQuantity) }}</strong></span>
            <span>产出量: <strong>{{ formatQty(approveTargetRow.outputQuantity) }}</strong></span>
            <span>人数: <strong>{{ approveTargetRow.totalWorkers ?? '-' }}</strong></span>
            <span>工时(分钟): <strong>{{ approveTargetRow.totalWorkMinutes ?? '-' }}</strong></span>
          </div>
        </template>
      </el-alert>
      <el-alert
        style="margin-top:12px"
        title="审批通过后将计入下游成本/工资核算，不可撤销（如需撤销请到「报工撤回」流程重新审批）。"
        type="info"
        :closable="false"
        show-icon
      />
      <template #footer>
        <div style="display:flex;justify-content:flex-end;gap:8px">
          <el-button :disabled="approveSubmitting" @click="cancelApprove">取消</el-button>
          <el-button
            type="success"
            :loading="approveSubmitting"
            :disabled="approveSubmitting"
            @click="confirmApprove"
          >确认审批通过</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-container { padding: 20px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; }
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.toolbar-right { display: flex; gap: 8px; }
.text-warning { color: #e6a23c; font-weight: 600; }
.report-detail {
  padding: 12px 24px 18px 24px;
  background: #fafcff;
}
.evidence-panel {
  margin-top: 12px;
}
.evidence-title {
  font-size: 13px;
  font-weight: 700;
  color: #303133;
  margin-bottom: 8px;
}
.evidence-media {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.evidence-item {
  width: 96px;
  height: 96px;
  border-radius: 6px;
  object-fit: cover;
  background: #111827;
}
.evidence-empty {
  color: #909399;
  font-size: 13px;
}
.custom-field-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.process-detail-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.process-segments {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.process-segment-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  color: #303133;
  font-size: 13px;
}
.custom-field-tag {
  max-width: 100%;
  height: auto;
  min-height: 24px;
  white-space: normal;
  line-height: 18px;
  padding: 3px 8px;
}
.approve-context {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 20px;
  font-size: 13px;
  color: #606266;
  margin-top: 4px;
}
</style>
