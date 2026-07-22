<script setup lang="ts">
/**
 * 审批链配置 (approval_chain_configs)
 *
 * 控制 4-eye 审批 / 报工审批 / 财务审批等流程, 之前只能 SQL 改.
 * N9 update: 新增 triggerCondition / autoApproveCondition / autoRejectCondition /
 *             approverUserIds 字段支持; 完整 DecisionType 枚举 map;
 *             错误 toast 改 sticky (duration:0 + showClose) per fool-proof-design 规范.
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Edit, Delete as DeleteIcon, Refresh } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const router = useRouter();
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
  } catch (e) {
    showError('加载审批链配置失败', e);
  } finally {
    loading.value = false;
  }
}

/** Sticky error toast per fool-proof-design §4-位一体 */
function showError(prefix: string, e: unknown) {
  const msg = e instanceof Error ? e.message : String(e);
  ElMessage({
    message: `${prefix}: ${msg}`,
    type: 'error',
    duration: 0,
    showClose: true,
  });
}

const dialogVisible = ref(false);
const editingId = ref<string | null>(null);
const editingName = ref('');

const emptyForm = () => ({
  decisionType: 'FORCE_INSERT',
  name: '',
  description: '',
  triggerCondition: '',
  approvalLevel: 1,
  requiredApprovers: 1,
  approverRoles: '',
  approverUserIds: '',
  autoApproveCondition: '',
  autoRejectCondition: '',
  timeoutMinutes: null as number | null,
  priority: 0,
  enabled: true,
});

const form = ref(emptyForm());

/** Dialog title includes decision type + config name for context (fool-proof Rule 2) */
const dialogTitle = computed(() => {
  if (!editingId.value) return '新建审批链';
  const typeName = decisionTypeMap[form.value.decisionType] || form.value.decisionType;
  return `编辑审批链 — ${typeName}${editingName.value ? ` (${editingName.value})` : ''}`;
});

const submitting = ref(false);
const UNIFIED_OA_DECISION_TYPES = new Set(['SALES_ORDER_APPROVAL']);

function isUnifiedOaDecision(rowOrType: TableRow | string): boolean {
  const decisionType = typeof rowOrType === 'string'
    ? rowOrType
    : String(rowOrType.decisionType || '');
  return UNIFIED_OA_DECISION_TYPES.has(decisionType);
}

async function goToUnifiedOa(decisionType = 'SALES_ORDER_APPROVAL') {
  await router.push({
    name: 'CanvasEditor',
    query: { tab: 'approval', decisionType },
  });
}

function openCreate() {
  editingId.value = null;
  editingName.value = '';
  form.value = emptyForm();
  dialogVisible.value = true;
}

function openEdit(row: TableRow) {
  if (isUnifiedOaDecision(row)) {
    ElMessage.info('销售订单审批已迁移至统一 OA，此处仅保留历史配置只读展示');
    void goToUnifiedOa(String(row.decisionType));
    return;
  }
  editingId.value = String(row.id || '');
  editingName.value = String(row.name || '');
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
    approverUserIds: (() => {
      const raw = row.approverUserIds;
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
  if (isUnifiedOaDecision(form.value.decisionType)) {
    ElMessage.warning('销售订单审批请前往统一 OA 配置');
    dialogVisible.value = false;
    await goToUnifiedOa(form.value.decisionType);
    return;
  }
  if (!form.value.name?.trim()) {
    ElMessage.warning('请填写审批链名称');
    return;
  }
  if (!form.value.approverRoles?.trim()) {
    ElMessage.warning('请填写审批角色 (逗号分隔多个)');
    return;
  }

  // 后端 validateConfig 要求 approverRoles 是 JSON 数组格式
  const roles = form.value.approverRoles
    .split(/[,，]/)
    .map((r: string) => r.trim())
    .filter((r: string) => r.length > 0);

  const userIds = form.value.approverUserIds
    ? form.value.approverUserIds
        .split(/[,，]/)
        .map((u: string) => u.trim())
        .filter((u: string) => u.length > 0)
    : null;

  const payload = {
    ...form.value,
    approverRoles: JSON.stringify(roles),
    approverUserIds: userIds && userIds.length > 0 ? JSON.stringify(userIds) : null,
    triggerCondition: form.value.triggerCondition?.trim() || null,
    autoApproveCondition: form.value.autoApproveCondition?.trim() || null,
    autoRejectCondition: form.value.autoRejectCondition?.trim() || null,
  };

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
  } catch (e) {
    showError('保存失败', e);
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(row: TableRow) {
  if (isUnifiedOaDecision(row)) {
    ElMessage.warning('销售订单历史审批链不可在旧页面删除，请前往统一 OA');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确定删除审批链「${row.name}」(决策类型: ${decisionTypeMap[String(row.decisionType)] || row.decisionType})?`,
      '删除确认',
      { type: 'warning' }
    );
    const res = await del(`/${factoryId.value}/approval-chains/${row.id}`);
    if (res.success) { ElMessage.success('删除成功'); loadData(); }
  } catch (e) {
    if (e === 'cancel') return;
    showError('删除失败', e);
  }
}

async function handleToggle(row: TableRow) {
  if (isUnifiedOaDecision(row)) {
    ElMessage.warning('销售订单历史审批链不可在旧页面变更，请前往统一 OA');
    return;
  }
  const willEnable = !row.enabled;
  try {
    const res = await put(
      `/${factoryId.value}/approval-chains/${row.id}/toggle?enabled=${willEnable}`,
      {}
    );
    if (res.success) {
      ElMessage.success(willEnable ? '已启用' : '已禁用');
      loadData();
    }
  } catch (e) {
    showError('状态切换失败', e);
  }
}

// 来自后端 ApprovalChainConfig.DecisionType enum (完整列表, Sprint 5 H-3 扩展)
const decisionTypeMap: Record<string, string> = {
  // 生产 / 工序
  FORCE_INSERT: '强制插单',
  PRODUCTION_PLAN_CHANGE: '生产计划变更',
  EQUIPMENT_STATUS_CHANGE: '设备状态变更',
  BOM_VERSION_APPROVAL: 'BOM 版本审批',
  ECN_APPROVAL: 'ECN 工程变更审批',
  WORK_ORDER_APPROVAL: '工单审批',
  PRODUCTION_REVERSAL_APPROVAL: '生产撤单审批',
  // 质检 / 物料
  QUALITY_RELEASE: '质检放行',
  QUALITY_EXCEPTION: '质检特批',
  BATCH_STATUS_CHANGE: '批次状态变更',
  MATERIAL_DISPOSAL: '原料处置',
  MATERIAL_REQUISITION_APPROVAL: '请购单审批',
  // 采购 / 供应商
  SUPPLIER_APPROVAL: '供应商准入',
  SUPPLIER_STATUS_CHANGE: '供应商状态变更',
  PURCHASE_ORDER_APPROVAL: '采购订单审批',
  PURCHASE_PAYMENT_APPROVAL: '采购付款审批',
  PURCHASE_RETURN_APPROVAL: '采购退货审批',
  // 销售 / 客户
  SALES_ORDER_APPROVAL: '销售订单审批',
  SALES_RETURN_APPROVAL: '销售退货审批',
  SALES_DISCOUNT_APPROVAL: '销售折扣审批',
  CUSTOMER_CREDIT_APPROVAL: '客户额度审批',
  INVOICE_ISSUANCE_APPROVAL: '发票开具审批',
  // 财务 / 凭证
  VOUCHER_APPROVAL: '凭证审核',
  EXPENSE_APPROVAL: '报销审批',
  BUDGET_APPROVAL: '预算审批',
  PAYMENT_APPROVAL: '付款审批',
  TAX_FILING_APPROVAL: '报税审批',
  // 人事 / 工资
  LEAVE_APPROVAL: '请假审批',
  OVERTIME_APPROVAL: '加班审批',
  WAGE_RECORD_APPROVAL: '工资记录审批',
  HIRE_APPROVAL: '入职审批',
  // 仓储 / 调拨
  INVENTORY_TRANSFER_APPROVAL: '库存调拨审批',
  INVENTORY_ADJUSTMENT_APPROVAL: '库存调整审批',
  WASTAGE_APPROVAL: '损耗记录审批',
  CUSTOM: '自定义',
};

/** Group label for decision type option groups */
const decisionTypeGroups: { label: string; types: string[] }[] = [
  {
    label: '生产 / 工序',
    types: ['FORCE_INSERT', 'PRODUCTION_PLAN_CHANGE', 'EQUIPMENT_STATUS_CHANGE', 'BOM_VERSION_APPROVAL', 'ECN_APPROVAL', 'WORK_ORDER_APPROVAL', 'PRODUCTION_REVERSAL_APPROVAL'],
  },
  {
    label: '质检 / 物料',
    types: ['QUALITY_RELEASE', 'QUALITY_EXCEPTION', 'BATCH_STATUS_CHANGE', 'MATERIAL_DISPOSAL', 'MATERIAL_REQUISITION_APPROVAL'],
  },
  {
    label: '采购 / 供应商',
    types: ['SUPPLIER_APPROVAL', 'SUPPLIER_STATUS_CHANGE', 'PURCHASE_ORDER_APPROVAL', 'PURCHASE_PAYMENT_APPROVAL', 'PURCHASE_RETURN_APPROVAL'],
  },
  {
    label: '销售 / 客户',
    types: ['SALES_RETURN_APPROVAL', 'SALES_DISCOUNT_APPROVAL', 'CUSTOMER_CREDIT_APPROVAL', 'INVOICE_ISSUANCE_APPROVAL'],
  },
  {
    label: '财务 / 凭证',
    types: ['VOUCHER_APPROVAL', 'EXPENSE_APPROVAL', 'BUDGET_APPROVAL', 'PAYMENT_APPROVAL', 'TAX_FILING_APPROVAL'],
  },
  {
    label: '人事 / 工资',
    types: ['LEAVE_APPROVAL', 'OVERTIME_APPROVAL', 'WAGE_RECORD_APPROVAL', 'HIRE_APPROVAL'],
  },
  {
    label: '仓储 / 调拨',
    types: ['INVENTORY_TRANSFER_APPROVAL', 'INVENTORY_ADJUSTMENT_APPROVAL', 'WASTAGE_APPROVAL'],
  },
  {
    label: '其他',
    types: ['CUSTOM'],
  },
];
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
        <el-table-column label="决策类型" width="160">
          <template #default="{ row }">
            <el-tooltip :content="String(row.decisionType || '')" placement="top">
              <span>{{ decisionTypeMap[String(row.decisionType)] || row.decisionType }}</span>
            </el-tooltip>
            <el-tag v-if="isUnifiedOaDecision(row)" type="info" size="small" style="margin-left: 6px">旧配置只读</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column prop="approvalLevel" label="审批级别" width="90" align="center" />
        <el-table-column prop="requiredApprovers" label="所需审批人数" width="110" align="center" />
        <el-table-column prop="approverRoles" label="审批角色" min-width="160" show-overflow-tooltip />
        <el-table-column prop="triggerCondition" label="触发条件" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.triggerCondition" type="warning" size="small" style="font-size: 11px; max-width: 150px; overflow: hidden; text-overflow: ellipsis;">
              {{ row.triggerCondition }}
            </el-tag>
            <span v-else style="color: #c0c4cc">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="autoApproveCondition" label="自动通过" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.autoApproveCondition" type="success" size="small">有</el-tag>
            <span v-else style="color: #c0c4cc">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="timeoutMinutes" label="超时(分)" width="85" align="center">
          <template #default="{ row }">
            <span v-if="row.timeoutMinutes">{{ row.timeoutMinutes }}</span>
            <span v-else style="color: #c0c4cc">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="75">
          <template #default="{ row }">
            <el-tag v-if="row.enabled" type="success" size="small">启用</el-tag>
            <el-tag v-else type="info" size="small">停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-tooltip
              v-if="isUnifiedOaDecision(row)"
              content="销售订单审批已迁移至统一 OA；此处历史配置仅供查看"
            >
              <el-button link type="primary" @click="goToUnifiedOa(String(row.decisionType))">前往统一 OA</el-button>
            </el-tooltip>
            <template v-else>
              <el-button v-if="canWrite" link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
              <el-button
                v-if="canWrite"
                link
                :type="row.enabled ? 'warning' : 'success'"
                @click="handleToggle(row)"
              >{{ row.enabled ? '停用' : '启用' }}</el-button>
              <el-button v-if="canWrite" link type="danger" :icon="DeleteIcon" @click="handleDelete(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建/编辑 dialog — 标题含决策类型+名称提供操作上下文 (fool-proof Rule 2) -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="680px" destroy-on-close>
      <el-form :model="form" label-width="130px">
        <!-- 决策类型 (分组下拉) -->
        <el-form-item label="决策类型" required>
          <el-select v-model="form.decisionType" style="width: 100%" filterable>
            <el-option-group
              v-for="group in decisionTypeGroups"
              :key="group.label"
              :label="group.label"
            >
              <el-option
                v-for="k in group.types"
                :key="k"
                :label="`${decisionTypeMap[k]} (${k})`"
                :value="k"
              />
            </el-option-group>
          </el-select>
        </el-form-item>

        <el-form-item label="审批链名称" required>
          <el-input v-model="form.name" placeholder="如 采购大额订单审批" />
        </el-form-item>

        <el-form-item label="审批级别">
          <el-input-number v-model="form.approvalLevel" :min="1" :max="10" style="width: 100%" />
          <div class="field-hint">1 = 一级审批, 2 = 二级审批, 以此类推</div>
        </el-form-item>

        <el-form-item label="所需审批人数">
          <el-input-number v-model="form.requiredApprovers" :min="1" :max="10" style="width: 100%" />
        </el-form-item>

        <el-form-item label="审批角色" required>
          <el-input
            v-model="form.approverRoles"
            placeholder="多个角色用逗号分隔, 如 finance_manager,factory_super_admin"
          />
          <div class="field-hint">逗号分隔多个角色. 该角色的用户可审批此类型.</div>
        </el-form-item>

        <el-form-item label="指定审批人 ID">
          <el-input
            v-model="form.approverUserIds"
            placeholder="可选 — 用户 ID 逗号分隔, 如 101,202"
          />
          <div class="field-hint">可选. 指定后仅这些用户可审批 (优先于角色限制).</div>
        </el-form-item>

        <el-divider content-position="left" style="font-size: 13px; color: #909399">触发 & 自动审批条件 (JSON)</el-divider>

        <el-form-item label="触发条件 JSON">
          <el-input
            v-model="form.triggerCondition"
            type="textarea"
            :rows="2"
            placeholder='如 {"amount": ">5000"} 表示金额>5000 才触发审批'
          />
          <div class="field-hint">留空 = 此决策类型所有操作都触发审批.</div>
        </el-form-item>

        <el-form-item label="自动通过 JSON">
          <el-input
            v-model="form.autoApproveCondition"
            type="textarea"
            :rows="2"
            placeholder='如 {"externalOrder": true} 表示外部渠道订单自动通过'
          />
          <div class="field-hint">满足条件时系统自动审批通过, 无需人工操作.</div>
        </el-form-item>

        <el-form-item label="自动拒绝 JSON">
          <el-input
            v-model="form.autoRejectCondition"
            type="textarea"
            :rows="2"
            placeholder="可留空"
          />
        </el-form-item>

        <el-divider content-position="left" style="font-size: 13px; color: #909399">其他设置</el-divider>

        <el-form-item label="超时 (分钟)">
          <el-input-number v-model="form.timeoutMinutes" :min="0" style="width: 100%" placeholder="留空 = 不超时" />
          <div class="field-hint">超时后升级到 escalationConfigId 配置的规则 (若已设置).</div>
        </el-form-item>

        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" :max="100" style="width: 100%" />
          <div class="field-hint">同决策类型有多条规则时, 数值越大优先级越高.</div>
        </el-form-item>

        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" />
          <span style="margin-left: 12px; color: #909399; font-size: 12px">
            关闭后此规则不参与审批判断
          </span>
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
.field-hint { font-size: 12px; color: #909399; margin-top: 4px; line-height: 1.4; }
</style>
