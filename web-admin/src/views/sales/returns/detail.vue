<script setup lang="ts">
/**
 * 销售退货详情 — T-RTA (issue #531) frontend MVP.
 * Drives backend ReturnOrderController state transitions: submit / approve / reject / complete.
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft, Refresh } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import { formatDateTime } from '@/utils/dateFormat';
import type { TableRow } from '@/types/api';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
const canApprove = computed(() => permissionStore.canWrite('sales'));
// 六扇门 Tier0 #16: 退货财审门. 只有财务角色 (finance:read_write) 可财务审批.
const canFinanceApprove = computed(() => permissionStore.canWrite('finance'));

const returnOrderId = computed(() => route.params.id as string);
const loading = ref(false);
const submitting = ref(false);
const order = ref<TableRow | null>(null);

function statusType(s: string) {
  switch (s) {
    case 'DRAFT': return 'info';
    case 'SUBMITTED': return 'warning';
    case 'APPROVED': return 'primary';
    case 'FINANCE_APPROVED': return 'success';
    case 'REJECTED': return 'danger';
    case 'PROCESSING': return 'warning';
    case 'COMPLETED': return 'success';
    default: return 'info';
  }
}

function statusLabel(s: string) {
  const map: Record<string, string> = {
    DRAFT: '草稿', SUBMITTED: '已提交', APPROVED: '已审批',
    FINANCE_APPROVED: '财务已审',
    REJECTED: '已驳回', PROCESSING: '处理中', COMPLETED: '已完成',
  };
  return map[s] || s || '-';
}

onMounted(() => loadData());

async function loadData() {
  if (!factoryId.value || !returnOrderId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/return-orders/${returnOrderId.value}`);
    if (res.success && res.data) {
      order.value = res.data;
    } else if (res.success === false) {
      ElMessage.error(res.message || '加载退货单失败');
    }
  } catch { /* interceptor */ }
  finally { loading.value = false; }
}

async function handleAction(action: 'submit' | 'approve' | 'finance-approve' | 'reject' | 'complete') {
  // T-RTA business logic (#571): messages reflect actual backend branching by withGoods.
  // Old confirm message LIED ("approve triggers inbound") — fixed to match real flow.
  const withGoods = order.value?.withGoods !== false; // null/undefined defaults to true (legacy + new primary)
  // 防呆: 确认框带单据上下文 (单号 + 金额).
  const ctx = order.value
    ? `退货单 ${order.value.returnNumber || '-'}　金额 ¥${order.value.totalAmount ?? '-'}\n`
    : '';
  const approveMsg = withGoods
    ? '审批通过后, 退货单状态置为 已审批, 等待财务审批. 完成时才触发 AR/AP 冲减. 确认审批?'
    : '审批通过后, 立即触发 AR/AP 冲减 (无库存动作). 后续需财务审批 + 完成标记状态. 确认审批?';
  // 六扇门 Tier0 #16: 退货跟钱有关, 业务审批后必须财务审批才能完成/出货.
  const financeMsg = '财务审批通过后, 退货单状态置为 财务已审, 可交仓管完成出货. 确认财务审批?';
  const completeMsg = withGoods
    ? '标记完成: 触发 AR/AP 冲减. 注意: 库存入库到总仓 + 不良品状态 暂未自动实现 (issue #571 Phase C), 需仓管员手动入库. 确认完成?'
    : '标记完成: 仅状态置为已完成. AR/AP 冲减已在审批时完成. 确认?';
  const messages: Record<string, string> = {
    submit: '确认提交本退货单进入审批?',
    approve: approveMsg,
    'finance-approve': financeMsg,
    reject: '驳回后退货单状态置为已驳回, 不再处理. 确认驳回?',
    complete: completeMsg,
  };
  try {
    await ElMessageBox.confirm(ctx + messages[action], '退货单操作', { type: 'warning' });
  } catch { return; }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/return-orders/${returnOrderId.value}/${action}`, {});
    if (res.success) {
      ElMessage.success('操作成功');
      await loadData();
    }
  } catch { /* interceptor 透传后端 message (含 409 状态机/防呆 hint) */ }
  finally { submitting.value = false; }
}

function goBack() { router.push('/sales/returns'); }
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card v-if="!loading && !order" shadow="never">
      <el-empty description="退货单不存在">
        <el-button @click="goBack">返回列表</el-button>
      </el-empty>
    </el-card>

    <template v-if="order">
      <div class="detail-header">
        <div class="header-left">
          <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
          <h2 class="title">{{ order.returnNumber || '-' }}</h2>
          <el-tag :type="statusType(order.status)" size="large">{{ statusLabel(order.status) }}</el-tag>
        </div>
        <div style="display: flex; gap: 8px;">
          <el-button :icon="Refresh" @click="loadData">刷新</el-button>
          <el-button v-if="order.status === 'DRAFT'" type="primary"
                     :loading="submitting" @click="handleAction('submit')">提交审批</el-button>
          <template v-if="order.status === 'SUBMITTED' && canApprove">
            <el-button type="success" :loading="submitting" @click="handleAction('approve')">审批通过</el-button>
            <el-button type="danger" :loading="submitting" @click="handleAction('reject')">驳回</el-button>
          </template>
          <!-- 六扇门 Tier0 #16: APPROVED 必须先财务审批 (仅财务角色) 才能完成. -->
          <el-button v-if="order.status === 'APPROVED' && canFinanceApprove" type="primary"
                     :loading="submitting" @click="handleAction('finance-approve')">财务审批</el-button>
          <el-tag v-else-if="order.status === 'APPROVED'" type="info" size="large">等待财务审批</el-tag>
          <!-- FINANCE_APPROVED / PROCESSING: 财务已审, 仓管可完成出货. -->
          <el-button v-if="order.status === 'FINANCE_APPROVED' || order.status === 'PROCESSING'"
                     type="primary" :loading="submitting" @click="handleAction('complete')">标记完成</el-button>
        </div>
      </div>

      <el-card shadow="never" class="detail-card">
        <template #header><span class="section-title">基本信息</span></template>
        <el-descriptions :column="3" border>
          <el-descriptions-item label="退货类型">{{ order.returnType === 'SALES_RETURN' ? '销售退货' : '采购退货' }}</el-descriptions-item>
          <!-- T-RTA business logic (#571): 有食物/无食物 drives 审批/完成 时机 + 库存动作. -->
          <el-descriptions-item label="退货分支">
            <el-tag :type="order.withGoods === false ? 'info' : 'warning'" size="small">
              {{ order.withGoods === false ? '无食物 (直接退款)' : '有食物 (实物入库)' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="客户/对方">{{ order.counterpartyName || order.counterpartyId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="源订单号">{{ order.sourceOrderNumber || order.sourceOrderId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="退货日期">{{ order.returnDate || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="退货金额">{{ formatAmount(order.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="审批人">{{ order.approvedByName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="原因" :span="3">{{ order.reason || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ order.remark || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(order.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatDateTime(order.updatedAt) }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" class="detail-card">
        <template #header><span class="section-title">退货明细</span></template>
        <el-table :data="order.items || []" border stripe>
          <el-table-column label="品名" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.itemName || row.productName || row.materialName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="batchNumber" label="批次号" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.batchNumber || '-' }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="退货数量" width="110" align="right" />
          <el-table-column v-if="canViewPrice" prop="unitPrice" label="单价" width="110" align="right">
            <template #default="{ row }">{{ formatAmount(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="小计" width="120" align="right">
            <template #default="{ row }">{{ formatAmount((Number(row.quantity) || 0) * (Number(row.unitPrice) || 0)) }}</template>
          </el-table-column>
          <el-table-column label="行原因" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.reason || '-' }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { padding: 20px; height: 100%; overflow-y: auto; }
.detail-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;
  .header-left { display: flex; align-items: center; gap: 12px;
    .title { font-size: 18px; font-weight: 600; margin: 0; color: #303133; }
  }
}
.detail-card { margin-bottom: 16px;
  :deep(.el-card__header) { padding: 14px 18px; border-bottom: 1px solid #ebeef5; }
}
.section-title { font-size: 15px; font-weight: 600; color: #303133; }
</style>
