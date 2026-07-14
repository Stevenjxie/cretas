<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  listVouchers,
  postVoucher,
  batchPostVouchers,
  voidVoucher,
  VOUCHER_STATUS_LABEL,
  VOUCHER_TYPE_LABEL,
  SOURCE_BUSINESS_TYPE_LABEL,
  type Voucher,
  type VoucherStatus,
  type VoucherType,
  type SourceBusinessType,
  type VoucherBatchPostResponse,
} from '@/api/voucher';
import { formatAmount } from '@/utils/tableFormatters';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId ?? '');
const canWrite = computed(() => permissionStore.canWrite('finance'));

// ==================== State ====================

const loading = ref(false);
const tableData = ref<Voucher[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });

const filterStatus = ref<VoucherStatus | ''>('');
const filterType = ref<VoucherType | ''>('');
const filterSourceBusinessType = ref<SourceBusinessType | ''>('');
const filterVoucherNumber = ref('');

const voidDialogVisible = ref(false);
const voidTargetId = ref('');
const voidTargetRow = ref<Voucher | null>(null); // Rule 2: 携带凭证上下文
const voidReason = ref('');
const voidOtherText = ref(''); // Rule 3: "其他" 时补充文本
const voiding = ref(false);

// 防呆 Rule 4: 过账是财务写操作(不可逆), 无 loading 保护时双击可能重复提交过账请求。
const postingIds = ref<Set<string>>(new Set());

// 批量过账 (Part 2 follow-up to #1228): 人工审核制下逐张过账负担太大, 支持多选批量过账。
const selectedRows = ref<Voucher[]>([]);
const batchPosting = ref(false);
const batchResultVisible = ref(false);
const batchResult = ref<VoucherBatchPostResponse | null>(null);

// Rule 3: 作废原因标准选项
const VOID_REASON_OPTIONS = [
  { value: '录入有误', label: '录入有误' },
  { value: '业务撤销', label: '业务撤销' },
  { value: '重复生成', label: '重复生成' },
  { value: '科目错误', label: '科目错误' },
  { value: '_other', label: '其他原因' },
];

// ==================== Load ====================

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const result = await listVouchers(factoryId.value, {
      status: filterStatus.value || undefined,
      type: filterType.value || undefined,
      sourceBusinessType: filterSourceBusinessType.value || undefined,
      page: pagination.value.page,
      size: pagination.value.size,
    });
    let rows = result.content ?? [];
    // 凭证字号前端过滤 (后端 list 端点暂无 keyword 参数)
    const kw = filterVoucherNumber.value.trim();
    if (kw) {
      const lower = kw.toLowerCase();
      rows = rows.filter(
        (r) =>
          String(r.voucherNumber || '').toLowerCase().includes(lower) ||
          String(r.sourceBusinessId || '').toLowerCase().includes(lower),
      );
    }
    tableData.value = rows;
    pagination.value.total = result.totalElements ?? 0;
  } catch (e: unknown) {
    ElMessage({
      message: e instanceof Error ? e.message : '凭证列表加载失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleReset() {
  filterStatus.value = '';
  filterType.value = '';
  filterSourceBusinessType.value = '';
  filterVoucherNumber.value = '';
  handleSearch();
}

function handlePageChange(p: number) {
  pagination.value.page = p;
  loadData();
}

// ==================== Actions ====================

function handleViewDetail(id: string) {
  router.push(`/finance/voucher/${id}`);
}

async function handlePost(id: string) {
  if (postingIds.value.has(id)) return;
  try {
    await ElMessageBox.confirm('确认将该凭证过账？过账后不可直接编辑。', '确认过账', {
      confirmButtonText: '确认过账',
      cancelButtonText: '取消',
      type: 'warning',
    });
    postingIds.value = new Set(postingIds.value).add(id);
    await postVoucher(factoryId.value, id);
    ElMessage.success('凭证已过账');
    loadData();
  } catch (e) {
    if (e === 'cancel') return;
    ElMessage({
      message: e instanceof Error ? e.message : '过账失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    const next = new Set(postingIds.value);
    next.delete(id);
    postingIds.value = next;
  }
}

// ==================== 批量过账 (Part 2 follow-up to #1228) ====================

/** 只有 DRAFT 凭证可被选中批量过账 (POSTED/VOID/REVERSED 不可再过账). */
function isRowSelectable(row: Voucher) {
  return row.status === 'DRAFT';
}

function handleSelectionChange(rows: Voucher[]) {
  selectedRows.value = rows;
}

// Rule 1 (fool-proof-design): 预先显示边界 — 借方合计, 让财务人员过账前就知道总金额.
const selectedTotalDebit = computed(() =>
  selectedRows.value.reduce((sum, r) => sum + Number(r.totalDebit || 0), 0),
);

async function handleBatchPost() {
  if (selectedRows.value.length === 0 || batchPosting.value) return;
  try {
    // Rule 2 (fool-proof-design): 携带数量 + 金额 context, 不是笼统的"确认批量过账?"
    await ElMessageBox.confirm(
      `确认批量过账选中的 ${selectedRows.value.length} 张凭证 (借方合计 ¥${formatAmount(
        selectedTotalDebit.value,
      )})？过账后不可直接编辑, 已过账的凭证会自动跳过。`,
      '批量过账确认',
      { confirmButtonText: '确认过账', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return; // 用户取消
  }
  batchPosting.value = true;
  try {
    const ids = selectedRows.value.map((r) => r.id);
    const result = await batchPostVouchers(factoryId.value, ids);
    batchResult.value = result;
    batchResultVisible.value = true;
    selectedRows.value = [];
    loadData();
  } catch (e: unknown) {
    ElMessage({
      message: e instanceof Error ? e.message : '批量过账失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    batchPosting.value = false;
  }
}

function openVoidDialog(row: Voucher) {
  voidTargetId.value = row.id;
  voidTargetRow.value = row; // Rule 2: 保存完整行供 dialog 显示
  voidReason.value = '';
  voidOtherText.value = '';
  voidDialogVisible.value = true;
}

async function submitVoid() {
  if (!voidReason.value) {
    ElMessage.warning('请选择作废原因');
    return;
  }
  if (voidReason.value === '_other' && !voidOtherText.value.trim()) {
    ElMessage.warning('请填写具体作废原因');
    return;
  }
  const finalReason =
    voidReason.value === '_other' ? voidOtherText.value.trim() : voidReason.value;
  voiding.value = true;
  try {
    await voidVoucher(factoryId.value, voidTargetId.value, finalReason);
    ElMessage.success('凭证已作废');
    voidDialogVisible.value = false;
    loadData();
  } catch (e: unknown) {
    ElMessage({
      message: e instanceof Error ? e.message : '作废失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    voiding.value = false;
  }
}

onMounted(() => loadData());
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px">
          <span style="font-size:16px;font-weight:600">凭证列表</span>
          <div class="filter-bar" style="margin-bottom:0">
            <el-input
              v-model="filterVoucherNumber"
              placeholder="搜索凭证字号 / 来源单号"
              clearable
              style="width:200px"
              @keyup.enter="handleSearch"
            />
            <el-select
              v-model="filterStatus"
              placeholder="全部状态"
              clearable
              style="width:130px"
              @change="handleSearch"
            >
              <el-option
                v-for="(v, k) in VOUCHER_STATUS_LABEL"
                :key="k"
                :label="v.text"
                :value="k"
              />
            </el-select>
            <el-select
              v-model="filterType"
              placeholder="全部类型"
              clearable
              style="width:140px"
              @change="handleSearch"
            >
              <el-option
                v-for="(label, k) in VOUCHER_TYPE_LABEL"
                :key="k"
                :label="label"
                :value="k"
              />
            </el-select>
            <el-select
              v-model="filterSourceBusinessType"
              placeholder="全部来源业务"
              clearable
              style="width:140px"
              @change="handleSearch"
            >
              <el-option
                v-for="(label, k) in SOURCE_BUSINESS_TYPE_LABEL"
                :key="k"
                :label="label"
                :value="k"
              />
            </el-select>
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button @click="handleReset">重置</el-button>
            <el-button
              v-if="canWrite"
              type="success"
              :disabled="selectedRows.length === 0"
              :loading="batchPosting"
              @click="handleBatchPost"
            >
              批量过账{{ selectedRows.length > 0 ? `(${selectedRows.length})` : '' }}
            </el-button>
          </div>
        </div>
      </template>

      <!-- 空状态 -->
      <el-empty
        v-if="!loading && tableData.length === 0"
        description="暂无凭证数据。可通过业务单据（销售/采购/报销等）自动生成凭证，或检查搜索条件。"
        style="padding:40px 0"
      />

      <el-table v-else :data="tableData" border stripe @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="45" :selectable="isRowSelectable" />
        <el-table-column prop="voucherNumber" label="凭证字号" width="160" />
        <el-table-column prop="voucherType" label="类型" width="120" align="center">
          <template #default="{ row }">
            {{ VOUCHER_TYPE_LABEL[row.voucherType as VoucherType] || row.voucherType }}
          </template>
        </el-table-column>
        <el-table-column prop="voucherDate" label="凭证日期" width="120" align="center" />
        <el-table-column prop="totalDebit" label="借方合计" width="130" align="right">
          <template #default="{ row }">{{ formatAmount(row.totalDebit) }}</template>
        </el-table-column>
        <el-table-column prop="totalCredit" label="贷方合计" width="130" align="right">
          <template #default="{ row }">{{ formatAmount(row.totalCredit) }}</template>
        </el-table-column>
        <el-table-column prop="sourceBusinessType" label="来源业务" width="130" align="center">
          <template #default="{ row }">
            <span style="font-size:12px;color:#606266">{{ row.sourceBusinessType }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              :type="VOUCHER_STATUS_LABEL[row.status as VoucherStatus]?.type || 'info'"
              size="small"
            >
              {{ VOUCHER_STATUS_LABEL[row.status as VoucherStatus]?.text || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="handleViewDetail(row.id)">
              查看
            </el-button>
            <el-button
              v-if="canWrite && row.status === 'DRAFT'"
              link
              type="success"
              size="small"
              :loading="postingIds.has(row.id)"
              @click="handlePost(row.id)"
            >
              过账
            </el-button>
            <el-button
              v-if="canWrite && (row.status === 'DRAFT' || row.status === 'POSTED')"
              link
              type="danger"
              size="small"
              @click="openVoidDialog(row)"
            >
              作废
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="pagination.total > pagination.size"
        style="margin-top:16px;justify-content:flex-end"
        :current-page="pagination.page"
        :page-size="pagination.size"
        :total="pagination.total"
        layout="total, prev, pager, next"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- 作废凭证 dialog (Rule 2: 凭证号+类型+金额 context; Rule 3: 原因 dropdown) -->
    <el-dialog
      v-model="voidDialogVisible"
      :title="`作废凭证 — ${voidTargetRow?.voucherNumber || '—'}`"
      width="480px"
      destroy-on-close
    >
      <!-- Rule 2: 凭证身份信息 -->
      <el-descriptions v-if="voidTargetRow" :column="2" border size="small" style="margin-bottom:16px">
        <el-descriptions-item label="凭证字号">{{ voidTargetRow.voucherNumber }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          {{ VOUCHER_TYPE_LABEL[voidTargetRow.voucherType as VoucherType] || voidTargetRow.voucherType }}
        </el-descriptions-item>
        <el-descriptions-item label="凭证日期">{{ voidTargetRow.voucherDate || '—' }}</el-descriptions-item>
        <el-descriptions-item label="借方合计">{{ formatAmount(voidTargetRow.totalDebit) }}</el-descriptions-item>
      </el-descriptions>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="凭证作废后状态变为「已作废」，不可恢复。"
        style="margin-bottom:16px"
      />
      <el-form label-width="90px">
        <!-- Rule 3: 标准选项 -->
        <el-form-item label="作废原因" required>
          <el-select
            v-model="voidReason"
            placeholder="请选择作废原因"
            style="width: 100%"
          >
            <el-option
              v-for="opt in VOID_REASON_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="voidReason === '_other'" label="具体原因" required>
          <el-input
            v-model="voidOtherText"
            type="textarea"
            :rows="2"
            placeholder="请填写具体作废原因"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="voidDialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :loading="voiding"
          :disabled="!voidReason || (voidReason === '_other' && !voidOtherText.trim())"
          @click="submitVoid"
        >
          确认作废
        </el-button>
      </template>
    </el-dialog>

    <!-- 批量过账结果 dialog (Part 2 follow-up to #1228): N 成功, M 失败 + 原因 -->
    <el-dialog
      v-model="batchResultVisible"
      title="批量过账结果"
      width="560px"
      destroy-on-close
    >
      <el-alert
        v-if="batchResult"
        :type="batchResult.failureCount === 0 ? 'success' : 'warning'"
        :closable="false"
        show-icon
        :title="`共 ${batchResult.total} 张：${batchResult.successCount} 成功，${batchResult.failureCount} 失败`"
        style="margin-bottom:16px"
      />
      <template v-if="batchResult && batchResult.failureCount > 0">
        <div style="margin-bottom:8px;color:#606266;font-size:13px">失败明细：</div>
        <el-table
          :data="batchResult.results.filter((r) => !r.success)"
          border
          size="small"
          max-height="300"
        >
          <el-table-column prop="voucherId" label="凭证 ID" width="220" show-overflow-tooltip />
          <el-table-column prop="message" label="失败原因" show-overflow-tooltip />
        </el-table>
      </template>
      <template v-if="batchResult && batchResult.results.some((r) => r.skipped)">
        <div style="margin-top:12px;color:#909399;font-size:12px">
          {{ batchResult.results.filter((r) => r.skipped).length }} 张已过账，已自动跳过。
        </div>
      </template>
      <template #footer>
        <el-button type="primary" @click="batchResultVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-bar { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
</style>
