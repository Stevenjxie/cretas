<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Search } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  createManufacturer,
  deleteManufacturer,
  listManufacturers,
  updateManufacturer,
  type ManufacturerPayload,
  type ManufacturerRegistry,
} from '@/api/manufacturer';
import type { FormInstance } from 'element-plus';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));

const loading = ref(false);
const tableData = ref<ManufacturerRegistry[]>([]);
const keyword = ref('');
const activeOnly = ref(false);

const dialogVisible = ref(false);
const dialogTitle = ref('新增厂商登记');
const formRef = ref<FormInstance>();
const saving = ref(false);
const editingId = ref<string | null>(null);
const form = reactive<ManufacturerPayload>({
  code: '',
  name: '',
  originPlace: '',
  isActive: true,
  remark: '',
});

const rules = {
  code: [{ required: true, message: '请输入厂号编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入厂商名称', trigger: 'blur' }],
};

const filteredRows = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  if (!q) return tableData.value;
  return tableData.value.filter((row) =>
    [row.code, row.name, row.originPlace, row.remark]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(q))
  );
});

onMounted(loadData);

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listManufacturers(factoryId.value, activeOnly.value);
    tableData.value = res.success && Array.isArray(res.data) ? res.data : [];
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  Object.assign(form, {
    code: '',
    name: '',
    originPlace: '',
    isActive: true,
    remark: '',
  });
}

function handleCreate() {
  editingId.value = null;
  dialogTitle.value = '新增厂商登记';
  resetForm();
  dialogVisible.value = true;
}

function handleEdit(row: ManufacturerRegistry) {
  editingId.value = row.id;
  dialogTitle.value = '编辑厂商登记';
  Object.assign(form, {
    code: row.code,
    name: row.name,
    originPlace: row.originPlace || '',
    isActive: row.isActive,
    remark: row.remark || '',
  });
  dialogVisible.value = true;
}

async function handleSubmit() {
  if (!factoryId.value || !formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    const payload = {
      ...form,
      code: String(form.code || '').trim(),
      name: String(form.name || '').trim(),
      originPlace: form.originPlace ? String(form.originPlace).trim() : null,
      remark: form.remark ? String(form.remark).trim() : null,
    };
    const res = editingId.value
      ? await updateManufacturer(factoryId.value, editingId.value, payload)
      : await createManufacturer(factoryId.value, payload);
    if (res.success) {
      ElMessage.success(editingId.value ? '厂商登记已更新' : '厂商登记已创建');
      dialogVisible.value = false;
      await loadData();
    }
  } catch (error) {
    const message = String((error as Error)?.message || '厂商登记保存失败');
    ElMessage({ message, type: 'error', duration: 0, showClose: true });
  } finally {
    saving.value = false;
  }
}

async function handleDelete(row: ManufacturerRegistry) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(`确认停用厂号 ${row.code}？`, '停用厂商登记', {
      type: 'warning',
      confirmButtonText: '停用',
      cancelButtonText: '取消',
    });
    const res = await deleteManufacturer(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success('厂商登记已停用');
      await loadData();
    }
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    const message = String((error as Error)?.message || '停用失败');
    ElMessage({ message, type: 'error', duration: 0, showClose: true });
  }
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>厂商登记表</h2>
        <p>维护工厂级厂号编码、厂商名称和默认产地</p>
      </div>
      <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">新增厂商</el-button>
    </div>

    <el-card shadow="never" class="content-card">
      <div class="toolbar">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索厂号、厂商、产地" />
        <el-checkbox v-model="activeOnly" @change="loadData">仅显示启用</el-checkbox>
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="filteredRows" border stripe>
        <el-table-column prop="code" label="厂号" width="130" show-overflow-tooltip />
        <el-table-column prop="name" label="厂商名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="originPlace" label="默认产地" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.originPlace || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
              {{ row.isActive ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
        <el-table-column v-if="canWrite" label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
        <el-form-item label="厂号" prop="code">
          <el-input v-model="form.code" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="厂商名称" prop="name">
          <el-input v-model="form.name" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="默认产地">
          <el-input v-model="form.originPlace" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.isActive" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.page-container {
  padding: 20px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    font-size: 20px;
    color: #1a2332;
  }

  p {
    margin: 4px 0 0;
    color: #7a8599;
    font-size: 13px;
  }
}

.content-card {
  border-radius: 10px;
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(220px, 320px) auto auto;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}
</style>
