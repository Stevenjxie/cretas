<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Edit, Delete as DeleteIcon, Search, Refresh } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';

const router = useRouter();

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('equipment'));

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchKeyword = ref('');

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/equipment`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchKeyword.value || undefined
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error) {
    console.error('加载失败:', error);
    ElMessage.error('加载数据失败');
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchKeyword.value = '';
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    RUNNING: 'success',
    ACTIVE: 'success',
    ONLINE: 'success',
    IDLE: 'info',
    STANDBY: 'info',
    INACTIVE: 'info',
    OFFLINE: 'info',
    DISABLED: 'info',
    MAINTENANCE: 'warning',
    FAULT: 'danger',
    ERROR: 'danger',
    STOPPED: 'warning',
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    RUNNING: '运行中',
    ACTIVE: '运行中',
    ONLINE: '在线',
    IDLE: '空闲',
    STANDBY: '待机',
    INACTIVE: '停用',
    OFFLINE: '离线',
    DISABLED: '已禁用',
    MAINTENANCE: '维护中',
    FAULT: '故障',
    ERROR: '故障',
    STOPPED: '已停止',
  };
  return map[status?.toUpperCase()] || status;
}

const detailVisible = ref(false);
const detailData = ref<TableRow>({});

async function handleView(row: TableRow) {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/equipment/${row.id}`);
    if (response.success && response.data) {
      detailData.value = response.data;
      detailVisible.value = true;
    } else {
      ElMessage.error(response.message || '获取设备详情失败');
    }
  } catch (error) {
    console.error('获取设备详情失败:', error);
    ElMessage.error('获取设备详情失败');
  }
}

function handleMaintenance(row: TableRow) {
  router.push({ path: '/equipment/maintenance', query: { equipmentId: row.id as string } });
}

// ==================== Create / Edit Dialog ====================
// 张权 Apr 29 2026 audit P0 fix: handleAdd/handleEdit 之前是 "开发中" stub.
// 后端 /equipment 全 CRUD 齐全 (POST/PUT/DELETE/GET), 补完 dialog form.
const formDialogVisible = ref(false);
const editingId = ref<string | null>(null);
const form = ref({
  equipmentCode: '',
  name: '',
  model: '',
  location: '',
  manufacturer: '',
  status: 'IDLE',
  notes: '',
  // Bug fix (module-verify 2026-07-03): 表单之前没有维护间隔字段, UI 建的设备永远不会
  // 设置 nextMaintenanceDate, 也就永远不会出现在"待维护"列表里 (设备维护功能事实上不可达)。
  // 用户按"天"填写 (对齐 RN 移动端 maintenanceInterval 的天数惯例), 提交时换算成后端的
  // maintenanceIntervalHours (小时)。
  maintenanceIntervalDays: null as number | null,
});
const dialogTitle = computed(() => (editingId.value ? '编辑设备' : '新建设备'));
const submitting = ref(false);

function handleAdd() {
  editingId.value = null;
  form.value = {
    equipmentCode: '',
    name: '',
    model: '',
    location: '',
    manufacturer: '',
    status: 'IDLE',
    notes: '',
    maintenanceIntervalDays: null,
  };
  formDialogVisible.value = true;
}

function handleEdit(row: TableRow) {
  editingId.value = String(row.id || '');
  const hours = row.maintenanceIntervalHours;
  const hoursNum = typeof hours === 'number' ? hours : Number(hours);
  form.value = {
    equipmentCode: String(row.equipmentCode || ''),
    name: String(row.name || ''),
    model: String(row.model || ''),
    location: String(row.location || ''),
    manufacturer: String(row.manufacturer || ''),
    status: String(row.status || 'IDLE'),
    notes: String(row.notes || ''),
    maintenanceIntervalDays: Number.isFinite(hoursNum) && hoursNum > 0 ? Math.round(hoursNum / 24) : null,
  };
  formDialogVisible.value = true;
}

async function handleSave() {
  if (!form.value.name) return ElMessage.warning('请填写设备名称');
  if (!form.value.equipmentCode) return ElMessage.warning('请填写设备编号');
  submitting.value = true;
  try {
    const { maintenanceIntervalDays, ...rest } = form.value;
    const payload = {
      ...rest,
      maintenanceIntervalHours: maintenanceIntervalDays ? maintenanceIntervalDays * 24 : undefined,
    };
    if (editingId.value) {
      const res = await put(`/${factoryId.value}/equipment/${editingId.value}`, payload);
      if (res.success) ElMessage.success('更新成功');
    } else {
      const res = await post(`/${factoryId.value}/equipment`, payload);
      if (res.success) ElMessage.success('创建成功');
    }
    formDialogVisible.value = false;
    loadData();
  } catch (e) { console.error(e); }
  finally { submitting.value = false; }
}

async function handleDelete(row: TableRow) {
  try {
    await ElMessageBox.confirm(
      `确定删除设备「${row.name}」? 该设备的维护记录、告警历史仍保留, 但无法新分配工单.`,
      '删除确认',
      { type: 'warning' },
    );
    const res = await del(`/${factoryId.value}/equipment/${row.id}`);
    if (res.success) {
      ElMessage.success('删除成功');
      loadData();
    }
  } catch { /* user cancelled */ }
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">设备管理</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleAdd">添加设备</el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索设备编号/名称"
          :prefix-icon="Search"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column prop="equipmentCode" label="设备编号" width="140" />
        <el-table-column prop="name" label="设备名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="model" label="型号" width="120" />
        <el-table-column prop="location" label="位置" min-width="150" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastMaintenanceDate" label="上次维护" width="120" />
        <el-table-column label="操作" width="280" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleView(row)">查看</el-button>
            <el-button v-if="canWrite" type="primary" link size="small" @click="handleMaintenance(row)">维护</el-button>
            <el-button v-if="canWrite" type="primary" link size="small" :icon="Edit" @click="handleEdit(row)">编辑</el-button>
            <el-button v-if="canWrite" type="danger" link size="small" :icon="DeleteIcon" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <el-dialog v-model="formDialogVisible" :title="dialogTitle" width="600px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item label="设备编号" required>
          <el-input v-model="form.equipmentCode" placeholder="如 EQ001" :disabled="!!editingId" />
        </el-form-item>
        <el-form-item label="设备名称" required>
          <el-input v-model="form.name" placeholder="如 卤煮锅 1 号 / 真空包装机" />
        </el-form-item>
        <el-form-item label="型号">
          <el-input v-model="form.model" placeholder="如 LZG-200L" />
        </el-form-item>
        <el-form-item label="生产厂商">
          <el-input v-model="form.manufacturer" placeholder="如 苏泊尔" />
        </el-form-item>
        <el-form-item label="位置">
          <el-input v-model="form.location" placeholder="如 一楼车间 A 区" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="空闲 (IDLE)" value="IDLE" />
            <el-option label="运行中 (RUNNING)" value="RUNNING" />
            <el-option label="维护中 (MAINTENANCE)" value="MAINTENANCE" />
            <el-option label="故障 (FAULT)" value="FAULT" />
            <el-option label="离线 (OFFLINE)" value="OFFLINE" />
          </el-select>
        </el-form-item>
        <el-form-item label="维护间隔(天)">
          <el-input-number
            v-model="form.maintenanceIntervalDays"
            :min="1"
            :precision="0"
            style="width: 100%"
            placeholder="不填则不进入待维护提醒"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="设备详情" width="500px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="设备编号">{{ detailData.equipmentCode || '-' }}</el-descriptions-item>
        <el-descriptions-item label="设备名称">{{ detailData.name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="型号">{{ detailData.model || '-' }}</el-descriptions-item>
        <el-descriptions-item label="位置">{{ detailData.location || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(detailData.status as string)" size="small">
            {{ getStatusText(detailData.status as string) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="上次维护">{{ detailData.lastMaintenanceDate || '-' }}</el-descriptions-item>
      </el-descriptions>
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
}

.search-bar {
  display: flex;
  gap: 12px;
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
