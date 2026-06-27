<template>
  <CanvasAwareWrapper module-code="restaurant">
    <div class="restaurant-page">
      <div class="page-head">
        <div>
          <h2>供应商月对账</h2>
          <p>按供应商和月份核对送货单、应付挂账、付款与调整。存在差异时不能确认。</p>
        </div>
        <div class="head-actions">
          <el-button :icon="Refresh" @click="loadAll">刷新</el-button>
          <el-button type="primary" :icon="Plus" @click="draftVisible = true">生成月对账</el-button>
        </div>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="确认前必须差异为 0"
        description="系统不自动抹平差异。缺少应付挂账、金额不一致、存在未匹配应付时，确认会被后端拒绝并给出下一步处理提示。"
      />

      <el-table :data="rows" v-loading="loading" stripe class="main-table" row-key="id">
        <el-table-column type="expand">
          <template #default="{ row }">
            <el-table :data="row.lines || []" size="small" border>
              <el-table-column label="状态" width="110">
                <template #default="{ row: line }">
                  <el-tag :type="lineStatusType(line.lineStatus)" size="small">
                    {{ lineStatusLabel(line.lineStatus) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="送货单" min-width="150">
                <template #default="{ row: line }">{{ line.deliveryNoteNumber || line.deliveryNoteId || '-' }}</template>
              </el-table-column>
              <el-table-column prop="deliveryDate" label="送货日" width="110" />
              <el-table-column label="送货金额" width="110" align="right">
                <template #default="{ row: line }">{{ money(line.deliveryAmount) }}</template>
              </el-table-column>
              <el-table-column label="应付单" min-width="150">
                <template #default="{ row: line }">{{ line.apTransactionNumber || line.apTransactionId || '-' }}</template>
              </el-table-column>
              <el-table-column prop="transactionDate" label="交易日" width="110" />
              <el-table-column label="应付金额" width="110" align="right">
                <template #default="{ row: line }">{{ money(line.apAmount) }}</template>
              </el-table-column>
              <el-table-column label="差异" width="110" align="right">
                <template #default="{ row: line }">
                  <span :class="{ danger: Number(line.differenceAmount || 0) !== 0 }">
                    {{ money(line.differenceAmount) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column prop="remark" label="说明" min-width="180" />
              <el-table-column label="送货照片" width="100">
                <template #default="{ row: line }">
                  <el-link v-if="line.photoOssUrl" :href="line.photoOssUrl" target="_blank" type="primary" underline="hover">查看</el-link>
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <el-table-column label="语音转录" min-width="160">
                <template #default="{ row: line }">
                  <span class="evidence-text">{{ line.voiceTranscriptText || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="价格审批" width="110">
                <template #default="{ row: line }">
                  <el-tag v-if="line.priceAnomalyApprovalStatus" size="small" :type="line.priceAnomalyApprovalStatus === 'APPROVED' ? 'success' : 'warning'">
                    {{ line.priceAnomalyApprovalStatus }}
                  </el-tag>
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <el-table-column label="审批意见" min-width="140">
                <template #default="{ row: line }">{{ line.priceAnomalyApprovalComment || '—' }}</template>
              </el-table-column>
              <el-table-column label="应付单号" min-width="140">
                <template #default="{ row: line }">{{ line.payableTransactionId || line.apTransactionNumber || '—' }}</template>
              </el-table-column>
            </el-table>

            <!-- Option B: orphan notes confirmed after freeze — read-only visibility only -->
            <div
              v-if="row.status === 'CONFIRMED' && row.unReconciledNotes && row.unReconciledNotes.length > 0"
              class="orphan-notes-section"
            >
              <el-alert
                type="warning"
                :closable="false"
                show-icon
                :title="`本月有 ${row.unReconciledNotes.length} 张送货单在对账冻结后确认，未纳入本期对账`"
                description="以下送货单已完成审批并生成应付挂账，但因对账已冻结未列入本期明细。可在下月生成对账草稿时自动纳入，或由财务与供应商协商调整。"
              />
              <el-table :data="row.unReconciledNotes" size="small" border class="orphan-table">
                <el-table-column label="单号" min-width="180">
                  <template #default="{ row: n }">{{ n.noteNumber || n.deliveryNoteId || '—' }}</template>
                </el-table-column>
                <el-table-column prop="deliveryDate" label="送货日" width="110" />
                <el-table-column label="金额" width="120" align="right">
                  <template #default="{ row: n }">{{ money(n.totalAmount) }}</template>
                </el-table-column>
                <el-table-column label="应付交易ID" min-width="200">
                  <template #default="{ row: n }">{{ n.payableTransactionId || '—' }}</template>
                </el-table-column>
                <el-table-column label="确认时间" min-width="170">
                  <template #default="{ row: n }">{{ n.confirmedAt || '—' }}</template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="month" label="月份" width="100" />
        <el-table-column label="供应商" min-width="180">
          <template #default="{ row }">{{ row.supplierName || row.supplierId }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'CONFIRMED' ? 'success' : 'warning'">
              {{ row.status === 'CONFIRMED' ? '已确认' : '草稿' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="deliveryNoteCount" label="送货单" width="90" align="right" />
        <el-table-column prop="apTransactionCount" label="应付交易" width="100" align="right" />
        <el-table-column label="送货合计" width="120" align="right">
          <template #default="{ row }">{{ money(row.deliveryTotal) }}</template>
        </el-table-column>
        <el-table-column label="应付合计" width="120" align="right">
          <template #default="{ row }">{{ money(row.apInvoiceTotal) }}</template>
        </el-table-column>
        <el-table-column label="已付" width="110" align="right">
          <template #default="{ row }">{{ money(row.apPaymentTotal) }}</template>
        </el-table-column>
        <el-table-column label="差异" width="110" align="right">
          <template #default="{ row }">
            <span :class="{ danger: Number(row.differenceAmount || 0) !== 0 }">
              {{ money(row.differenceAmount) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="净应付" width="120" align="right">
          <template #default="{ row }">{{ money(row.netPayableAmount) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'DRAFT'"
              link
              type="primary"
              :disabled="Number(row.differenceAmount || 0) !== 0"
              @click="confirmRow(row)"
            >
              确认
            </el-button>
            <span v-else class="muted">已锁定</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>暂无供应商月对账</p>
            <p class="muted">先确认供应商送货单并生成应付挂账，再按月生成对账草稿。</p>
          </div>
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        class="pager"
        layout="total, sizes, prev, pager, next"
        :total="total"
        :page-sizes="[10, 20, 50]"
        @current-change="loadRows"
        @size-change="loadRows"
      />

      <el-dialog v-model="draftVisible" title="生成供应商月对账草稿" width="520px">
        <el-form label-width="92px">
          <el-form-item label="供应商" required>
            <el-select
              v-model="draftSupplierId"
              filterable
              allow-create
              default-first-option
              placeholder="选择供应商，或粘贴供应商ID"
              style="width: 100%"
            >
              <el-option
                v-for="supplier in supplierOptions"
                :key="supplier.id"
                :label="supplier.name"
                :value="supplier.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="月份" required>
            <el-date-picker v-model="draftMonth" type="month" value-format="YYYY-MM" style="width: 100%" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="draftVisible = false">取消</el-button>
          <el-button type="primary" :loading="drafting" :disabled="!draftSupplierId || !draftMonth" @click="createDraft">
            生成草稿
          </el-button>
        </template>
      </el-dialog>
    </div>
  </CanvasAwareWrapper>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import { useFactoryId } from '@/composables/useFactoryId';
import { getErrorMessage } from '@/utils/errorToast';
import { getNoteList } from '@/api/restaurant/supplierDeliveryNote';
import {
  confirmSupplierReconciliation,
  createSupplierReconciliationDraft,
  listSupplierReconciliations,
  type SupplierMonthlyReconciliationDto,
  type SupplierReconciliationLineStatus,
} from '@/api/restaurant/supplierReconciliation';

interface SupplierOption {
  id: string;
  name: string;
}

const factoryId = useFactoryId();
const loading = ref(false);
const drafting = ref(false);
const rows = ref<SupplierMonthlyReconciliationDto[]>([]);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const draftVisible = ref(false);
const draftSupplierId = ref('');
const draftMonth = ref(currentMonth());
const supplierOptions = ref<SupplierOption[]>([]);

const canLoad = computed(() => Boolean(factoryId.value));

onMounted(loadAll);

function showStickyError(message: string) {
  ElMessage({ message, type: 'error', duration: 0, showClose: true });
}

async function loadAll() {
  await Promise.all([loadRows(), loadSupplierOptions()]);
}

async function loadRows() {
  if (!canLoad.value) return;
  loading.value = true;
  try {
    const resp = await listSupplierReconciliations(factoryId.value, { page: page.value, size: size.value });
    rows.value = resp.data.content || [];
    total.value = resp.data.totalElements || 0;
  } catch (error) {
    showStickyError(getErrorMessage(error, '供应商月对账加载失败'));
  } finally {
    loading.value = false;
  }
}

async function loadSupplierOptions() {
  if (!canLoad.value) return;
  try {
    const resp = await getNoteList(factoryId.value, { status: 'CONFIRMED', page: 1, size: 200 });
    const seen = new Map<string, SupplierOption>();
    for (const note of resp.data.content || []) {
      if (!note.supplierId) continue;
      seen.set(note.supplierId, { id: note.supplierId, name: note.supplierName || note.supplierId });
    }
    supplierOptions.value = Array.from(seen.values()).sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
  } catch {
    supplierOptions.value = [];
  }
}

async function createDraft() {
  if (!draftSupplierId.value || !draftMonth.value) return;
  drafting.value = true;
  try {
    await createSupplierReconciliationDraft(factoryId.value, {
      supplierId: draftSupplierId.value,
      month: draftMonth.value,
    });
    ElMessage.success('月对账草稿已生成');
    draftVisible.value = false;
    await loadRows();
  } catch (error) {
    showStickyError(getErrorMessage(error, '生成月对账草稿失败'));
  } finally {
    drafting.value = false;
  }
}

async function confirmRow(row: SupplierMonthlyReconciliationDto) {
  await ElMessageBox.confirm(
    `确认后该月对账将锁定。\n供应商：${row.supplierName || row.supplierId}\n月份：${row.month}\n差异：${money(row.differenceAmount)}`,
    '确认供应商月对账',
    { type: 'warning', confirmButtonText: '确认对账', cancelButtonText: '取消' },
  );
  try {
    await confirmSupplierReconciliation(factoryId.value, row.id);
    ElMessage.success('月对账已确认');
    await loadRows();
  } catch (error) {
    showStickyError(getErrorMessage(error, '确认月对账失败'));
  }
}

function money(value?: number | null) {
  if (value === null || value === undefined) return '已脱敏';
  return `¥${Number(value).toFixed(2)}`;
}

function lineStatusLabel(status: SupplierReconciliationLineStatus) {
  const map: Record<SupplierReconciliationLineStatus, string> = {
    MATCHED: '匹配',
    MISSING_AP: '缺应付',
    AMOUNT_MISMATCH: '金额不符',
    UNMATCHED_AP: '未匹配应付',
    INFO: '信息',
  };
  return map[status] || status;
}

function lineStatusType(status: SupplierReconciliationLineStatus) {
  if (status === 'MATCHED') return 'success';
  if (status === 'INFO') return 'info';
  return 'danger';
}

function currentMonth() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}
</script>

<style scoped>
@use '../restaurant-shared.scss';

.restaurant-page { padding: 16px; }
.page-head { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; margin-bottom: 12px; }
.page-head h2 { margin: 0 0 6px; font-size: 20px; }
.page-head p { margin: 0; color: var(--el-text-color-secondary); }
.head-actions { display: flex; gap: 8px; }
.main-table { margin-top: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
.danger { color: var(--el-color-danger); font-weight: 600; }
.evidence-text {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}
.muted { color: var(--el-text-color-secondary); font-size: 12px; }
.empty-state { padding: 16px 0; }
.empty-state p { margin: 4px 0; }
.orphan-notes-section { margin-top: 12px; }
.orphan-table { margin-top: 8px; }
</style>
