<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get, post } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { enumLabel } from '@/utils/enumDisplay';
import { handleCatchError } from '@/utils/errorToast';
import { formatDateTime } from '@/utils/dateFormat';

interface PendingApproval {
  instanceId: string;
  moduleCode: string;
  businessEntityId: string;
  businessSummary?: string;
  currentNodeId?: string;
  currentNodeLabel?: string;
  approverRoles?: string[];
  initiatedAt?: string;
  initiatedByUsername?: string;
}

const authStore = useAuthStore();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId);
const loading = ref(false);
const operatingId = ref('');
const rows = ref<PendingApproval[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const ACTIONABLE_MODULE_CODES = new Set(['PURCHASE_ORDER', 'SALES_ORDER', 'INVENTORY_TRANSFER']);
const moduleCode = ref('');
const focusedInstanceId = ref('');
let mounted = false;

function queryValue(value: unknown): string {
  const normalized = Array.isArray(value) ? value[0] : value;
  return typeof normalized === 'string' ? normalized : '';
}

function canAct(row: PendingApproval): boolean {
  return ACTIONABLE_MODULE_CODES.has(row.moduleCode);
}

async function loadPending() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/workflow/instances/pending`, {
      params: {
        page: page.value,
        size: size.value,
        moduleCode: moduleCode.value || undefined,
      },
    });
    const data = res.data || {};
    rows.value = data.items || data.content || data.records || [];
    total.value = Number(data.total ?? data.totalElements ?? rows.value.length);
  } catch (error) {
    handleCatchError(error, '待审批任务加载失败');
  } finally {
    loading.value = false;
  }
}

watch(
  () => [route.query.moduleCode, route.query.instanceId],
  async ([moduleCodeQuery, instanceIdQuery]) => {
    const requestedModuleCode = queryValue(moduleCodeQuery);
    const nextModuleCode = ACTIONABLE_MODULE_CODES.has(requestedModuleCode)
      ? requestedModuleCode
      : '';
    const moduleChanged = nextModuleCode !== moduleCode.value;
    moduleCode.value = nextModuleCode;
    focusedInstanceId.value = queryValue(instanceIdQuery);
    if (mounted && moduleChanged) {
      page.value = 1;
      await loadPending();
    }
  },
  { immediate: true },
);

function rowClassName({ row }: { row: PendingApproval }): string {
  return row.instanceId === focusedInstanceId.value ? 'deep-linked-approval-row' : '';
}

async function act(row: PendingApproval, action: 'APPROVE' | 'REJECT') {
  if (operatingId.value) return;
  let notes = '';
  try {
    if (action === 'REJECT') {
      const result = await ElMessageBox.prompt('请填写驳回原因', '驳回审批', {
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputValidator: value => Boolean(value?.trim()) || '驳回原因不能为空',
      });
      notes = result.value.trim();
    } else {
      await ElMessageBox.confirm(
        `确认通过「${row.businessSummary || row.businessEntityId}」？`,
        'OA 审批确认',
        { confirmButtonText: '审批通过', cancelButtonText: '取消' },
      );
    }
  } catch {
    return;
  }
  operatingId.value = row.instanceId;
  try {
    const res = await post(
      `/${factoryId.value}/workflow/instances/${row.instanceId}/actions`,
      {
        action,
        notes,
        idempotencyKey: `oa-${action.toLowerCase()}-${row.instanceId}-${row.currentNodeId}`,
        expectedNodeId: row.currentNodeId,
      },
    );
    if (!res.success) throw new Error(res.message || '审批操作失败');
    ElMessage.success(action === 'APPROVE' ? '审批已通过' : '审批已驳回');
    await loadPending();
  } catch (error) {
    handleCatchError(error, '审批操作失败');
  } finally {
    operatingId.value = '';
  }
}

onMounted(async () => {
  mounted = true;
  await loadPending();
});
</script>

<template>
  <div class="approval-center-page">
    <el-card shadow="never">
      <template #header>
        <div class="header">
          <div>
            <h2>OA 审批中心</h2>
            <p>这里只显示当前账号有权处理的本工厂待办。</p>
          </div>
          <el-select v-model="moduleCode" clearable placeholder="全部业务类型" style="width: 190px" @change="page = 1; loadPending()">
            <el-option label="采购订单" value="PURCHASE_ORDER" />
            <el-option label="销售订单" value="SALES_ORDER" />
            <el-option label="库存调拨" value="INVENTORY_TRANSFER" />
          </el-select>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="rows"
        :row-class-name="rowClassName"
        border
        stripe
        empty-text="暂无待您审批的任务"
      >
        <el-table-column label="业务类型" width="120">
          <template #default="{ row }">{{ enumLabel(row.moduleCode) }}</template>
        </el-table-column>
        <el-table-column prop="businessSummary" label="业务单据" min-width="260" />
        <el-table-column prop="currentNodeLabel" label="当前节点" min-width="150" />
        <el-table-column label="授权角色" min-width="180">
          <template #default="{ row }">{{ row.approverRoles?.map((role: string) => enumLabel(role)).join('、') || '-' }}</template>
        </el-table-column>
        <el-table-column prop="initiatedByUsername" label="申请人" width="130" />
        <el-table-column label="提交时间" min-width="180">
          <template #default="{ row }">{{ formatDateTime(row.initiatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <template v-if="canAct(row)">
              <el-button link type="primary" :loading="operatingId === row.instanceId" @click="act(row, 'APPROVE')">通过</el-button>
              <el-button link type="danger" :disabled="Boolean(operatingId)" @click="act(row, 'REJECT')">驳回</el-button>
            </template>
            <el-tooltip v-else content="该业务域正在接入统一 OA，当前仅可查看审批进度">
              <el-tag type="info">只读</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > size"
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="loadPending"
      />
    </el-card>
  </div>
</template>

<style scoped>
.approval-center-page { padding: 20px; }
.header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.header h2 { margin: 0 0 6px; }
.header p { margin: 0; color: var(--el-text-color-secondary); }
.el-pagination { margin-top: 16px; justify-content: flex-end; }
:deep(.deep-linked-approval-row > td.el-table__cell) { background: var(--el-color-primary-light-9) !important; }
</style>
