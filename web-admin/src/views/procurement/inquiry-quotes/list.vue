<script setup lang="ts">
/**
 * P-NUCLEAR-1 (28-Backlog #30) — 核价单列表 (PC).
 *
 * 三阶段 pipeline:
 *   DRAFT → INQUIRING → QUOTED → CONVERTED (生成 PO)
 *
 * UX 防呆: 状态用 el-tag 颜色区分, CONVERTED 行点击进 PO 详情.
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';
import { Refresh, Plus } from '@element-plus/icons-vue';
import {
  listInquiries,
  createInquiry,
  type InquiryQuote,
  type InquiryQuoteStatus,
} from '@/api/inquiryQuote';

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const rows = ref<InquiryQuote[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });

// Create dialog state
const createDialogVisible = ref(false);
const createForm = ref({
  title: '',
  materialTypeId: '',
  materialName: '',
  specification: '',
  quantity: 0 as number,
  unit: 'kg',
  inquiryDate: new Date().toISOString().slice(0, 10),
  requiredDate: '',
  remark: '',
});
const createSubmitting = ref(false);

const STATUS_TAG: Record<InquiryQuoteStatus, { type: 'info' | 'warning' | 'primary' | 'success' | 'danger'; label: string }> = {
  DRAFT: { type: 'info', label: '草稿' },
  INQUIRING: { type: 'warning', label: '询价中' },
  QUOTED: { type: 'primary', label: '已报价' },
  SELECTED: { type: 'primary', label: '已选定' },
  CONVERTED: { type: 'success', label: '已转采购' },
  CANCELLED: { type: 'info', label: '已取消' },
};

async function load() {
  if (!factoryId.value) {
    ElMessage.warning('未登录或缺少工厂上下文');
    return;
  }
  loading.value = true;
  try {
    const res = await listInquiries(factoryId.value, {
      page: pagination.value.page,
      size: pagination.value.size,
    });
    if (res.success && res.data) {
      rows.value = res.data.content;
      pagination.value.total = res.data.totalElements;
    } else {
      rows.value = [];
      pagination.value.total = 0;
    }
  } finally {
    loading.value = false;
  }
}

function goDetail(row: InquiryQuote) {
  router.push({ name: 'InquiryQuoteDetail', params: { id: row.id } });
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  load();
}

function openCreate() {
  createForm.value = {
    title: '',
    materialTypeId: '',
    materialName: '',
    specification: '',
    quantity: 0,
    unit: 'kg',
    inquiryDate: new Date().toISOString().slice(0, 10),
    requiredDate: '',
    remark: '',
  };
  createDialogVisible.value = true;
}

async function submitCreate() {
  if (!factoryId.value) return;
  if (!createForm.value.materialTypeId) {
    ElMessage.warning('请填写物料类型 ID');
    return;
  }
  if (!createForm.value.quantity || createForm.value.quantity <= 0) {
    ElMessage.warning('请填写询价数量 (> 0)');
    return;
  }
  if (!createForm.value.unit) {
    ElMessage.warning('请填写单位');
    return;
  }
  if (!createForm.value.inquiryDate) {
    ElMessage.warning('请选择询价日期');
    return;
  }
  createSubmitting.value = true;
  try {
    const res = await createInquiry(factoryId.value, {
      title: createForm.value.title || undefined,
      materialTypeId: createForm.value.materialTypeId,
      materialName: createForm.value.materialName || undefined,
      specification: createForm.value.specification || undefined,
      quantity: createForm.value.quantity,
      unit: createForm.value.unit,
      inquiryDate: createForm.value.inquiryDate,
      requiredDate: createForm.value.requiredDate || undefined,
      remark: createForm.value.remark || undefined,
    });
    if (res.success && res.data) {
      ElMessage.success(res.message || `核价单 ${res.data.inquiryNumber} 创建成功`);
      createDialogVisible.value = false;
      load();
    }
  } finally {
    createSubmitting.value = false;
  }
}

function formatPrice(v: number | null | undefined): string {
  if (v == null) return '-';
  return `¥${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

onMounted(load);
</script>

<template>
  <div class="inquiry-quote-list">
    <div class="page-header">
      <div>
        <h2 class="title">核价单</h2>
        <p class="subtitle">询价 → 核价 → 采购 三阶段 pipeline (P-NUCLEAR-1)</p>
      </div>
      <div class="actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建核价单</el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      stripe
      style="width: 100%"
      empty-text="暂无核价单"
      @row-click="goDetail"
    >
      <el-table-column prop="inquiryNumber" label="核价单号" min-width="180" />
      <el-table-column prop="materialName" label="物料" min-width="160">
        <template #default="{ row }">
          {{ row.materialName || row.materialTypeId }}
          <span v-if="row.specification" class="spec">({{ row.specification }})</span>
        </template>
      </el-table-column>
      <el-table-column prop="quantity" label="数量" min-width="100" align="right">
        <template #default="{ row }">{{ row.quantity }} {{ row.unit }}</template>
      </el-table-column>
      <el-table-column prop="inquiryDate" label="询价日期" min-width="120" />
      <el-table-column prop="requiredDate" label="期望到货" min-width="120">
        <template #default="{ row }">{{ row.requiredDate || '-' }}</template>
      </el-table-column>
      <el-table-column label="中标供应商" min-width="160">
        <template #default="{ row }">
          {{ row.selectedSupplierName || row.selectedSupplierId || '-' }}
        </template>
      </el-table-column>
      <el-table-column label="中标价" min-width="120" align="right">
        <template #default="{ row }">{{ formatPrice(row.selectedUnitPrice) }}</template>
      </el-table-column>
      <el-table-column label="状态" min-width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status as InquiryQuoteStatus]?.type || 'info'" effect="light">
            {{ STATUS_TAG[row.status as InquiryQuoteStatus]?.label || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="采购单" min-width="160">
        <template #default="{ row }">
          {{ row.purchaseOrderNumber || '-' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" align="center" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="goDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="pagination.total > 0"
      class="pagination"
      :current-page="pagination.page"
      :page-size="pagination.size"
      :total="pagination.total"
      layout="total, prev, pager, next, jumper"
      background
      @current-change="handlePageChange"
    />

    <!-- 新建核价单 dialog -->
    <el-dialog v-model="createDialogVisible" title="新建核价单" width="560px" :close-on-click-modal="false">
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="标题">
          <el-input v-model="createForm.title" placeholder="选填, 如 Q2 辣椒采购询价" maxlength="200" />
        </el-form-item>
        <el-form-item label="物料类型 ID" required>
          <el-input v-model="createForm.materialTypeId" placeholder="raw_material_types.id" maxlength="191" />
        </el-form-item>
        <el-form-item label="物料名称">
          <el-input v-model="createForm.materialName" placeholder="如 辣椒" maxlength="200" />
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="createForm.specification" placeholder="选填" maxlength="200" />
        </el-form-item>
        <el-form-item label="数量 / 单位" required>
          <el-input-number v-model="createForm.quantity" :min="0" :precision="4" />
          <el-input v-model="createForm.unit" placeholder="单位(必填)" style="width: 100px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="询价日期" required>
          <el-date-picker v-model="createForm.inquiryDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="期望到货">
          <el-date-picker v-model="createForm.requiredDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" maxlength="5000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.inquiry-quote-list {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 4px;
  color: #303133;
}
.page-header .subtitle {
  font-size: 13px;
  color: #909399;
  margin: 0;
}
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
.spec {
  color: #909399;
  font-size: 12px;
  margin-left: 4px;
}
:deep(.el-table__row) {
  cursor: pointer;
}
</style>
