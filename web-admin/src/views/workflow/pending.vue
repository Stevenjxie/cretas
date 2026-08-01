<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get, post } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { enumLabel } from '@/utils/enumDisplay';

/**
 * 业务类型中文名 —— 筛选下拉和表格列共用同一份。
 * 之前下拉里写死了四个中文名, 表格列却走 enumLabel 的通用字典(只有 PO/SO),
 * 于是调拨待办在列表里显示成「未知状态（INVENTORY_TRANSFER）」——
 * 同一个页面对同一个编码给出两种说法。
 */
/**
 * ⚠️ 这不是事实来源, 只是后端没下发 moduleLabel 时的离线兜底。
 *
 * 权威表在后端 `DecisionTypeMetadataRegistry`(30+ 个 moduleCode, 各带 chineseName),
 * 已通过待办 DTO 的 `moduleLabel` 字段下发。**不要在这里加新码** ——
 * 这份表历史上只覆盖了权威表的 4 个, 另外 20 多个码全部渲染成「未知状态（X）」,
 * 客户截图里的「未知状态（BUDGET）」就是这么来的。加在这里只会再漂一次。
 */
const MODULE_LABELS: Record<string, string> = {
  PURCHASE_ORDER: '采购订单',
  SALES_ORDER: '销售订单',
  INVENTORY_TRANSFER: '库存调拨',
  INVENTORY_ADJUSTMENT: '库存盘点',
};
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
  /** 业务类型中文名, 后端按权威表下发; 解析不出时为空, 由 MODULE_LABELS 兜底。 */
  moduleLabel?: string;
  /** 由定时任务等系统流程发起, 没有人类申请人 (后端判据: initiatedBy == null)。 */
  systemInitiated?: boolean;
}

const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId);
const loading = ref(false);
const operatingId = ref('');
const rows = ref<PendingApproval[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const ACTIONABLE_MODULE_CODES = new Set([
  'PURCHASE_ORDER', 'SALES_ORDER', 'INVENTORY_TRANSFER', 'INVENTORY_ADJUSTMENT',
  // 🔒 BUDGET(会计期间结账): 通过 = 执行月度关账。此前不在此列表里而显示「只读」,
  // 那忠实反映了当时的现状 —— OA 实例是孤儿, 批不批都不影响期间。现已接上后端适配器。
  'BUDGET',
]);
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
    } else if (row.moduleCode === 'BUDGET') {
      // 🔒 BUDGET 通过 = 执行月度关账: 期间转 CLOSED、生成库存台账快照、
      // 凭证进入 20 天调整窗口(逾期硬锁)。通用文案「确认通过 xxx？」完全没有传达这个
      // 后果, 而待办列表正是批量处理场景 —— 误点代价很高(反结账有通道但很麻烦)。
      await ElMessageBox.confirm(
        `这将关闭「${row.businessSummary || row.businessEntityId}」，并生成库存台账快照。\n`
        + '关账后凭证进入 20 天调整窗口，逾期将硬锁。',
        '确认关账',
        {
          confirmButtonText: '确认关账',
          cancelButtonText: '取消',
          type: 'warning',
        },
      );
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
    // 客户 2026-07-30「审核后 库存没有过来」: 调拨审批通过 ≠ 库存过账, 还要回单据点一次
    // 「确认调拨入库」。批完人停在 OA 列表, 这里不导航就是 dead-end (防呆 Rule 5) ——
    // 实测客户 3 张单卡在 APPROVED, 最早一张卡了 6 周。
    if (action === 'APPROVE' && row.moduleCode === 'INVENTORY_TRANSFER' && row.businessEntityId) {
      try {
        await ElMessageBox.confirm(
          '审批通过后库存还没有过账。需要回到调拨单点一次「确认调拨入库」，调出仓才会扣减、调入仓才会收到货。',
          '还差最后一步：确认入库',
          { confirmButtonText: '去确认入库', cancelButtonText: '稍后处理', type: 'warning' },
        );
        router.push(`/transfer/${row.businessEntityId}`);
      } catch {
        // 选择"稍后处理": 不跳转; 单据列表与首页待办仍会持续提示。
      }
    }
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
            <el-option
              v-for="(label, code) in MODULE_LABELS"
              :key="code"
              :label="label"
              :value="code"
            />
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
        <!-- 权威表在后端, moduleLabel 由 DTO 下发; MODULE_LABELS 仅兜底 (见其定义处注释) -->
        <el-table-column label="业务类型" width="120">
          <template #default="{ row }">{{ row.moduleLabel || enumLabel(row.moduleCode, MODULE_LABELS) }}</template>
        </el-table-column>
        <el-table-column prop="businessSummary" label="业务单据" min-width="260" show-overflow-tooltip />
        <el-table-column prop="currentNodeLabel" label="当前节点" min-width="150" show-overflow-tooltip />
        <el-table-column label="授权角色" min-width="180">
          <template #default="{ row }">{{ row.approverRoles?.map((role: string) => enumLabel(role)).join('、') || '-' }}</template>
        </el-table-column>
        <!--
          申请人为空有两种成因, 不能混为一谈:
            systemInitiated=true  → 定时任务等系统流程发起, 本来就没有人 (如月度会计期间结账)
            username 查不到       → 用户已删, 该显示「—」
          后端的判据是 initiatedBy == null, 不是 username 是否为空。
        -->
        <el-table-column label="申请人" width="130">
          <template #default="{ row }">
            <span v-if="row.systemInitiated" class="system-initiator">系统自动发起</span>
            <span v-else>{{ row.initiatedByUsername || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" min-width="180">
          <template #default="{ row }">{{ formatDateTime(row.initiatedAt) }}</template>
        </el-table-column>
        <!--
          操作列不再 fixed: fixed 列在 Element Plus 里是独立浮层, 宽度与主表分别计算,
          内容一换行两边就对不齐(实测调拨待办的表头与内容错位)。本表 7 列合计约 1190px,
          常规屏幕不横向滚动, 钉住操作列换不来什么, 却把对齐搞坏了。
        -->
        <el-table-column label="操作" width="170">
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
/* 系统发起没有人类申请人, 弱化处理以免被当成某个真实用户名 */
.system-initiator { color: var(--el-text-color-secondary); }
.header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.header h2 { margin: 0 0 6px; }
.header p { margin: 0; color: var(--el-text-color-secondary); }
.el-pagination { margin-top: 16px; justify-content: flex-end; }
:deep(.deep-linked-approval-row > td.el-table__cell) { background: var(--el-color-primary-light-9) !important; }
</style>
