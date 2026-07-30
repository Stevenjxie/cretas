<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus';
import { Download, MoreFilled, Plus, Refresh, Search, Upload } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import {
  createSupplier,
  downloadSupplierTemplate,
  listSuppliers,
  updateSupplierStatus,
  type SupplierRecord,
  type SupplierSavePayload,
} from '@/api/supplierManagement';
import SupplierDetailDrawer from './SupplierDetailDrawer.vue';
import SupplierImportDialog from './SupplierImportDialog.vue';
import {
  normalizeSupplierPayload,
  showShortNameWarning,
  supplierFormRules,
  supplierProfileComplete,
  supplierStatus,
  supplierStatusLabel,
} from './supplierModel';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId || '');
const canWrite = computed(() => permissionStore.canWrite('procurement'));

const loading = ref(false);
const allData = ref<SupplierRecord[]>([]);
const pagination = reactive({ page: 1, size: 10 });
const searchKeyword = ref('');
const filterStatus = ref<'' | 'ACTIVE' | 'INACTIVE'>('');
const detailVisible = ref(false);
const selectedSupplier = ref<SupplierRecord | null>(null);
const detailInitialTab = ref('profile');
const importVisible = ref(false);
const createVisible = ref(false);
const creating = ref(false);
const createFormRef = ref<FormInstance>();
const createForm = reactive<SupplierSavePayload>({
  name: '', shortName: '', contactPerson: '', phone: '', address: '', email: '', bankAccount: '', taxNumber: '', notes: '',
});

const filteredData = computed(() => allData.value.filter((supplier) => {
  if (filterStatus.value && supplierStatus(supplier) !== filterStatus.value) return false;
  const keyword = searchKeyword.value.trim().toLowerCase();
  if (!keyword) return true;
  // 简称必须参与过滤 —— 客户要简称就是为了打简称找供应商
  return [supplier.name, supplier.shortName, supplier.supplierCode, supplier.code, supplier.contactPerson]
    .some((value) => String(value ?? '').toLowerCase().includes(keyword));
}));
const tableData = computed(() => {
  const start = (pagination.page - 1) * pagination.size;
  return filteredData.value.slice(start, start + pagination.size);
});

onMounted(loadData);

async function loadData(): Promise<void> {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    allData.value = await listSuppliers(factoryId.value);
    if ((pagination.page - 1) * pagination.size >= allData.value.length) pagination.page = 1;
  } finally {
    loading.value = false;
  }
}

function resetFilters(): void {
  searchKeyword.value = '';
  filterStatus.value = '';
  pagination.page = 1;
}

function openCreate(): void {
  // 必须逐字段重置(含 shortName): 漏一个字段, 重开弹窗就带着上次的残留值,
  // 而简称是工厂内唯一的 —— 残留会直接撞 409。
  Object.assign(createForm, {
    name: '', shortName: '', contactPerson: '', phone: '', address: '', email: '', bankAccount: '', taxNumber: '', notes: '',
  });
  createVisible.value = true;
}

async function submitCreate(): Promise<void> {
  if (!createFormRef.value) return;
  await createFormRef.value.validate();
  creating.value = true;
  try {
    const created = await createSupplier(factoryId.value, normalizeSupplierPayload(createForm));
    ElMessage.success('供应商已创建');
    // 简称重名只提示不拦 (Steve 2026-07-30): 已经保存成功了, 所以是 warning 不是 error,
    // 但要 sticky —— 一闪而过的提示等于没提示, 用户永远不会去改那个简称。
    showShortNameWarning(created?.shortNameWarning);
    createVisible.value = false;
    await loadData();
  } finally {
    creating.value = false;
  }
}

function openDetail(supplier: SupplierRecord, tab?: string): void {
  selectedSupplier.value = supplier;
  detailInitialTab.value = tab || 'profile';
  detailVisible.value = true;
}

async function changeStatus(supplier: SupplierRecord): Promise<void> {
  const active = supplierStatus(supplier) !== 'ACTIVE';
  const action = active ? '恢复合作' : '暂停合作';
  try {
    const result = await ElMessageBox.prompt(
      active ? '恢复后可继续创建采购业务，请填写原因。' : '暂停后禁止新增采购业务，历史记录仍可查看，请填写原因。',
      action,
      { inputPlaceholder: `请输入${action}原因`, inputValidator: (value) => Boolean(value?.trim()) || '原因不能为空' },
    );
    await updateSupplierStatus(factoryId.value, supplier, active, result.value);
    ElMessage.success(`${action}成功`);
    await loadData();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') console.error('Supplier status update failed', error);
  }
}

function handleRowCommand(command: string, supplier: SupplierRecord): void {
  if (command === 'materials') openDetail(supplier, 'materials');
  if (command === 'status') void changeStatus(supplier);
}

async function handleDownloadTemplate(): Promise<void> {
  await downloadSupplierTemplate(factoryId.value);
  ElMessage.success('供应商标准模板已下载');
}
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="供应商"
      here="向我们供货的上游卖家（如肉联厂、包材厂），用于采购订单 / 应付账款"
      other-name="销售管理 → 客户"
      other="向我们采购的下游买家，用于销售订单 / 应收账款"
      other-path="/sales/customers"
    />
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">供应商管理</span>
            <span class="data-count">共 {{ filteredData.length }} 条记录</span>
          </div>
          <div v-if="canWrite" class="header-right">
            <el-button :icon="Download" @click="handleDownloadTemplate">下载标准模板</el-button>
            <el-button :icon="Upload" @click="importVisible = true">导入供应商</el-button>
            <el-button type="primary" :icon="Plus" @click="openCreate">新增供应商</el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索供应商名称/简称/编号/联系人"
          :prefix-icon="Search"
          clearable
          @keyup.enter="pagination.page = 1"
        />
        <el-select v-model="filterStatus" placeholder="全部状态" clearable @change="pagination.page = 1">
          <el-option label="合作中" value="ACTIVE" />
          <el-option label="暂停合作" value="INACTIVE" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="pagination.page = 1">搜索</el-button>
        <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" stripe border class="supplier-table">
        <el-table-column prop="supplierCode" label="供应商编号" width="150">
          <template #default="{ row }">{{ row.supplierCode || row.code || '-' }}</template>
        </el-table-column>
        <el-table-column label="供应商名称" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.shortName" type="primary" size="small" effect="plain" class="short-name-tag">
              {{ row.shortName }}
            </el-tag>
            <span>{{ row.name }}</span>
            <el-tag v-if="!supplierProfileComplete(row)" type="warning" size="small" effect="plain" class="incomplete-tag">
              资料不完整
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="联系人" width="130">
          <template #default="{ row }">{{ row.contactPerson || '资料不完整' }}</template>
        </el-table-column>
        <el-table-column label="联系电话" width="160">
          <template #default="{ row }">{{ row.phone || row.contactPhone || '资料不完整' }}</template>
        </el-table-column>
        <el-table-column label="地址" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.address || '资料不完整' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="supplierStatus(row) === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ supplierStatusLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link @click="openDetail(row)">详情</el-button>
            <el-dropdown v-if="canWrite" trigger="click" @command="handleRowCommand($event, row)">
              <el-button link :icon="MoreFilled">更多</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="materials">供应原料</el-dropdown-item>
                  <el-dropdown-item command="status" :divided="true">
                    {{ supplierStatus(row) === 'ACTIVE' ? '暂停合作' : '恢复合作' }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="filteredData.length"
          layout="total, sizes, prev, pager, next, jumper"
        />
      </div>
    </el-card>

    <el-dialog v-model="createVisible" title="新增供应商" width="620px" destroy-on-close :close-on-click-modal="false">
      <el-alert title="供应商名称、联系人、联系电话和地址为必填项" type="info" :closable="false" show-icon />
      <el-form ref="createFormRef" :model="createForm" :rules="supplierFormRules" label-width="110px" class="create-form">
        <el-form-item label="供应商名称" prop="name"><el-input v-model="createForm.name" maxlength="200" /></el-form-item>
        <el-form-item label="简称" prop="shortName">
          <el-input v-model="createForm.shortName" maxlength="50" show-word-limit placeholder="选填，下拉里优先显示，例如「飞熊」" />
          <div class="field-hint">留空时下拉显示全称。同一工厂内简称不能重复。</div>
        </el-form-item>
        <el-form-item label="联系人" prop="contactPerson"><el-input v-model="createForm.contactPerson" maxlength="100" /></el-form-item>
        <el-form-item label="联系电话" prop="phone"><el-input v-model="createForm.phone" placeholder="大陆手机号或带区号座机，可含分机" maxlength="40" /></el-form-item>
        <el-form-item label="地址" prop="address"><el-input v-model="createForm.address" type="textarea" maxlength="500" show-word-limit /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="createForm.email" maxlength="100" /></el-form-item>
        <el-form-item label="银行账户"><el-input v-model="createForm.bankAccount" maxlength="100" /></el-form-item>
        <el-form-item label="税号"><el-input v-model="createForm.taxNumber" maxlength="50" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="createForm.notes" type="textarea" maxlength="5000" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <SupplierImportDialog v-model="importVisible" :factory-id="factoryId" @imported="loadData" />
    <SupplierDetailDrawer
      v-model="detailVisible"
      :factory-id="factoryId"
      :supplier="selectedSupplier"
      :can-write="canWrite"
      :initial-tab="detailInitialTab"
      @changed="loadData"
    />
  </div>
</template>

<style scoped lang="scss">
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; border-radius: 10px; }
.card-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.header-left, .header-right, .search-bar { display: flex; align-items: center; gap: 8px; }
.page-title { font-size: 16px; font-weight: 600; color: var(--el-text-color-primary); }
.data-count { color: var(--el-text-color-secondary); font-size: 13px; }
.search-bar { margin-bottom: 16px; flex-wrap: wrap; }
.search-bar .el-input { width: 300px; }
.search-bar .el-select { width: 150px; }
.supplier-table { width: 100%; }
.incomplete-tag { margin-left: 8px; }
.short-name-tag { margin-right: 8px; }
.field-hint { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; }
.pagination-wrapper { display: flex; justify-content: flex-end; padding-top: 16px; }
.create-form { margin-top: 16px; }
@media (max-width: 1100px) {
  .card-header { align-items: flex-start; flex-direction: column; }
  .header-right { width: 100%; flex-wrap: wrap; }
}
</style>
