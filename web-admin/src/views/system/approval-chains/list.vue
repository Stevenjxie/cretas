<script setup lang="ts">
/**
 * 审批链配置 (approval_chains)
 * audit P1 fix: 后端 /approval-chains 全 CRUD 齐全, 前端零页面.
 * 控制 4-eye 审批 / 报工审批 / 财务审批等流程, 之前只能 SQL 改.
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Edit, Delete as DeleteIcon, Refresh } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

const loading = ref(false);
const tableData = ref<TableRow[]>([]);

onMounted(loadData);

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/approval-chains`);
    if (res.success && res.data) {
      tableData.value = Array.isArray(res.data) ? res.data : (res.data.content || []);
    }
  } catch (e) { console.error(e); }
  finally { loading.value = false; }
}

const dialogVisible = ref(false);
const editingId = ref<string | null>(null);
const form = ref({
  decisionType: 'FORCE_INSERT',
  name: '',
  description: '',
  triggerCondition: '',
  approvalLevel: 1,
  requiredApprovers: 1,
  approverRoles: '',
  autoApproveCondition: '',
  autoRejectCondition: '',
  timeoutMinutes: null as number | null,
  priority: 0,
  enabled: true,
});
const dialogTitle = computed(() => (editingId.value ? '编辑审批链' : '新建审批链'));
const submitting = ref(false);

function openCreate() {
  editingId.value = null;
  form.value = {
    decisionType: 'FORCE_INSERT',
    name: '',
    description: '',
    triggerCondition: '',
    approvalLevel: 1,
    requiredApprovers: 1,
    approverRoles: '',
    autoApproveCondition: '',
    autoRejectCondition: '',
    timeoutMinutes: null,
    priority: 0,
    enabled: true,
  };
  dialogVisible.value = true;
}

function openEdit(row: TableRow) {
  editingId.value = String(row.id || '');
  form.value = {
    decisionType: String(row.decisionType || 'FORCE_INSERT'),
    name: String(row.name || ''),
    description: String(row.description || ''),
    triggerCondition: String(row.triggerCondition || ''),
    approvalLevel: Number(row.approvalLevel || 1),
    requiredApprovers: Number(row.requiredApprovers || 1),
    // 后端存的是 JSON 数组字符串, 编辑时转回逗号分隔友好格式
    approverRoles: (() => {
      const raw = row.approverRoles;
      if (!raw) return '';
      try {
        const arr = JSON.parse(String(raw));
        return Array.isArray(arr) ? arr.join(',') : String(raw);
      } catch { return String(raw); }
    })(),
    autoApproveCondition: String(row.autoApproveCondition || ''),
    autoRejectCondition: String(row.autoRejectCondition || ''),
    timeoutMinutes: row.timeoutMinutes as number | null ?? null,
    priority: Number(row.priority || 0),
    enabled: row.enabled !== false,
  };
  dialogVisible.value = true;
}

async function handleSave() {
  if (!form.value.name) return ElMessage.warning('请填写审批链名称');
  if (!form.value.approverRoles?.trim()) return ElMessage.warning('请填写审批角色 (逗号分隔多个)');

  // 后端 validateConfig 要求 approverRoles 是 JSON 数组格式
  // 用户输入逗号分隔字符串, 这里转换成 JSON
  const roles = form.value.approverRoles
    .split(/[,，]/)
    .map(r => r.trim())
    .filter(r => r.length > 0);
  const payload = { ...form.value, approverRoles: JSON.stringify(roles) };

  submitting.value = true;
  try {
    if (editingId.value) {
      const res = await put(`/${factoryId.value}/approval-chains/${editingId.value}`, payload);
      if (res.success) ElMessage.success('更新成功');
    } else {
      const res = await post(`/${factoryId.value}/approval-chains`, payload);
      if (res.success) ElMessage.success('创建成功');
    }
    dialogVisible.value = false;
    loadData();
  } catch (e) { console.error(e); }
  finally { submitting.value = false; }
}

async function handleDelete(row: TableRow) {
  try {
    await ElMessageBox.confirm(`确定删除审批链「${row.name}」?`, '删除确认', { type: 'warning' });
    const res = await del(`/${factoryId.value}/approval-chains/${row.id}`);
    if (res.success) { ElMessage.success('删除成功'); loadData(); }
  } catch { /* cancel */ }
}

// 来自后端 ApprovalChainConfig.DecisionType enum
const decisionTypeMap: Record<string, string> = {
  FORCE_INSERT: '强制插单',
  QUALITY_RELEASE: '质检放行',
  QUALITY_EXCEPTION: '质检特批',
  BATCH_STATUS_CHANGE: '批次状态变更',
  SUPPLIER_APPROVAL: '供应商准入',
  SUPPLIER_STATUS_CHANGE: '供应商状态变更',
  MATERIAL_DISPOSAL: '原料处置',
  PRODUCTION_PLAN_CHANGE: '生产计划变更',
  EQUIPMENT_STATUS_CHANGE: '设备状态变更',
  PRODUCTION_REVERSAL_APPROVAL: '生产撤单审批', // SP12
  PURCHASE_ORDER_APPROVAL: '采购订单审批',
  PURCHASE_PAYMENT_APPROVAL: '采购付款审批',
  PURCHASE_RETURN_APPROVAL: '采购退货审批',
  SALES_ORDER_APPROVAL: '销售订单审批',
  SALES_RETURN_APPROVAL: '销售退货审批',
  CUSTOM: '自定义',
};
</script>

<template>
  <div class="page-wrapper">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">审批链配置</span>
            <span class="data-count">控制 4-eye 审批 / 报工审批 / 财务审批等流程</span>
          </div>
          <div class="header-right">
            <el-button :icon="Refresh" @click="loadData">刷新</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新建审批链</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="tableData" stripe>
        <el-table-column label="决策类型" width="140">
          <template #default="{ row }">{{ decisionTypeMap[row.decisionType] || row.decisionType }}</template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="approvalLevel" label="审批级别" width="100" align="center" />
        <el-table-column prop="requiredApprovers" label="所需审批人数" width="120" align="center" />
        <el-table-column prop="approverRoles" label="审批角色" min-width="180" show-overflow-tooltip />
        <el-table-column prop="triggerCondition" label="触发条件" min-width="180" show-overflow-tooltip />
        <el-table-column prop="autoApproveCondition" label="自动通过条件" min-width="180" show-overflow-tooltip />
        <el-table-column prop="timeoutMinutes" label="超时(分钟)" width="100" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.enabled" type="success" size="small">启用</el-tag>
            <el-tag v-else type="info" size="small">停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canWrite" link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="canWrite" link type="danger" :icon="DeleteIcon" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px" destroy-on-close>
      <el-form :model="form" label-width="120px">
        <el-form-item label="决策类型" required>
          <el-select v-model="form.decisionType" style="width: 100%">
            <el-option v-for="(v, k) in decisionTypeMap" :key="k" :label="`${v} (${k})`" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="审批链名称" required>
          <el-input v-model="form.name" placeholder="如 财务审批 - 大额订单" />
        </el-form-item>
        <el-form-item label="审批级别">
          <el-input-number v-model="form.approvalLevel" :min="1" :max="10" style="width: 100%" />
        </el-form-item>
        <el-form-item label="所需审批人数">
          <el-input-number v-model="form.requiredApprovers" :min="1" :max="10" style="width: 100%" />
        </el-form-item>
        <el-form-item label="审批角色">
          <el-input v-model="form.approverRoles" placeholder="多个角色用逗号分隔, 如 finance_manager,factory_super_admin" />
        </el-form-item>
        <el-form-item label="触发条件 JSON">
          <el-input
            v-model="form.triggerCondition"
            type="textarea"
            :rows="2"
            placeholder='如 {"amount": ">5000"}'
          />
        </el-form-item>
        <el-form-item label="自动通过 JSON">
          <el-input
            v-model="form.autoApproveCondition"
            type="textarea"
            :rows="2"
            placeholder='如 {"externalOrder": true}'
          />
        </el-form-item>
        <el-form-item label="自动拒绝 JSON">
          <el-input
            v-model="form.autoRejectCondition"
            type="textarea"
            :rows="2"
            placeholder="可留空"
          />
        </el-form-item>
        <el-form-item label="超时 (分钟)">
          <el-input-number v-model="form.timeoutMinutes" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" :max="100" style="width: 100%" />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-wrapper { padding: 20px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-left { display: flex; align-items: baseline; gap: 12px; }
.page-title { font-size: 18px; font-weight: 600; }
.data-count { font-size: 13px; color: #909399; }
</style>
