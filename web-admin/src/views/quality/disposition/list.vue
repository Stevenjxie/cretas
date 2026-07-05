<script setup lang="ts">
// 不良品处置/放行 (Quality Disposition) —— 质量主管人工决策界面。
//
// 接入既有、此前无 UI 的 QualityDispositionController:
//   evaluate  只读评估建议 (基于合格率/缺陷率规则)
//   execute   执行处置决策 (若需审批, 后端自动转入待审批队列)
//   pending   待审批处置列表
//   {id}/approve  审批(批准/拒绝), 批准后后端自动执行
//
// 重要: 本页只操作 QualityInspection 记录的处置决策审计与审批流转, 不直接修改
// MaterialBatch/FinishedGoodsBatch 库存状态 (那是 AI Tool ReleaseDecisionTool 的
// 职责范围, 与本 controller 代码无关联) —— 顶部提示条如实说明这一点。
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh, WarningFilled } from '@element-plus/icons-vue';
import { formatDateTimeCell } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';
import {
  getAvailableActions,
  evaluateDisposition,
  executeDisposition,
  getPendingDispositions,
  approveDisposition,
  type DispositionActionInfo,
  type DispositionActionCode,
  type DispositionEvaluationDTO,
  type PendingDispositionDTO,
} from '@/api/quality-disposition';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('quality'));
const currentUserId = computed(() => authStore.user?.id ?? 0);
const currentUserName = computed(() => authStore.user?.fullName || authStore.user?.username || '');

const activeTab = ref<'inspections' | 'approvals'>('inspections');

// ==================== Tab 1: 待处置质检 ====================
const inspectionLoading = ref(false);
const inspectionRows = ref<TableRow[]>([]);
// 生产批次号/品名查表 (质检记录接口本身不返回 batchNumber/productName, 只有
// production_batch_id 外键 —— 借用 inspections/list.vue 已用的同一 /processing/batches
// 查表模式做展示映射, 不改变后端契约).
const batchLookup = ref<Map<string, TableRow>>(new Map());

async function loadBatchLookup() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/processing/batches`, { params: { size: 200 } });
    if (res.success && res.data) {
      const list: TableRow[] = res.data.content || res.data || [];
      const m = new Map<string, TableRow>();
      for (const b of list) m.set(String(b.id), b);
      batchLookup.value = m;
    }
  } catch {
    /* silent — 展示层降级为只显示批次ID, 不阻塞主流程 */
  }
}

function batchInfo(productionBatchId: unknown): TableRow | undefined {
  if (productionBatchId == null) return undefined;
  return batchLookup.value.get(String(productionBatchId));
}

function batchLabel(productionBatchId: unknown): string {
  const b = batchInfo(productionBatchId);
  if (!b) return productionBatchId != null ? `批次 #${productionBatchId}` : '-';
  return `${b.batchNumber || '#' + productionBatchId}${b.productName ? ' - ' + b.productName : ''}`;
}

async function loadPendingInspections() {
  if (!factoryId.value) return;
  inspectionLoading.value = true;
  try {
    const [failRes, condRes] = await Promise.all([
      get(`/${factoryId.value}/processing/quality/inspections`, {
        params: { page: 1, size: 100, result: 'FAIL' },
      }),
      get(`/${factoryId.value}/processing/quality/inspections`, {
        params: { page: 1, size: 100, result: 'CONDITIONAL' },
      }),
    ]);
    const failList: TableRow[] = failRes.success && failRes.data ? (failRes.data.content || []) : [];
    const condList: TableRow[] = condRes.success && condRes.data ? (condRes.data.content || []) : [];
    inspectionRows.value = [...failList, ...condList].sort((a, b) =>
      String(b.inspectionDate || '').localeCompare(String(a.inspectionDate || ''))
    );
  } catch (error) {
    // Interceptor 已弹出具体的 sticky 错误提示
    console.error('加载待处置质检记录失败:', error);
  } finally {
    inspectionLoading.value = false;
  }
}

function getResultLabel(result: unknown): string {
  if (result === 'FAIL' || result === 'FAILED') return '不合格';
  if (result === 'CONDITIONAL') return '有条件放行';
  return String(result ?? '-');
}

// ==================== 处置动作元数据 (来自后端 /actions, 供 dropdown 使用) ====================
const actionOptions = ref<DispositionActionInfo[]>([]);
async function loadActionOptions() {
  if (!factoryId.value) return;
  actionOptions.value = await getAvailableActions(factoryId.value);
}

const ACTION_TAG_TYPE: Record<string, string> = {
  RELEASE: 'success',
  CONDITIONAL_RELEASE: 'warning',
  REWORK: 'warning',
  SCRAP: 'danger',
  SPECIAL_APPROVAL: 'danger',
  HOLD: 'info',
};

function actionTagType(code: string | undefined | null): string {
  return (code && ACTION_TAG_TYPE[code]) || 'info';
}

function actionDisplayName(code: string | undefined | null): string {
  const found = actionOptions.value.find((a) => a.actionCode === code);
  return found?.actionName || String(code ?? '-');
}

// ==================== 处置对话框 (评估 → 决策 → 执行) ====================
const dispositionDialogVisible = ref(false);
const dispositionRow = ref<TableRow | null>(null);
const evaluating = ref(false);
const evaluation = ref<DispositionEvaluationDTO | null>(null);
const selectedAction = ref<DispositionActionCode | ''>('');
const comment = ref('');
const executing = ref(false);

function openDispositionDialog(row: TableRow) {
  dispositionRow.value = row;
  selectedAction.value = '';
  comment.value = '';
  evaluation.value = null;
  dispositionDialogVisible.value = true;
  loadEvaluation(row);
}

async function loadEvaluation(row: TableRow) {
  if (!factoryId.value) return;
  evaluating.value = true;
  try {
    const result = await evaluateDisposition(factoryId.value, {
      inspectionId: row.id,
      productionBatchId: row.productionBatchId,
      inspectorId: row.inspectorId,
      inspectionDate: row.inspectionDate,
      sampleSize: row.sampleSize,
      passCount: row.passCount,
      failCount: row.failCount,
      passRate: row.passRate,
      result: row.result,
      notes: row.notes,
    });
    evaluation.value = result;
    selectedAction.value = result.recommendedAction;
  } catch (error) {
    console.error('评估处置建议失败:', error);
  } finally {
    evaluating.value = false;
  }
}

function pickAlternative(action: DispositionActionCode) {
  selectedAction.value = action;
}

// 高风险动作 (非直接放行) 必须留处置理由, 便于审计与后续审批人判断依据 —— 放行本身
// 通常无需说明 (合格率达标即可放行), 但其余动作都影响后续流程/成本, 理由必填。
const requiresComment = computed(() => selectedAction.value !== '' && selectedAction.value !== 'RELEASE');

async function submitDisposition() {
  if (!dispositionRow.value || !factoryId.value) return;
  if (!selectedAction.value) {
    ElMessage.warning('请选择处置动作');
    return;
  }
  if (requiresComment.value && !comment.value.trim()) {
    ElMessage.warning('该处置动作请填写处置理由，便于审计与审批参考');
    return;
  }
  executing.value = true;
  try {
    const result = await executeDisposition(factoryId.value, {
      batchId: dispositionRow.value.productionBatchId,
      inspectionId: dispositionRow.value.id,
      actionCode: selectedAction.value,
      operatorComment: comment.value.trim() || undefined,
      executorId: currentUserId.value,
    });
    if (result.approvalInitiated) {
      ElMessage({
        type: 'warning',
        message: `${result.message} — 请到"待审批处置"页签跟进`,
        duration: 0,
        showClose: true,
      });
    } else {
      ElMessage.success(`${result.message}${result.nextSteps ? ' — ' + result.nextSteps : ''}`);
    }
    dispositionDialogVisible.value = false;
    loadPendingInspections();
    loadPendingApprovals();
  } catch (error) {
    // Interceptor 已弹出具体的 sticky 错误提示
    console.error('执行处置失败:', error);
  } finally {
    executing.value = false;
  }
}

// ==================== Tab 2: 待审批处置 ====================
const approvalLoading = ref(false);
const approvalRows = ref<PendingDispositionDTO[]>([]);

async function loadPendingApprovals() {
  if (!factoryId.value) return;
  approvalLoading.value = true;
  try {
    approvalRows.value = await getPendingDispositions(factoryId.value);
  } catch (error) {
    console.error('加载待审批处置列表失败:', error);
  } finally {
    approvalLoading.value = false;
  }
}

// ==================== 审批对话框 ====================
const approveDialogVisible = ref(false);
const approveRow = ref<PendingDispositionDTO | null>(null);
const approveForm = ref<{ approved: boolean; comment: string }>({ approved: true, comment: '' });
const approving = ref(false);

function openApproveDialog(row: PendingDispositionDTO) {
  approveRow.value = row;
  approveForm.value = { approved: true, comment: '' };
  approveDialogVisible.value = true;
}

async function submitApprove() {
  if (!approveRow.value || !factoryId.value) return;
  if (!approveForm.value.approved && !approveForm.value.comment.trim()) {
    ElMessage.warning('拒绝时请填写审批意见');
    return;
  }
  approving.value = true;
  try {
    const result = await approveDisposition(factoryId.value, approveRow.value.id, {
      approved: approveForm.value.approved,
      approverId: currentUserId.value,
      approverName: currentUserName.value || undefined,
      comment: approveForm.value.comment.trim() || undefined,
    });
    ElMessage.success(result.message);
    approveDialogVisible.value = false;
    loadPendingApprovals();
    loadPendingInspections();
  } catch (error) {
    console.error('审批处置失败:', error);
  } finally {
    approving.value = false;
  }
}

function handleRefresh() {
  loadPendingInspections();
  loadPendingApprovals();
  loadBatchLookup();
}

onMounted(() => {
  loadActionOptions();
  loadBatchLookup();
  loadPendingInspections();
  loadPendingApprovals();
});
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">不良品处置</span>
          </div>
          <div class="header-right">
            <el-button :icon="Refresh" @click="handleRefresh">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="scope-alert"
      >
        <template #title>
          仅记录处置决策的审计与审批流转 (放行/条件放行/返工/报废/特批/待定)，不会自动修改批次库存状态；返工不会自动创建返工工单，需人工安排。
        </template>
      </el-alert>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="待处置质检" name="inspections">
          <el-table
            :data="inspectionRows"
            v-loading="inspectionLoading"
            empty-text="暂无待处置的不合格/条件放行质检记录"
            stripe
            border
            style="width: 100%"
          >
            <el-table-column label="生产批次" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ batchLabel(row.productionBatchId) }}</template>
            </el-table-column>
            <el-table-column label="抽样/合格/不合格" width="150" align="center">
              <template #default="{ row }">
                {{ row.sampleSize ?? '-' }} / {{ row.passCount ?? '-' }} / {{ row.failCount ?? '-' }}
              </template>
            </el-table-column>
            <el-table-column label="合格率" width="90" align="center">
              <template #default="{ row }">{{ row.passRate != null ? Number(row.passRate).toFixed(1) + '%' : '-' }}</template>
            </el-table-column>
            <el-table-column label="缺陷率" width="90" align="center">
              <template #default="{ row }">{{ row.defectRate != null ? Number(row.defectRate).toFixed(1) + '%' : '-' }}</template>
            </el-table-column>
            <el-table-column prop="qualityGrade" label="等级" width="70" align="center" />
            <el-table-column label="检测结果" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="row.result === 'CONDITIONAL' ? 'warning' : 'danger'" size="small">
                  {{ getResultLabel(row.result) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="inspectionDate" label="检测日期" width="130" />
            <el-table-column prop="notes" label="备注" min-width="150" show-overflow-tooltip />
            <el-table-column label="操作" width="100" fixed="right" align="center">
              <template #default="{ row }">
                <el-button
                  v-if="canWrite"
                  type="primary"
                  link
                  size="small"
                  @click="openDispositionDialog(row)"
                >处置</el-button>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane name="approvals">
          <template #label>
            待审批处置
            <el-badge v-if="approvalRows.length" :value="approvalRows.length" class="tab-badge" />
          </template>
          <el-table
            :data="approvalRows"
            v-loading="approvalLoading"
            empty-text="暂无待审批的处置申请"
            stripe
            border
            style="width: 100%"
          >
            <el-table-column label="生产批次" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.batchNumber || batchLabel(row.productionBatchId) }}
              </template>
            </el-table-column>
            <el-table-column label="处置动作" width="130" align="center">
              <template #default="{ row }">
                <el-tag :type="actionTagType(row.decisionMade)" size="small">
                  {{ row.actionDescription || actionDisplayName(row.decisionMade) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="处置原因" min-width="200" show-overflow-tooltip />
            <el-table-column prop="executorName" label="申请人" width="100" />
            <el-table-column prop="approvalLevel" label="审批级别" width="110" align="center" />
            <el-table-column prop="ruleConfigName" label="触发规则" width="140" show-overflow-tooltip />
            <el-table-column prop="createdAt" label="申请时间" width="180" :formatter="formatDateTimeCell" />
            <el-table-column label="操作" width="100" fixed="right" align="center">
              <template #default="{ row }">
                <el-button
                  v-if="canWrite"
                  type="primary"
                  link
                  size="small"
                  @click="openApproveDialog(row)"
                >审批</el-button>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 处置对话框: 评估建议 → 人工决策 → 执行 -->
    <el-dialog
      v-model="dispositionDialogVisible"
      :title="`不良品处置 — ${batchLabel(dispositionRow?.productionBatchId)}`"
      width="560px"
      destroy-on-close
    >
      <div v-if="dispositionRow" class="disposition-context">
        <div class="context-row">
          <span>抽样 {{ dispositionRow.sampleSize ?? '-' }}</span>
          <span>合格 {{ dispositionRow.passCount ?? '-' }}</span>
          <span>不合格 {{ dispositionRow.failCount ?? '-' }}</span>
          <span>合格率 {{ dispositionRow.passRate != null ? Number(dispositionRow.passRate).toFixed(1) + '%' : '-' }}</span>
        </div>
        <div class="context-row">
          <el-tag :type="dispositionRow.result === 'CONDITIONAL' ? 'warning' : 'danger'" size="small">
            {{ getResultLabel(dispositionRow.result) }}
          </el-tag>
          <span v-if="dispositionRow.notes" class="context-notes">{{ dispositionRow.notes }}</span>
        </div>
      </div>

      <el-divider content-position="left">评估建议</el-divider>
      <div v-loading="evaluating" style="min-height: 60px">
        <template v-if="evaluation">
          <div class="eval-row">
            <span>推荐动作：</span>
            <el-tag :type="actionTagType(evaluation.recommendedAction)" size="small">
              {{ evaluation.recommendedActionDescription }}
            </el-tag>
            <span v-if="evaluation.confidence != null" class="eval-confidence">置信度 {{ Number(evaluation.confidence).toFixed(0) }}%</span>
          </div>
          <div class="eval-reason">{{ evaluation.reason }}</div>
          <div v-if="evaluation.requiresApproval" class="eval-approval-hint">
            <el-icon><WarningFilled /></el-icon>
            此动作需要审批，提交后将进入"待审批处置"队列
          </div>
          <div v-if="evaluation.alternativeActions?.length" class="eval-alternatives">
            备选：
            <el-tag
              v-for="alt in evaluation.alternativeActions"
              :key="alt.action"
              class="alt-tag"
              size="small"
              effect="plain"
              @click="pickAlternative(alt.action)"
            >{{ alt.description }}</el-tag>
          </div>
        </template>
        <template v-else-if="!evaluating">
          <span class="eval-empty">评估建议加载失败，可直接手动选择处置动作。</span>
        </template>
      </div>

      <el-form label-width="90px" class="disposition-form">
        <el-form-item label="处置动作" required>
          <el-select v-model="selectedAction" placeholder="选择处置动作" style="width: 100%">
            <el-option
              v-for="opt in actionOptions"
              :key="opt.actionCode"
              :label="`${opt.actionName} — ${opt.applicableCondition}`"
              :value="opt.actionCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="requiresComment ? '处置理由' : '备注'" :required="requiresComment">
          <el-input
            v-model="comment"
            type="textarea"
            :rows="2"
            :placeholder="requiresComment ? '请说明处置理由（必填），便于审计与审批参考' : '可选备注'"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dispositionDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="executing" @click="submitDisposition">确认处置</el-button>
      </template>
    </el-dialog>

    <!-- 审批对话框 -->
    <el-dialog v-model="approveDialogVisible" title="审批处置申请" width="480px" destroy-on-close>
      <div v-if="approveRow" class="disposition-context">
        <div class="context-row">
          <strong>{{ approveRow.batchNumber || batchLabel(approveRow.productionBatchId) }}</strong>
        </div>
        <div class="context-row">
          <el-tag :type="actionTagType(approveRow.decisionMade)" size="small">
            {{ approveRow.actionDescription || actionDisplayName(approveRow.decisionMade) }}
          </el-tag>
          <span v-if="approveRow.approvalLevel">审批级别：{{ approveRow.approvalLevel }}</span>
        </div>
        <div v-if="approveRow.reason" class="context-notes">原因：{{ approveRow.reason }}</div>
        <div v-if="approveRow.executorName" class="context-notes">申请人：{{ approveRow.executorName }}</div>
      </div>

      <el-form label-width="90px" class="disposition-form">
        <el-form-item label="审批决定" required>
          <el-radio-group v-model="approveForm.approved">
            <el-radio-button :label="true">批准</el-radio-button>
            <el-radio-button :label="false">拒绝</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="approveForm.approved ? '审批意见' : '拒绝原因'" :required="!approveForm.approved">
          <el-input
            v-model="approveForm.comment"
            type="textarea"
            :rows="2"
            :placeholder="approveForm.approved ? '可选审批意见' : '请填写拒绝原因（必填）'"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="approveDialogVisible = false">取消</el-button>
        <el-button
          :type="approveForm.approved ? 'primary' : 'danger'"
          :loading="approving"
          @click="submitApprove"
        >{{ approveForm.approved ? '确认批准' : '确认拒绝' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  }

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .page-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--text-color-primary, #303133);
  }
}

.scope-alert {
  margin-bottom: 12px;
}

.tab-badge {
  margin-left: 6px;
  :deep(.el-badge__content) {
    transform: translateY(-2px);
  }
}

.disposition-context {
  line-height: 1.8;
  margin-bottom: 12px;

  .context-row {
    display: flex;
    gap: 12px;
    align-items: center;
    flex-wrap: wrap;
    color: var(--text-color-secondary, #606266);
  }

  .context-notes {
    color: var(--text-color-secondary, #909399);
    font-size: 13px;
  }
}

.eval-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.eval-confidence {
  font-size: 12px;
  color: var(--text-color-secondary, #909399);
}

.eval-reason {
  font-size: 13px;
  color: var(--text-color-secondary, #606266);
  margin-bottom: 8px;
}

.eval-approval-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #e6a23c;
  font-size: 13px;
  margin-bottom: 8px;
}

.eval-alternatives {
  font-size: 13px;
  color: var(--text-color-secondary, #909399);
  margin-bottom: 8px;

  .alt-tag {
    margin-left: 6px;
    cursor: pointer;
  }
}

.eval-empty {
  color: var(--text-color-secondary, #909399);
  font-size: 13px;
}

.disposition-form {
  margin-top: 12px;
}
</style>
