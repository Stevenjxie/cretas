<script setup lang="ts">
/**
 * 销售运营报价管理 — V3 P0-4 (Round 2 Agent B 8.5/10 设计落地)
 *
 * 客户原话 (会议 1670-1750s):
 * "样品过了以后, 像类似于提交一个审批, 对应的人员去报价的;
 *  运营报完价以后调过去审批审批完 ok 没问题, 然后给到销售这边."
 *
 * 状态机 (3 段式):
 *   PENDING_QUOTE → 销售运营录价 → PENDING_APPROVAL → 主管审批 → APPROVED
 *                                                              ↘ REJECTED → revise → PENDING_QUOTE
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import { handleCatchError } from '@/utils/errorToast';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('sales'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const submitting = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const statusFilter = ref<string>('');

// 状态枚举
const statusMap: Record<string, { text: string; type: 'info' | 'warning' | 'success' | 'danger' | '' }> = {
  DRAFT: { text: '草稿', type: 'info' },
  PENDING_QUOTE: { text: '待录价', type: 'warning' },
  PENDING_APPROVAL: { text: '待审批', type: 'warning' },
  APPROVED: { text: '已批准', type: 'success' },
  REJECTED: { text: '已驳回', type: 'danger' },
  EXPIRED: { text: '已过期', type: 'info' },
};

// 创建报价对话框
const createDialogVisible = ref(false);
const createForm = ref({
  sampleId: '',
  customerId: '',
  productTypeId: '',
  bomId: '',
  quotedByUserId: null as number | null,
  quotedByName: '',
  remarks: '',
});

// 录价对话框
const submitPriceDialogVisible = ref(false);
const submitPriceForm = ref({
  id: '',
  quoteNo: '',
  quoteType: 'FIXED',
  unitPrice: 0,
  unit: 'kg',
  minOrderQty: 0,
  costPrice: 0,
  validUntil: '',
  remarks: '',
});

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: TableRow = {
      page: pagination.value.page,
      size: pagination.value.size,
    };
    if (statusFilter.value) params.status = statusFilter.value;
    const res = await get(`/${factoryId.value}/quotes`, { params });
    if (res.success) {
      const data = res.data || {};
      tableData.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载报价列表失败');
  } finally {
    loading.value = false;
  }
}

function handleStatusChange() {
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

// ──────────────────────────────────────────────
// 创建报价 (研发或销售触发)
// ──────────────────────────────────────────────
function openCreateDialog() {
  createForm.value = {
    sampleId: '',
    customerId: '',
    productTypeId: '',
    bomId: '',
    quotedByUserId: null,
    quotedByName: '',
    remarks: '',
  };
  createDialogVisible.value = true;
}

async function handleCreate() {
  if (submitting.value) return;
  if (!createForm.value.customerId || !createForm.value.productTypeId) {
    return ElMessage.warning('客户和产品必填');
  }
  if (!createForm.value.quotedByName) {
    return ElMessage.warning('销售运营报价人必填 (客户原话: 必须指定到人不是岗位)');
  }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/quotes`, createForm.value);
    if (res.success) {
      ElMessage.success(`报价单已创建: ${res.data?.quoteNo}, 已分配给 ${createForm.value.quotedByName}`);
      createDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '创建失败');
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '创建失败, 请检查网络');
  } finally {
    submitting.value = false;
  }
}

// ──────────────────────────────────────────────
// 录价 (PENDING_QUOTE → PENDING_APPROVAL)
// ──────────────────────────────────────────────
function openSubmitPriceDialog(row: TableRow) {
  submitPriceForm.value = {
    id: String(row.id),
    quoteNo: String(row.quoteNo),
    quoteType: String(row.quoteType || 'FIXED'),
    unitPrice: Number(row.unitPrice || 0),
    unit: String(row.unit || 'kg'),
    minOrderQty: Number(row.minOrderQty || 0),
    costPrice: Number(row.costPrice || 0),
    validUntil: row.validUntil ? String(row.validUntil) : '',
    remarks: String(row.remarks || ''),
  };
  submitPriceDialogVisible.value = true;
}

async function handleSubmitPrice() {
  if (submitting.value) return;
  if (!submitPriceForm.value.unitPrice || submitPriceForm.value.unitPrice <= 0) {
    return ElMessage.warning('单价必须 > 0');
  }
  submitting.value = true;
  try {
    const { id, quoteNo: _qn, ...body } = submitPriceForm.value;
    const res = await put(`/${factoryId.value}/quotes/${id}/submit-price`, body);
    if (res.success) {
      const margin = res.data?.marginRate ? `(毛利率 ${(Number(res.data.marginRate) * 100).toFixed(2)}%)` : '';
      ElMessage.success(`报价已提交审批 ${margin}`);
      submitPriceDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '提交失败');
    }
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  } finally {
    submitting.value = false;
  }
}

// ──────────────────────────────────────────────
// 审批 / 驳回 / 修订
// ──────────────────────────────────────────────
async function handleApprove(row: TableRow) {
  if (submitting.value) return;
  try {
    const { value: comment } = await ElMessageBox.prompt(
      `审批通过报价 ${row.quoteNo}? 单价 ${formatAmount(Number(row.unitPrice))}/${row.unit}, 可选填备注:`,
      '主管审批',
      { confirmButtonText: '通过', cancelButtonText: '取消', inputPlaceholder: '(选填)' }
    );
    submitting.value = true;
    const res = await put(`/${factoryId.value}/quotes/${row.id}/approve`, {
      approverName: '主管',
      comment: comment || '',
    });
    if (res.success) {
      ElMessage.success('已审批通过, 销售可下单');
      loadData();
    } else {
      ElMessage.error(res.message || '审批失败');
    }
  } catch { /* cancelled */ }
  finally { submitting.value = false; }
}

async function handleReject(row: TableRow) {
  if (submitting.value) return;
  try {
    const { value: reason } = await ElMessageBox.prompt('请填写驳回原因', '主管驳回', {
      confirmButtonText: '驳回',
      cancelButtonText: '取消',
      inputValidator: (v) => !!v || '驳回原因必填',
    });
    submitting.value = true;
    const res = await put(`/${factoryId.value}/quotes/${row.id}/reject`, {
      approverName: '主管',
      reason,
    });
    if (res.success) {
      ElMessage.success('已驳回');
      loadData();
    } else {
      ElMessage.error(res.message || '驳回失败');
    }
  } catch { /* cancelled */ }
  finally { submitting.value = false; }
}

async function handleRevise(row: TableRow) {
  if (submitting.value) return;
  try {
    const { value: reason } = await ElMessageBox.prompt(
      '客户砍价后重新报价? 填写修订原因:',
      '修订报价',
      { confirmButtonText: '确认修订', cancelButtonText: '取消', inputValidator: (v) => !!v || '修订原因必填' }
    );
    submitting.value = true;
    const res = await put(`/${factoryId.value}/quotes/${row.id}/revise`, { reason });
    if (res.success) {
      ElMessage.success(`已发起重新报价 (第 ${res.data?.revisionCount || 1} 次修订)`);
      loadData();
    } else {
      ElMessage.error(res.message || '修订失败');
    }
  } catch { /* cancelled */ }
  finally { submitting.value = false; }
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">销售运营报价</span>
            <el-tag type="info" size="small">共 {{ pagination.total }} 条</el-tag>
          </div>
          <div class="header-right">
            <el-select v-model="statusFilter" placeholder="按状态筛选" clearable style="width: 160px"
                       @change="handleStatusChange">
              <el-option label="待录价" value="PENDING_QUOTE" />
              <el-option label="待审批" value="PENDING_APPROVAL" />
              <el-option label="已批准" value="APPROVED" />
              <el-option label="已驳回" value="REJECTED" />
              <el-option label="已过期" value="EXPIRED" />
            </el-select>
            <el-button :icon="Refresh" @click="loadData">刷新</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreateDialog">
              新建报价
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        title="客户原话: 销售运营部统一报价, 必须指定到人不是岗位"
        description="3 段式: 提交 PENDING_QUOTE → 录价 PENDING_APPROVAL → 主管审批 APPROVED → 销售可下单"
        style="margin-bottom: 16px"
      />

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="quoteNo" label="报价单号" min-width="170" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status]?.type || 'info'" size="small">
              {{ statusMap[row.status]?.text || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="customerId" label="客户" min-width="120" show-overflow-tooltip />
        <el-table-column prop="productTypeId" label="产品" min-width="120" show-overflow-tooltip />
        <el-table-column label="报价类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.quoteType === 'FIXED'" size="small" type="success">固定</el-tag>
            <el-tag v-else-if="row.quoteType === 'NEGOTIABLE'" size="small" type="warning">议价</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" label="单价" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.unitPrice">{{ formatAmount(row.unitPrice) }}/{{ row.unit }}</span>
            <span v-else style="color:#909399">未报价</span>
          </template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" label="毛利率" width="90" align="right">
          <template #default="{ row }">
            <span v-if="row.marginRate" :style="{ color: Number(row.marginRate) >= 0.3 ? '#67c23a' : '#e6a23c' }">
              {{ (Number(row.marginRate) * 100).toFixed(1) }}%
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="quotedByName" label="录价人" width="120">
          <template #default="{ row }">{{ row.quotedByName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="approverName" label="审批人" width="120">
          <template #default="{ row }">{{ row.approverName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="validUntil" label="有效期至" width="110" />
        <el-table-column label="修订" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.revisionCount && row.revisionCount > 0" size="small" type="warning">
              v{{ row.revisionCount }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" align="center" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING_QUOTE' && canWrite" type="primary" link size="small"
                       @click="openSubmitPriceDialog(row)">录价</el-button>
            <el-button v-if="row.status === 'PENDING_APPROVAL' && canWrite" type="success" link size="small"
                       @click="handleApprove(row)">通过</el-button>
            <el-button v-if="row.status === 'PENDING_APPROVAL' && canWrite" type="danger" link size="small"
                       @click="handleReject(row)">驳回</el-button>
            <el-button v-if="['REJECTED','APPROVED'].includes(row.status) && canWrite" type="warning" link size="small"
                       @click="handleRevise(row)">修订</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          :page-size="pagination.size"
          :total="pagination.total"
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <!-- ─── 创建报价对话框 ─── -->
    <el-dialog v-model="createDialogVisible" title="新建销售运营报价" width="600px" destroy-on-close>
      <el-form label-width="120px">
        <el-form-item label="客户 ID" required>
          <el-input v-model="createForm.customerId" placeholder="选填客户 UUID 或编码" />
        </el-form-item>
        <el-form-item label="产品 ID" required>
          <el-input v-model="createForm.productTypeId" placeholder="如 PT-F001-001" />
        </el-form-item>
        <el-form-item label="样品 ID">
          <el-input v-model="createForm.sampleId" placeholder="可选, 报价由样品驱动" />
        </el-form-item>
        <el-form-item label="BOM ID">
          <el-input v-model="createForm.bomId" placeholder="可选, 用于成本参考" />
        </el-form-item>
        <el-form-item label="录价人" required>
          <el-input v-model="createForm.quotedByName" placeholder="销售运营部具体的人 (不是岗位)" />
        </el-form-item>
        <el-form-item label="录价人 ID">
          <el-input-number v-model="createForm.quotedByUserId" :min="0" placeholder="user_id" style="width: 200px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remarks" type="textarea" :rows="3" placeholder="如: 客户老王肉制品厂咨询" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">提交</el-button>
      </template>
    </el-dialog>

    <!-- ─── 录价对话框 ─── -->
    <el-dialog v-model="submitPriceDialogVisible" :title="`录价 — ${submitPriceForm.quoteNo}`" width="560px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="报价类型">
          <el-radio-group v-model="submitPriceForm.quoteType">
            <el-radio value="FIXED">固定报价</el-radio>
            <el-radio value="NEGOTIABLE">可议价</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="单价" required>
          <el-input-number v-model="submitPriceForm.unitPrice" :min="0" :precision="2" style="width: 200px" />
          <el-input v-model="submitPriceForm.unit" style="width: 80px; margin-left: 8px" placeholder="单位" />
        </el-form-item>
        <el-form-item label="最小起订量">
          <el-input-number v-model="submitPriceForm.minOrderQty" :min="0" :precision="3" style="width: 200px" />
        </el-form-item>
        <el-form-item v-if="canViewPrice" label="成本单价">
          <el-input-number v-model="submitPriceForm.costPrice" :min="0" :precision="2" style="width: 200px" />
          <span style="margin-left: 12px; color:#909399; font-size:12px">内部参考, 不展示给客户</span>
        </el-form-item>
        <el-form-item label="有效期至">
          <el-date-picker v-model="submitPriceForm.validUntil" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="submitPriceForm.remarks" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitPriceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitPrice">提交审批</el-button>
      </template>
    </el-dialog>
  </div>
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
  .header-right { display: flex; gap: 8px; align-items: center; }
}
.pagination-wrapper { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
