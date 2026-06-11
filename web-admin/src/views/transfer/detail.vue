<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import { get, post, put } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft, InfoFilled } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import { handleCatchError } from '@/utils/errorToast';
import NotFoundEmpty from '@/components/common/NotFoundEmpty.vue';
import type { TableRow } from '@/types/api';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const { label } = useBusinessMode();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
const transferId = computed(() => route.params.id as string);

const loading = ref(false);
const submitting = ref(false);
const transfer = ref<TableRow | null>(null);
const notFound = ref(false);
const notFoundMessage = ref('');

const statusMap: Record<string, { text: string; type: string }> = {
  DRAFT: { text: '草稿', type: 'info' },
  REQUESTED: { text: '已申请', type: 'warning' },
  APPROVED: { text: '已批准', type: '' },
  REJECTED: { text: '已驳回', type: 'danger' },
  SHIPPED: { text: '已发运', type: 'warning' },
  RECEIVED: { text: '已签收', type: '' },
  CONFIRMED: { text: '已确认', type: 'success' },
  CANCELLED: { text: '已取消', type: 'info' },
};

// 状态流转步骤
const statusSteps = ['DRAFT', 'REQUESTED', 'APPROVED', 'SHIPPED', 'RECEIVED', 'CONFIRMED'];
const terminalStatuses = ['REJECTED', 'CANCELLED'];

onMounted(() => loadTransfer());

async function loadTransfer() {
  if (!factoryId.value || !transferId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/transfers/${transferId.value}`);
    if (res.success) {
      transfer.value = res.data;
      // 签收后加载差异单（RECEIVED / CONFIRMED）
      if (res.data?.status === 'RECEIVED' || res.data?.status === 'CONFIRMED') {
        loadDiffs();
      }
    }
  } catch (err: any) {
    // axios interceptor wraps as ApiError(message, code, status)
    const status = err?.status ?? err?.response?.status;
    const code = err?.code ?? err?.response?.data?.code;
    if (status === 404 || status === 403 || code === 'NOT_FOUND' || code === 'FORBIDDEN') {
      notFound.value = true;
      notFoundMessage.value = err?.message || err?.response?.data?.message || '记录不存在';
    }
    // axios interceptor shows toast already (Bug #319 fix), component doesn't add fallback
  }
  finally { loading.value = false; }
}

function currentStep() {
  if (!transfer.value) return 0;
  if (terminalStatuses.includes(transfer.value.status)) return -1;
  const idx = statusSteps.indexOf(transfer.value.status);
  return idx >= 0 ? idx : 0;
}

const stepsStatus = computed(() => {
  if (!transfer.value) return 'process';
  return terminalStatuses.includes(transfer.value.status) ? 'error' : 'process';
});

const isOutbound = computed(() => transfer.value?.sourceFactoryId === factoryId.value);

async function handleAction(action: string) {
  if (submitting.value) return;
  const map: Record<string, { label: string; url: string }> = {
    request: { label: '提交申请', url: `/${factoryId.value}/transfers/${transferId.value}/request` },
    approve: { label: '审批通过', url: `/${factoryId.value}/transfers/${transferId.value}/approve` },
    reject: { label: '驳回', url: `/${factoryId.value}/transfers/${transferId.value}/reject` },
    ship: { label: '确认发运', url: `/${factoryId.value}/transfers/${transferId.value}/ship` },
    receive: { label: '确认签收', url: `/${factoryId.value}/transfers/${transferId.value}/receive` },
    confirm: { label: '确认入库', url: `/${factoryId.value}/transfers/${transferId.value}/confirm` },
    cancel: { label: '取消', url: `/${factoryId.value}/transfers/${transferId.value}/cancel` },
  };
  const a = map[action];
  if (!a) return;
  try {
    await ElMessageBox.confirm(`确认${a.label}？`, '操作确认');
  } catch { return; }
  submitting.value = true;
  try {
    const res = await post(a.url);
    if (res.success) { ElMessage.success(`${a.label}成功`); loadTransfer(); }
    else { ElMessage.error(res.message || `${a.label}失败，请重试`); }
  } catch (e) { handleCatchError(e, `${a.label}失败，请检查网络`); }
  finally { submitting.value = false; }
}

// PR #289 §B4 — display source-warehouse current stock per transfer item.
// Backend returns `currentStock` populated on each item via TransferServiceImpl.populateCurrentStock.
function formatStock(v: unknown): string {
  if (v === null || v === undefined || v === '') return '-';
  const n = typeof v === 'number' ? v : Number(v);
  if (Number.isNaN(n)) return '-';
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 4 });
}

function isStockShortage(row: Record<string, unknown>): boolean {
  const stock = row.currentStock as number | string | null | undefined;
  const qty = row.quantity as number | string | null | undefined;
  if (stock === null || stock === undefined) return false;
  if (qty === null || qty === undefined) return false;
  const sn = typeof stock === 'number' ? stock : Number(stock);
  const qn = typeof qty === 'number' ? qty : Number(qty);
  if (Number.isNaN(sn) || Number.isNaN(qn)) return false;
  return sn < qn;
}

// Issue #744: 调拨单缺库存预检 — 任何一行库存不足时,禁用「确认发运」按钮 (调出方视角).
// 列表表头展示 "现有库存" 红色高亮 (already wired in this file via .stock-shortage class).
const hasStockShortage = computed(() => {
  if (!transfer.value?.items?.length) return false;
  return (transfer.value.items as Record<string, unknown>[]).some(isStockShortage);
});

const shipBlockedReason = computed(() => {
  if (!hasStockShortage.value) return '';
  const shortageRows = (transfer.value?.items as Record<string, unknown>[] | undefined)
    ?.filter(isStockShortage) ?? [];
  const names = shortageRows.map(r => {
    const m = (r.materialType as { name?: string } | undefined)?.name
      ?? (r.productType as { name?: string } | undefined)?.name
      ?? r.materialTypeId ?? r.productTypeId ?? '-';
    return String(m);
  });
  return `库存不足 (${names.join(', ')}). 请先采购或调入补足后再发货.`;
});

// ==================== B1 两阶段批次选择 (PR #309 B1=C, 2026-05-11) ====================
// SHIP 前 (status=APPROVED), 调出方用户可为每个 item 预选批次:
//   - 留空 = 默认 FEFO (最早过期先扣)
//   - 选具体批次 = 优先扣减该批次, 不足部分 FEFO 兜底

interface BatchOption {
  batchId: string;
  batchNumber: string;
  availableQuantity: number;
  expireDate: string | null;
  warehouseId: string;
}

const availableBatchesMap = ref<Record<string, BatchOption[]>>({}); // itemId → 可用批次列表
const batchesLoading = ref<Record<string, boolean>>({});

// 仅 APPROVED 状态 + 调出方角度才显示批次列
const canSelectBatch = computed(() =>
  transfer.value?.status === 'APPROVED' && isOutbound.value && canWrite.value,
);

async function loadAvailableBatches(itemId: string | number) {
  if (!factoryId.value || !transferId.value || itemId === null || itemId === undefined) return;
  const key = String(itemId);
  batchesLoading.value[key] = true;
  try {
    const res = await get<BatchOption[]>(
      `/${factoryId.value}/transfers/${transferId.value}/items/${itemId}/available-batches`,
    );
    if (res.success) availableBatchesMap.value[key] = res.data || [];
  } catch (e) { handleCatchError(e, '加载可用批次失败'); }
  finally { batchesLoading.value[key] = false; }
}

async function onBatchChange(itemId: string | number, sourceBatchId: string | null) {
  if (!factoryId.value || !transferId.value) return;
  try {
    const res = await put(
      `/${factoryId.value}/transfers/${transferId.value}/items/${itemId}/source-batch`,
      { sourceBatchId },
    );
    if (res.success) {
      ElMessage.success(sourceBatchId ? '已锁定批次' : '已切换为默认 FEFO');
      loadTransfer(); // refresh to show updated sourceBatchId
    } else {
      ElMessage.error(res.message || '批次保存失败');
    }
  } catch (e) { handleCatchError(e, '批次保存失败, 请重试'); }
}

function formatBatchLabel(b: BatchOption): string {
  const parts = [b.batchNumber, `可用 ${formatStock(b.availableQuantity)}`];
  if (b.expireDate) parts.push(`到期 ${b.expireDate}`);
  return parts.join(' · ');
}

// ==================== 调拨差异找单（TDIFF）====================

interface DiffRecord {
  id: string;
  diffNumber: string;
  itemName: string | null;
  shippedQuantity: number;
  receivedQuantity: number;
  diffQuantity: number;
  unit: string | null;
  status: string;
  decision: string | null;
  decisionAt: string | null;
  decisionNotes: string | null;
}

const diffs = ref<DiffRecord[]>([]);
const diffsLoading = ref(false);
const decideDialogVisible = ref(false);
const decidingDiff = ref<DiffRecord | null>(null);
const decideForm = ref({ decision: '', notes: '' });
const decideSubmitting = ref(false);

const diffStatusMap: Record<string, { text: string; type: string }> = {
  PENDING: { text: '待处理', type: 'warning' },
  RESOLVED: { text: '已处理', type: 'success' },
};

const diffDecisionMap: Record<string, string> = {
  FOUND_BACK: '找回货物',
  WRITE_OFF_LOSS: '核销损耗',
  TRANSFER_DAMAGE: '转报损',
};

async function loadDiffs() {
  if (!factoryId.value || !transferId.value) return;
  diffsLoading.value = true;
  try {
    const res = await get<DiffRecord[]>(
      `/${factoryId.value}/transfer-diffs/by-transfer/${transferId.value}`,
    );
    if (res.success) diffs.value = res.data || [];
  } catch {
    // 非核心：静默失败，不影响主界面
  } finally {
    diffsLoading.value = false;
  }
}

function openDecideDialog(diff: DiffRecord) {
  decidingDiff.value = diff;
  decideForm.value = { decision: '', notes: '' };
  decideDialogVisible.value = true;
}

async function submitDecide() {
  if (!decidingDiff.value || !decideForm.value.decision) {
    ElMessage.error('请选择处理方式');
    return;
  }
  decideSubmitting.value = true;
  try {
    const res = await post(
      `/${factoryId.value}/transfer-diffs/${decidingDiff.value.id}/decide`,
      { decision: decideForm.value.decision, notes: decideForm.value.notes || null },
    );
    if (res.success) {
      ElMessage.success('差异单已处理');
      decideDialogVisible.value = false;
      loadDiffs();
    } else {
      ElMessage({ message: res.message || '操作失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) { handleCatchError(e, '提交失败，请检查网络'); }
  finally { decideSubmitting.value = false; }
}

// watch 备用：handleAction('receive') 后 loadTransfer 刷新会触发 loadDiffs（在 loadTransfer 内已处理）
</script>

<template>
  <NotFoundEmpty v-if="notFound"
    :description="notFoundMessage"
    return-path="/transfer/list" />
  <div v-else class="page-wrapper" v-loading="loading">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <el-button :icon="ArrowLeft" @click="router.push('/transfer/list')">返回</el-button>
            <span class="page-title">{{ label('transfer') }}详情</span>
            <el-tag v-if="transfer" :type="(statusMap[transfer.status]?.type) || 'info'" size="large">
              {{ statusMap[transfer.status]?.text || transfer.status }}
            </el-tag>
          </div>
          <div class="header-right" v-if="transfer && canWrite">
            <el-button v-if="transfer.status === 'DRAFT'" type="warning" :loading="submitting" @click="handleAction('request')">提交申请</el-button>
            <el-button v-if="transfer.status === 'REQUESTED'" type="success" :loading="submitting" @click="handleAction('approve')">审批通过</el-button>
            <el-button v-if="transfer.status === 'REQUESTED'" type="danger" :loading="submitting" @click="handleAction('reject')">驳回</el-button>
            <el-tooltip
              v-if="transfer.status === 'APPROVED' && isOutbound && hasStockShortage"
              :content="shipBlockedReason" placement="top">
              <span>
                <el-button type="primary" disabled>确认发运</el-button>
              </span>
            </el-tooltip>
            <el-button
              v-else-if="transfer.status === 'APPROVED' && isOutbound"
              type="primary"
              :loading="submitting"
              @click="handleAction('ship')">确认发运</el-button>
            <el-button v-if="transfer.status === 'SHIPPED' && !isOutbound" type="primary" :loading="submitting" @click="handleAction('receive')">确认签收</el-button>
            <el-button v-if="transfer.status === 'RECEIVED' && !isOutbound" type="success" :loading="submitting" @click="handleAction('confirm')">确认入库</el-button>
            <el-button v-if="['DRAFT','REQUESTED'].includes(transfer.status)" :disabled="submitting" @click="handleAction('cancel')">取消</el-button>
          </div>
        </div>
      </template>

      <template v-if="transfer">
        <!-- 状态流程 -->
        <el-steps v-if="!terminalStatuses.includes(transfer.status)" :active="currentStep()" finish-status="success" style="margin-bottom: 24px">
          <el-step title="草稿" />
          <el-step title="已申请" />
          <el-step title="已批准" />
          <el-step title="已发运" />
          <el-step title="已签收" />
          <el-step title="已确认" />
        </el-steps>
        <el-alert v-else :title="`该调拨单已${statusMap[transfer.status]?.text}`" :type="transfer.status === 'REJECTED' ? 'error' : 'info'" show-icon :closable="false" style="margin-bottom: 24px" />

        <!-- Issue #744: 库存不足时主动告警 (调出方视角) -->
        <el-alert
          v-if="transfer.status === 'APPROVED' && isOutbound && hasStockShortage"
          type="error" show-icon :closable="false" style="margin-bottom: 16px"
          title="原料库存不足，无法发货"
          :description="shipBlockedReason" />

        <el-descriptions :column="3" border>
          <el-descriptions-item label="调拨编号">{{ transfer.transferNumber }}</el-descriptions-item>
          <el-descriptions-item label="调拨类型">
            {{ transfer.transferType === 'HQ_TO_BRANCH' ? '总部→分部' : transfer.transferType === 'BRANCH_TO_BRANCH' ? '分部→分部' : '分部→总部' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="总金额">{{ formatAmount(transfer.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="调出方">{{ transfer.sourceFactory?.name || transfer.sourceFactoryId }}</el-descriptions-item>
          <el-descriptions-item label="调入方">{{ transfer.targetFactory?.name || transfer.targetFactoryId }}</el-descriptions-item>
          <el-descriptions-item label="调拨日期">{{ transfer.transferDate }}</el-descriptions-item>
          <el-descriptions-item label="预计到达">{{ transfer.expectedArrivalDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="申请人">{{ transfer.requestedBy || '-' }}</el-descriptions-item>
          <el-descriptions-item label="审批人">{{ transfer.approvedBy || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ transfer.remark || '-' }}</el-descriptions-item>
        </el-descriptions>

        <h3 style="margin: 20px 0 12px">调拨明细</h3>
        <el-table :data="transfer.items || []" border stripe empty-text="暂无明细数据">
          <el-table-column label="类型" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.itemType === 'RAW_MATERIAL' ? '' : 'success'" size="small">
                {{ row.itemType === 'RAW_MATERIAL' ? '原料' : '成品' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="品名" min-width="150">
            <template #default="{ row }">
              {{ row.materialType?.name || row.productType?.name || row.materialTypeId || row.productTypeId || '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="quantity" label="调拨数量" width="120" align="right" />
          <el-table-column label="现有库存" width="130" align="right">
            <template #header>
              <el-tooltip content="调出方当前可用库存 (实时查询)" placement="top">
                <span>现有库存 <el-icon style="vertical-align: -2px"><InfoFilled /></el-icon></span>
              </el-tooltip>
            </template>
            <template #default="{ row }">
              <span :class="{ 'stock-shortage': isStockShortage(row) }">
                {{ formatStock(row.currentStock) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="已收数量" width="120" align="right">
            <template #default="{ row }">{{ row.receivedQuantity || 0 }}</template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="80" align="center" />
          <el-table-column v-if="canViewPrice" prop="unitPrice" label="单价" width="120" align="right">
            <template #default="{ row }">{{ formatAmount(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="小计" width="130" align="right">
            <template #default="{ row }">{{ formatAmount(row.quantity * row.unitPrice) }}</template>
          </el-table-column>
          <!-- B1: SHIP 前批次选择 (status=APPROVED + 调出方视角) -->
          <el-table-column v-if="canSelectBatch" label="发货批次" width="280" align="left">
            <template #header>
              <el-tooltip
                content="留空 = 默认按 FEFO (最早过期) 扣减; 选具体批次 = 优先扣该批次. 卤味业务建议选新货."
                placement="top">
                <span>发货批次 <el-icon style="vertical-align: -2px"><InfoFilled /></el-icon></span>
              </el-tooltip>
            </template>
            <template #default="{ row }">
              <el-select
                :model-value="row.sourceBatchId || ''"
                placeholder="默认 FEFO (自动选最早过期)"
                clearable
                size="small"
                style="width: 100%"
                :loading="batchesLoading[String(row.id)]"
                @visible-change="(v: boolean) => v && !availableBatchesMap[String(row.id)] && loadAvailableBatches(row.id)"
                @change="(v: string | null) => onBatchChange(row.id, v || null)">
                <el-option label="默认 FEFO (自动选最早过期)" value="" />
                <el-option
                  v-for="b in availableBatchesMap[String(row.id)] || []"
                  :key="b.batchId"
                  :label="formatBatchLabel(b)"
                  :value="b.batchId" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>

        <!-- ════ 调拨差异找单（签收后自动出现） ════ -->
        <template v-if="['RECEIVED','CONFIRMED'].includes(transfer.status)">
          <div style="display:flex; align-items:center; gap:8px; margin: 28px 0 12px">
            <h3 style="margin:0">调拨差异找单</h3>
            <el-tag v-if="diffs.filter(d => d.status === 'PENDING').length > 0" type="danger" size="small">
              {{ diffs.filter(d => d.status === 'PENDING').length }} 条待处理
            </el-tag>
            <el-tag v-else-if="diffs.length > 0" type="success" size="small">全部已处理</el-tag>
            <el-tag v-else type="info" size="small">无差异</el-tag>
            <el-button size="small" :loading="diffsLoading" @click="loadDiffs()">刷新</el-button>
          </div>

          <el-alert
            v-if="diffs.filter(d => d.status === 'PENDING').length > 0"
            type="warning" show-icon :closable="false" style="margin-bottom: 12px"
            title="存在未处理的调拨差异"
            description="发出量与实收量不符，请核实差异原因后选择处理方式（找回 / 核销损耗 / 转报损）" />

          <el-table v-loading="diffsLoading" :data="diffs" border stripe empty-text="签收量与发货量一致，无差异">
            <el-table-column prop="diffNumber" label="差异单号" width="220" />
            <el-table-column label="品名" min-width="140">
              <template #default="{ row }">{{ row.itemName || '-' }}</template>
            </el-table-column>
            <el-table-column label="发货量" width="110" align="right">
              <template #default="{ row }">{{ row.shippedQuantity }} {{ row.unit }}</template>
            </el-table-column>
            <el-table-column label="实收量" width="110" align="right">
              <template #default="{ row }">{{ row.receivedQuantity }} {{ row.unit }}</template>
            </el-table-column>
            <el-table-column label="差异量" width="110" align="right">
              <template #default="{ row }">
                <span class="diff-qty">-{{ row.diffQuantity }} {{ row.unit }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="(diffStatusMap[row.status]?.type) || 'info'" size="small">
                  {{ diffStatusMap[row.status]?.text || row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="处理结果" min-width="120">
              <template #default="{ row }">
                <span v-if="row.decision">{{ diffDecisionMap[row.decision] || row.decision }}</span>
                <span v-else class="text-muted">未处理</span>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="140">
              <template #default="{ row }">{{ row.decisionNotes || '-' }}</template>
            </el-table-column>
            <el-table-column v-if="canWrite" label="操作" width="100" align="center" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'PENDING'"
                  type="primary" size="small" link
                  @click="openDecideDialog(row)">处理</el-button>
                <span v-else class="text-muted">已完成</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </template>
    </el-card>
  </div>

  <!-- 差异处理 Dialog -->
  <el-dialog
    v-model="decideDialogVisible"
    title="调拨差异处理"
    width="480px"
    :close-on-click-modal="false">
    <template v-if="decidingDiff">
      <el-descriptions :column="1" border size="small" style="margin-bottom:16px">
        <el-descriptions-item label="差异单号">{{ decidingDiff.diffNumber }}</el-descriptions-item>
        <el-descriptions-item label="品名">{{ decidingDiff.itemName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="发货量">{{ decidingDiff.shippedQuantity }} {{ decidingDiff.unit }}</el-descriptions-item>
        <el-descriptions-item label="实收量">{{ decidingDiff.receivedQuantity }} {{ decidingDiff.unit }}</el-descriptions-item>
        <el-descriptions-item label="差异量">
          <span class="diff-qty">-{{ decidingDiff.diffQuantity }} {{ decidingDiff.unit }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="调拨单">{{ transfer?.transferNumber }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="90px">
        <el-form-item label="处理方式" required>
          <el-radio-group v-model="decideForm.decision" style="display:flex; flex-direction:column; gap:8px">
            <el-radio value="FOUND_BACK">找回货物（差异货物已找回/补发）</el-radio>
            <el-radio value="WRITE_OFF_LOSS">核销损耗（记为运输/储存损耗）</el-radio>
            <el-radio value="TRANSFER_DAMAGE">转报损（货物破损，转入库存报损）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="decideForm.notes"
            type="textarea" :rows="2"
            placeholder="可填写差异原因或处理说明" />
        </el-form-item>
      </el-form>
    </template>
    <template #footer>
      <el-button @click="decideDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="decideSubmitting" @click="submitDecide">确认处理</el-button>
    </template>
  </el-dialog>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; padding: 20px; overflow-y: auto; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: center; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
  }
  .header-right { display: flex; gap: 8px; }
}
// PR #289 §B4 — stock shortage marker for "现有库存" column
.stock-shortage { color: #f56c6c; font-weight: 600; }
// 调拨差异量红色高亮（少收警示）
.diff-qty { color: #f56c6c; font-weight: 600; }
.text-muted { color: #909399; font-size: 12px; }
</style>
