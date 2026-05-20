<script setup lang="ts">
// Sprint 7 wave 2 T5 — 提成记录列表.
// HJ Round 13 §15 业绩 6 项: 提成 commission CRUD.
// 防呆 R3: status dropdown (PENDING/PAID/CANCELLED).
import { ref, onMounted } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import { handleCatchError } from '@/utils/errorToast';
import {
  listCommissions,
  markCommissionPaid,
  cancelCommission,
  type Commission,
  type CommissionStatus,
} from '@/api/salesTarget';

const status = ref<CommissionStatus | ''>('');
const page = ref(0);
const size = ref(20);
const total = ref(0);

const items = ref<Commission[]>([]);
const loading = ref(false);

const statusLabel = (s: CommissionStatus): string =>
  ({ PENDING: '待发放', PAID: '已发放', CANCELLED: '已取消' }[s]);

async function load(): Promise<void> {
  loading.value = true;
  try {
    const res = await listCommissions({
      status: status.value || undefined,
      page: page.value,
      size: size.value,
    });
    items.value = res.content;
    total.value = res.totalElements;
  } catch (e) {
    handleCatchError(e, '加载提成记录失败');
  } finally {
    loading.value = false;
  }
}

async function onMarkPaid(row: Commission): Promise<void> {
  try {
    // R2 防呆 — 含 amount + salesId context
    await ElMessageBox.confirm(
      `确认将销售员 ${row.salesId} 的提成 ¥${Number(row.amount).toLocaleString()} 标记为已发放?`,
      '标记发放',
      { type: 'warning', confirmButtonText: '确认发放', cancelButtonText: '取消' },
    );
    await markCommissionPaid(row.id);
    ElMessage.success('已标记发放');
    await load();
  } catch (e) {
    // user cancel returns 'cancel' string — silently swallow
    if (e === 'cancel' || (e instanceof Error && e.message === 'cancel')) return;
    handleCatchError(e, '操作失败');
  }
}

async function onCancel(row: Commission): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认取消销售员 ${row.salesId} 的提成 ¥${Number(row.amount).toLocaleString()}? (商机仍保留)`,
      '取消提成',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '关闭' },
    );
    await cancelCommission(row.id);
    ElMessage.success('已取消');
    await load();
  } catch (e) {
    if (e === 'cancel' || (e instanceof Error && e.message === 'cancel')) return;
    handleCatchError(e, '操作失败');
  }
}

function onPageChange(p: number): void {
  page.value = p - 1;
  load();
}

onMounted(load);
</script>

<template>
  <div class="commission-list">
    <div class="header">
      <h2>提成记录</h2>
      <p class="subtitle">SalesOpportunity CLOSED_WON 时自动计算 — HJ Round 13 §15 业绩 6 项</p>
    </div>

    <div class="filter-bar">
      <el-form inline>
        <el-form-item label="状态">
          <el-select v-model="status" placeholder="全部" clearable style="width: 140px" @change="load">
            <el-option label="待发放" value="PENDING" />
            <el-option label="已发放" value="PAID" />
            <el-option label="已取消" value="CANCELLED" />
          </el-select>
        </el-form-item>
      </el-form>
    </div>

    <el-table v-loading="loading" :data="items" border>
      <el-table-column prop="salesId" label="销售员 ID" width="100" />
      <el-table-column prop="salesOpportunityId" label="商机 ID" min-width="200" show-overflow-tooltip />
      <el-table-column label="百分比" width="100">
        <template #default="{ row }">{{ row.percentage }}%</template>
      </el-table-column>
      <el-table-column label="提成金额" width="160">
        <template #default="{ row }">¥ {{ Number(row.amount).toLocaleString() }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PAID' ? 'success' : row.status === 'CANCELLED' ? 'info' : 'warning'">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="paidAt" label="发放时间" width="180" />
      <el-table-column prop="createdAt" label="生成时间" width="180" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'PENDING'"
            size="small"
            type="primary"
            @click="onMarkPaid(row)"
          >标记发放</el-button>
          <el-button
            v-if="row.status === 'PENDING'"
            size="small"
            @click="onCancel(row)"
          >取消</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination">
      <el-pagination
        :current-page="page + 1"
        :page-size="size"
        :total="total"
        layout="prev, pager, next, total"
        @current-change="onPageChange"
      />
    </div>
  </div>
</template>

<style lang="scss" scoped>
.commission-list {
  padding: 16px;
  .header h2 { margin: 0; }
  .subtitle { color: #909399; margin: 4px 0 16px; font-size: 13px; }
  .filter-bar { margin-bottom: 16px; }
  .pagination { margin-top: 16px; display: flex; justify-content: flex-end; }
}
</style>
