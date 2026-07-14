<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormRules } from 'element-plus';
import { Plus, Search, Refresh, List } from '@element-plus/icons-vue';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('procurement'));

const loading = ref(false);
// 供应商为有界主数据(公司供应商列表不会像流水表一样无限增长), 采用一次性全量拉取 + 前端筛选/分页
// (镜像 web-admin/src/views/system/work-processes/index.vue 的既有模式), 不加后端筛选参数。
const allData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchKeyword = ref('');

// 筛选: 状态 (复用既有 isActive() 二元判定, 与下方 getStatusType/getStatusText 保持一致, 不重复定义新枚举)
const filterStatus = ref<'' | 'ACTIVE' | 'INACTIVE'>('');
const statusOptions = [
  { value: 'ACTIVE' as const, label: '合作中' },
  { value: 'INACTIVE' as const, label: '已停用' },
];

const filteredData = computed(() => allData.value.filter(row => {
  if (filterStatus.value) {
    const active = isActive(row);
    if (filterStatus.value === 'ACTIVE' && !active) return false;
    if (filterStatus.value === 'INACTIVE' && active) return false;
  }
  const kw = searchKeyword.value.trim().toLowerCase();
  if (kw) {
    const name = String(row.name || '').toLowerCase();
    const code = String(row.supplierCode || '').toLowerCase();
    if (!name.includes(kw) && !code.includes(kw)) return false;
  }
  return true;
}));

const tableData = computed(() => {
  const start = (pagination.value.page - 1) * pagination.value.size;
  return filteredData.value.slice(start, start + pagination.value.size);
});

function handleFilterChange() {
  pagination.value.page = 1;
}

function handleFilterReset() {
  filterStatus.value = '';
  pagination.value.page = 1;
}

// Dialog state
const dialogVisible = ref(false);
const dialogMode = ref<'view' | 'create' | 'edit'>('view');
const dialogTitle = computed(() => {
  if (dialogMode.value === 'create') return '新增供应商';
  if (dialogMode.value === 'edit') return '编辑供应商';
  return '查看供应商';
});
const submitting = ref(false);
const formRef = ref();

const defaultForm = {
  id: '',
  name: '',
  contactPerson: '',
  phone: '',
  address: '',
  email: '',
  bankAccount: '',
  taxId: '',
  notes: '',
  version: null as number | null,  // optimistic lock — server returns 409 on stale
};
const form = reactive({ ...defaultForm });

// 客户张权原话 (2026-07-02): "这个不要强制 一般不会放系统里面的 都是一脉单传的"
// 联系人/联系电话/地址 改为选填 — 只有供应商名称必填。手机号格式仍校验, 但仅当填了值时才检查
// (validator 在空值时直接 cb() 放行, 避免 pattern rule 对空字符串误报格式错误)。
const formRules: FormRules = {
  name: [{ required: true, message: '请输入供应商名称', trigger: 'blur' }],
  contactPerson: [],
  phone: [
    {
      validator: (_rule: unknown, value: string, callback: (err?: Error) => void) => {
        if (value && !/^1[3-9]\d{9}$/.test(value)) {
          callback(new Error('请输入正确的手机号'));
        } else {
          callback();
        }
      },
      trigger: 'blur',
    },
  ],
  address: [],
};

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    // 全量拉取(供应商目录条目数有限, 非高基数流水表), 供筛选下拉的"当前有的"选项 + 前端筛选/分页用。
    const response = await get(`/${factoryId.value}/suppliers`, {
      params: {
        page: 1,
        size: 500,
      }
    });
    if (response.success && response.data) {
      allData.value = response.data.content || [];
      pagination.value.page = 1;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.page = 1;
}

function handleRefresh() {
  searchKeyword.value = '';
  filterStatus.value = '';
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
}

function isActive(row: TableRow) {
  return row.status === 'ACTIVE' || row.isActive === true;
}

function getStatusType(row: TableRow) {
  return isActive(row) ? 'success' : 'info';
}

function getStatusText(row: TableRow) {
  return isActive(row) ? '合作中' : '已停用';
}

function resetForm() {
  Object.assign(form, defaultForm);
}

function handleCreate() {
  resetForm();
  dialogMode.value = 'create';
  dialogVisible.value = true;
}

function handleView(row: TableRow) {
  Object.assign(form, {
    id: row.id || '',
    name: row.name || '',
    contactPerson: row.contactPerson || '',
    phone: row.phone || row.contactPhone || '',
    address: row.address || '',
    email: row.email || '',
    bankAccount: row.bankAccount || '',
    taxId: row.taxId || '',
    notes: row.notes || '',
  });
  dialogMode.value = 'view';
  dialogVisible.value = true;
}

function handleEdit(row: TableRow) {
  Object.assign(form, {
    id: row.id || '',
    name: row.name || '',
    contactPerson: row.contactPerson || '',
    phone: row.phone || row.contactPhone || '',
    address: row.address || '',
    email: row.email || '',
    bankAccount: row.bankAccount || '',
    taxId: row.taxId || '',
    notes: row.notes || '',
    version: typeof row.version === 'number' ? row.version : null,
  });
  dialogMode.value = 'edit';
  dialogVisible.value = true;
}

async function handleSubmit() {
  if (!formRef.value) return;
  await formRef.value.validate();

  submitting.value = true;
  try {
    const payload: TableRow = {
      name: form.name,
      contactPerson: form.contactPerson,
      phone: form.phone,
      address: form.address,
      email: form.email,
      bankAccount: form.bankAccount,
      taxNumber: form.taxId,
      notes: form.notes,
    };

    let res;
    if (dialogMode.value === 'create') {
      res = await post(`/${factoryId.value}/suppliers`, payload);
    } else {
      if (form.version !== null && form.version !== undefined) {
        payload.version = form.version;
      }
      res = await put(`/${factoryId.value}/suppliers/${form.id}`, payload);
    }

    if (res && res.success === false) {
      ElMessage.error(res.message || '操作失败');
      return;
    }
    ElMessage.success(dialogMode.value === 'create' ? '新增成功' : '编辑成功');
    dialogVisible.value = false;
    loadData();
  } catch (error) {
    const err = error as { status?: number; actionHint?: string | null };
    // R24 P2 follow-up: only treat 409 as optimistic-lock when actionHint is null
    // (vanilla 409 from GlobalExceptionHandler). Business 409 with actionHint
    // (R18/R21/R23 invariants) is already toasted by interceptor — don't pile on
    // a wrong "并发编辑冲突" dialog.
    if (err?.status === 409 && !err.actionHint) {
      try {
        await ElMessageBox.confirm(
          '此供应商数据已被其他用户修改。点击"确定"将刷新列表并放弃当前编辑。',
          '并发编辑冲突',
          { type: 'warning', confirmButtonText: '刷新列表', cancelButtonText: '取消' }
        );
        dialogVisible.value = false;
        loadData();
      } catch {
        // user cancelled — keep dialog open
      }
    }
    // Interceptor already shows specific sticky toast; this is debug-only log.
    console.error('Submit failed:', error);
  } finally {
    submitting.value = false;
  }
}

// ==================== Issue #779: 该供应商供应的原料 (反查) ====================
// 客户要求 (May 7 part2 L222-240): "一种原料可来自多个供应商, 一个供应商可供多种原料"
// 复用 GET /suppliers/{supplierId}/history (SupplierController:285) — 返回历史供货记录,
// 含每条记录的 materialName / quantity / 时间. 按 materialName 去重得到该供应商供过哪些原料.
const materialsDialogVisible = ref(false);
const materialsForSupplier = ref<Array<{ materialName?: string; materialType?: string; quantity?: number; unit?: string; date?: string; orderNumber?: string }>>([]);
const materialsLoading = ref(false);
const materialsDialogSupplierName = ref('');
const materialsDistinct = computed(() => {
  const seen = new Map<string, number>();
  for (const h of materialsForSupplier.value) {
    const n = String(h.materialName || h.materialType || '').trim();
    if (!n) continue;
    seen.set(n, (seen.get(n) || 0) + 1);
  }
  return Array.from(seen.entries()).map(([name, count]) => ({ name, count }));
});

async function openMaterialsForSupplier(row: TableRow) {
  const supplierId = String(row.id || '').trim();
  if (!supplierId) {
    ElMessage.warning('供应商 ID 缺失');
    return;
  }
  materialsDialogSupplierName.value = String(row.name || row.supplierCode || supplierId);
  materialsForSupplier.value = [];
  materialsDialogVisible.value = true;
  materialsLoading.value = true;
  try {
    const res = await get<Array<{ materialName?: string; quantity?: number; unit?: string; date?: string; orderNumber?: string }>>(
      `/${factoryId.value}/suppliers/${supplierId}/history`,
    );
    if (res.success && Array.isArray(res.data)) {
      materialsForSupplier.value = res.data;
    }
  } catch {
    /* interceptor */
  } finally {
    materialsLoading.value = false;
  }
}

async function handleDelete(row: TableRow) {
  try {
    await ElMessageBox.confirm(`确定删除供应商「${row.name}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    });
    const delRes = await del(`/${factoryId.value}/suppliers/${row.id}`);
    if (delRes && delRes.success === false) {
      ElMessage.error(delRes.message || '删除失败');
      return;
    }
    ElMessage.success('删除成功');
    loadData();
  } catch (e) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (e !== 'cancel') console.error('Delete supplier failed:', e);
  }
}
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="供应商"
      here="向我们供货的上游卖家（如肉联厂、包材厂），用于采购订单 / 应付账款"
      other-name="销售管理 → 客户"
      other="向我们采购的下游买家（如「叮咚」「盒马」），用于销售订单 / 应收账款"
      other-path="/sales/customers"
    />
    <el-card class="page-card" shadow="never">
      <!-- 页面标题和操作 -->
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">供应商管理</span>
            <span class="data-count">共 {{ filteredData.length }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">
              新增供应商
            </el-button>
          </div>
        </div>
      </template>

      <!-- 搜索区域 -->
      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索供应商名称/编号"
          :prefix-icon="Search"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">
          搜索
        </el-button>
        <el-button :icon="Refresh" @click="handleRefresh">
          重置
        </el-button>
      </div>

      <!-- 筛选: 状态 -->
      <div class="filter-bar">
        <el-select
          v-model="filterStatus"
          placeholder="全部状态"
          clearable
          style="width: 140px"
          @change="handleFilterChange"
        >
          <el-option v-for="opt in statusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-button :icon="Refresh" @click="handleFilterReset">重置筛选</el-button>
      </div>

      <!-- 数据表格 -->
      <el-table
        :data="tableData"
        v-loading="loading"
        stripe
        border
        style="width: 100%"
      >
        <el-table-column prop="supplierCode" label="供应商编号" width="140" />
        <el-table-column prop="name" label="供应商名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="contactPerson" label="联系人" width="120" />
        <el-table-column label="联系电话" width="140">
          <template #default="{ row }">{{ row.phone || row.contactPhone || '-' }}</template>
        </el-table-column>
        <el-table-column prop="address" label="地址" min-width="200" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row)" size="small">
              {{ getStatusText(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleView(row)">查看</el-button>
            <!-- Issue #779: 反查该供应商供应的原料 -->
            <el-button type="primary" link size="small" :icon="List" @click="openMaterialsForSupplier(row)">供应原料</el-button>
            <el-button v-if="canWrite" type="primary" link size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button v-if="canWrite" type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="filteredData.length"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 新增/编辑/查看 Dialog -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px" destroy-on-close>
      <el-form
        ref="formRef"
        :model="form"
        :rules="dialogMode !== 'view' ? formRules : undefined"
        :disabled="dialogMode === 'view'"
        label-width="100px"
      >
        <el-form-item label="供应商名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入供应商名称" />
        </el-form-item>
        <el-form-item label="联系人" prop="contactPerson">
          <el-input v-model="form.contactPerson" placeholder="请输入联系人" />
        </el-form-item>
        <el-form-item label="联系电话" prop="phone">
          <el-input v-model="form.phone" placeholder="请输入联系电话" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="form.address" placeholder="请输入地址" />
        </el-form-item>
        <el-form-item label="银行账户">
          <el-input v-model="form.bankAccount" placeholder="请输入银行账户" />
        </el-form-item>
        <el-form-item label="税号">
          <el-input v-model="form.taxId" placeholder="请输入税号" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.notes" type="textarea" :rows="3" placeholder="请输入备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ dialogMode === 'view' ? '关闭' : '取消' }}</el-button>
        <el-button
          v-if="dialogMode !== 'view'"
          type="primary"
          :loading="submitting"
          @click="handleSubmit"
        >
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- Issue #779: 反查该供应商供应的原料 (基于供货历史去重 + 完整历史明细) -->
    <el-dialog
      v-model="materialsDialogVisible"
      :title="`${materialsDialogSupplierName} — 供应原料`"
      width="800px"
      destroy-on-close
    >
      <!-- 去重后的原料汇总 -->
      <div v-if="materialsDistinct.length > 0" style="margin-bottom: 16px">
        <span style="font-size: 13px; color: #909399; margin-right: 8px">已供应原料 ({{ materialsDistinct.length }} 种):</span>
        <el-tag
          v-for="m in materialsDistinct"
          :key="m.name"
          size="small"
          style="margin: 2px 4px"
          type="success"
          effect="plain"
        >
          {{ m.name }} ({{ m.count }} 次)
        </el-tag>
      </div>

      <!-- 完整供货历史 -->
      <el-table
        v-loading="materialsLoading"
        :data="materialsForSupplier"
        empty-text="该供应商暂无供货历史"
        stripe
        border
        size="small"
        max-height="400"
      >
        <el-table-column prop="date" label="供货日期" width="120">
          <template #default="{ row }">{{ row.date || '-' }}</template>
        </el-table-column>
        <el-table-column label="原料" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.materialName || row.materialType || '-' }}</template>
        </el-table-column>
        <el-table-column label="数量" width="120" align="right">
          <template #default="{ row }">
            {{ row.quantity != null ? `${row.quantity} ${row.unit || ''}` : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="orderNumber" label="采购单号" width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.orderNumber || '-' }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="materialsDialogVisible = false">关闭</el-button>
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

  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;

    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-color-primary, #303133);
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, #909399);
    }
  }

  .header-right {
    display: flex;
    gap: 8px;
  }
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.filter-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.el-table {
  flex: 1;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}
</style>
