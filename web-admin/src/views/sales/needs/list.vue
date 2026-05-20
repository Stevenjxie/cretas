<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import {
  listSalesNeeds,
  createSalesNeed,
  confirmSalesNeed,
  convertSalesNeed,
  cancelSalesNeed,
  type SalesNeed,
  type SalesNeedStatus,
  type SalesNeedPriority,
  type SalesNeedCreateRequest,
} from '@/api/salesNeed';

const router = useRouter();

const list = ref<SalesNeed[]>([]);
const total = ref(0);
const loading = ref(false);
const page = ref(0);
const size = ref(20);
const statusFilter = ref<string>('');

const dialogVisible = ref(false);
const submitting = ref(false);
const form = reactive<SalesNeedCreateRequest>({
  customerId: '',
  customerName: '',
  productId: '',
  productName: '',
  qtyDemand: 0,
  unit: '',
  expectedDeliveryDate: undefined,
  priority: 'NORMAL',
  remark: '',
});

async function load(): Promise<void> {
  loading.value = true;
  try {
    const res = await listSalesNeeds({
      status: statusFilter.value || undefined,
      page: page.value,
      size: size.value,
    });
    list.value = res?.data?.content ?? [];
    total.value = res?.data?.totalElements ?? 0;
  } catch (e) {
    // UX polish (2026-05-20): interceptor already shows specific 4xx/5xx message;
    // only fire fallback for pure network errors.
    handleCatchError(e, '加载失败,请检查网络');
  } finally {
    loading.value = false;
  }
}

function openCreate(): void {
  Object.assign(form, {
    customerId: '',
    customerName: '',
    productId: '',
    productName: '',
    qtyDemand: 0,
    unit: '',
    expectedDeliveryDate: undefined,
    priority: 'NORMAL',
    remark: '',
  });
  dialogVisible.value = true;
}

async function submitCreate(): Promise<void> {
  if (!form.customerId || !form.productId || !form.qtyDemand || !form.unit) {
    ElMessage.warning('请填写客户/产品/数量/单位');
    return;
  }
  submitting.value = true;
  try {
    await createSalesNeed(form);
    ElMessage.success('创建成功');
    dialogVisible.value = false;
    page.value = 0;
    load();
  } catch (e) {
    // UX polish (2026-05-20): interceptor surfaces backend message + actionHint;
    // local fallback only fires when no HTTP status (pure network error).
    handleCatchError(e, '创建失败,请检查网络');
  } finally {
    submitting.value = false;
  }
}

async function handleConfirm(row: SalesNeed): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认需求 ${row.id.slice(0, 8)}? 确认后可转销售订单.`, '确认',
        { type: 'warning' });
    await confirmSalesNeed(row.id);
    ElMessage.success('已确认');
    load();
  } catch {
    /* 取消 */
  }
}

async function handleConvert(row: SalesNeed): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将需求转为销售订单? 产品: ${row.productName ?? row.productId}, 数量: ${row.qtyDemand} ${row.unit}.`,
      '一键转销售单',
      { type: 'warning', confirmButtonText: '转换' });
    const res = await convertSalesNeed(row.id);
    ElMessage.success('已转换, 跳转到销售订单');
    router.push(`/sales/orders/${res.data.id}`);
  } catch {
    /* 取消 */
  }
}

async function handleCancel(row: SalesNeed): Promise<void> {
  try {
    await ElMessageBox.confirm('取消此需求?', '确认', { type: 'warning' });
    await cancelSalesNeed(row.id);
    ElMessage.success('已取消');
    load();
  } catch {
    /* 取消 */
  }
}

function statusTag(s: SalesNeedStatus): { type: 'info' | 'warning' | 'success' | 'danger'; label: string } {
  if (s === 'DRAFT') return { type: 'info', label: '草稿' };
  if (s === 'CONFIRMED') return { type: 'warning', label: '已确认' };
  if (s === 'CONVERTED_TO_SO') return { type: 'success', label: '已转销售单' };
  return { type: 'danger', label: '已取消' };
}

function priorityTag(p: SalesNeedPriority): { type: 'info' | 'warning' | 'danger'; label: string } {
  if (p === 'URGENT') return { type: 'danger', label: '紧急' };
  if (p === 'HIGH') return { type: 'warning', label: '高' };
  if (p === 'LOW') return { type: 'info', label: '低' };
  return { type: 'info', label: '普通' };
}

function onPageChange(p: number): void {
  page.value = p - 1;
  load();
}

function onStatusChange(): void {
  page.value = 0;
  load();
}

onMounted(load);
</script>

<template>
  <div class="sales-needs">
    <div class="header">
      <h2>客户需求</h2>
      <div class="actions">
        <el-radio-group v-model="statusFilter" @change="onStatusChange" size="default">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button label="DRAFT">草稿</el-radio-button>
          <el-radio-button label="CONFIRMED">已确认</el-radio-button>
          <el-radio-button label="CONVERTED_TO_SO">已转单</el-radio-button>
          <el-radio-button label="CANCELLED">已取消</el-radio-button>
        </el-radio-group>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建需求</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column label="客户" min-width="160">
        <template #default="{ row }">{{ row.customerName ?? row.customerId }}</template>
      </el-table-column>
      <el-table-column label="产品" min-width="160">
        <template #default="{ row }">{{ row.productName ?? row.productId }}</template>
      </el-table-column>
      <el-table-column prop="qtyDemand" label="数量" width="110">
        <template #default="{ row }">{{ row.qtyDemand }} {{ row.unit }}</template>
      </el-table-column>
      <el-table-column prop="expectedDeliveryDate" label="期望交货日" width="120" />
      <el-table-column label="优先级" width="100">
        <template #default="{ row }">
          <el-tag :type="priorityTag(row.priority).type" size="small">
            {{ priorityTag(row.priority).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status).type" size="small">
            {{ statusTag(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'DRAFT'"
            size="small"
            link
            @click="handleConfirm(row)"
          >确认</el-button>
          <el-button
            v-if="row.status === 'CONFIRMED'"
            size="small"
            type="success"
            link
            @click="handleConvert(row)"
          >一键转销售单</el-button>
          <el-button
            v-if="row.status === 'CONVERTED_TO_SO' && row.convertedSalesOrderId"
            size="small"
            link
            @click="router.push(`/sales/orders/${row.convertedSalesOrderId}`)"
          >查看销售单</el-button>
          <el-button
            v-if="row.status === 'DRAFT' || row.status === 'CONFIRMED'"
            size="small"
            type="danger"
            link
            @click="handleCancel(row)"
          >取消</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > 0"
      :current-page="page + 1"
      :page-size="size"
      :total="total"
      layout="prev, pager, next, total"
      @current-change="onPageChange"
      class="pagination"
    />

    <el-dialog v-model="dialogVisible" title="新建客户需求" width="600px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="客户ID *">
          <el-input v-model="form.customerId" placeholder="客户 ID (UUID)" />
        </el-form-item>
        <el-form-item label="客户名称">
          <el-input v-model="form.customerName" placeholder="客户名称 (冗余, 用于显示)" />
        </el-form-item>
        <el-form-item label="产品ID *">
          <el-input v-model="form.productId" placeholder="ProductType ID" />
        </el-form-item>
        <el-form-item label="产品名称">
          <el-input v-model="form.productName" placeholder="产品名称 (冗余)" />
        </el-form-item>
        <el-form-item label="数量 *">
          <el-input-number v-model="form.qtyDemand" :min="0" :precision="4" />
        </el-form-item>
        <el-form-item label="单位 *">
          <el-input v-model="form.unit" placeholder="如 kg / 箱 / 件" style="width: 200px" />
        </el-form-item>
        <el-form-item label="期望交货日">
          <el-date-picker
            v-model="form.expectedDeliveryDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="可选"
          />
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="form.priority" style="width: 200px">
            <el-option label="低" value="LOW" />
            <el-option label="普通" value="NORMAL" />
            <el-option label="高" value="HIGH" />
            <el-option label="紧急" value="URGENT" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.sales-needs {
  padding: 16px;
  .header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
    h2 { margin: 0; }
    .actions {
      display: flex;
      gap: 12px;
      align-items: center;
    }
  }
  .pagination {
    margin-top: 16px;
    justify-content: flex-end;
  }
}
</style>
