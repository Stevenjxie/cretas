<script setup lang="ts">
// Sprint 7 wave 2 T5 — 提成规则 admin CRUD.
// HJ Round 13 §15 业绩 6 项: 提成规则配置 (sales/customerType nullable for most-specific match).
// 防呆 R1: percentage 0-100 (input :max).
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { handleCatchError } from '@/utils/errorToast';
import {
  listRules,
  createRule,
  updateRule,
  deleteRule,
  type CommissionRule,
} from '@/api/salesTarget';
import { useAuthStore } from '@/store/modules/auth';

const auth = useAuthStore();
const currentUserId = computed<number | null>(() =>
  auth.user && typeof auth.user.id === 'number' ? auth.user.id : null,
);

const rules = ref<CommissionRule[]>([]);
const loading = ref(false);

const dialogOpen = ref(false);
const editingId = ref<string | null>(null);
const form = ref({
  salesId: null as number | null,
  customerType: '' as string,
  percentage: 5,
  effectiveFrom: new Date().toISOString().slice(0, 10),
  effectiveTo: '' as string,
  active: true,
});

async function load(): Promise<void> {
  loading.value = true;
  try {
    rules.value = await listRules();
  } catch (e) {
    handleCatchError(e, '加载规则失败');
  } finally {
    loading.value = false;
  }
}

function openCreate(): void {
  editingId.value = null;
  form.value = {
    salesId: null,
    customerType: '',
    percentage: 5,
    effectiveFrom: new Date().toISOString().slice(0, 10),
    effectiveTo: '',
    active: true,
  };
  dialogOpen.value = true;
}

function openEdit(row: CommissionRule): void {
  editingId.value = row.id;
  form.value = {
    salesId: row.salesId,
    customerType: row.customerType || '',
    percentage: Number(row.percentage),
    effectiveFrom: row.effectiveFrom,
    effectiveTo: row.effectiveTo || '',
    active: row.active,
  };
  dialogOpen.value = true;
}

async function onSubmit(): Promise<void> {
  if (currentUserId.value === null) {
    ElMessage.warning('未取得当前用户身份');
    return;
  }
  if (form.value.percentage == null || form.value.percentage < 0 || form.value.percentage > 100) {
    ElMessage.warning('百分比必须 0-100');
    return;
  }
  if (!form.value.effectiveFrom) {
    ElMessage.warning('请选择生效日');
    return;
  }
  try {
    if (editingId.value) {
      await updateRule(editingId.value, {
        percentage: form.value.percentage,
        effectiveFrom: form.value.effectiveFrom,
        effectiveTo: form.value.effectiveTo || null,
        active: form.value.active,
      });
      ElMessage.success('规则已更新');
    } else {
      await createRule({
        salesId: form.value.salesId,
        customerType: form.value.customerType || null,
        percentage: form.value.percentage,
        effectiveFrom: form.value.effectiveFrom,
        effectiveTo: form.value.effectiveTo || null,
        createdBy: currentUserId.value,
      });
      ElMessage.success('规则已创建');
    }
    dialogOpen.value = false;
    await load();
  } catch (e) {
    handleCatchError(e, '保存失败');
  }
}

async function onDelete(row: CommissionRule): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除规则 (销售员=${row.salesId ?? '所有'} / 客户类型=${row.customerType ?? '所有'} / ${row.percentage}%)?`,
      '删除规则',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    );
    await deleteRule(row.id);
    ElMessage.success('已删除');
    await load();
  } catch (e) {
    if (e === 'cancel' || (e instanceof Error && e.message === 'cancel')) return;
    handleCatchError(e, '删除失败');
  }
}

onMounted(load);
</script>

<template>
  <div class="commission-rule-config">
    <div class="header">
      <h2>提成规则配置 (admin)</h2>
      <p class="subtitle">
        most-specific wins: 销售员+客户类型 &gt; 销售员 &gt; 客户类型 &gt; 通用 — HJ Round 13 §15 业绩 6 项
      </p>
      <div class="action-bar">
        <el-button type="primary" @click="openCreate">+ 新建规则</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rules" border>
      <el-table-column label="销售员 ID" width="120">
        <template #default="{ row }">{{ row.salesId ?? '所有' }}</template>
      </el-table-column>
      <el-table-column label="客户类型" width="120">
        <template #default="{ row }">{{ row.customerType ?? '所有' }}</template>
      </el-table-column>
      <el-table-column label="百分比" width="100">
        <template #default="{ row }">{{ row.percentage }}%</template>
      </el-table-column>
      <el-table-column prop="effectiveFrom" label="生效日" width="140" />
      <el-table-column label="结束日" width="140">
        <template #default="{ row }">{{ row.effectiveTo ?? '永久' }}</template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-tag :type="row.active ? 'success' : 'info'">{{ row.active ? '启用' : '关闭' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogOpen"
      :title="editingId ? '编辑提成规则' : '新建提成规则'"
      width="500px"
    >
      <el-form label-width="120px">
        <el-form-item label="销售员 ID">
          <el-input-number v-model="form.salesId" :min="0" controls-position="right" />
          <span style="color: #999; font-size: 12px; margin-left: 8px">留空 = 适用所有销售员</span>
        </el-form-item>
        <el-form-item label="客户类型">
          <el-input v-model="form.customerType" placeholder="留空 = 适用所有客户类型" />
        </el-form-item>
        <el-form-item label="百分比" required>
          <el-input-number
            v-model="form.percentage"
            :min="0"
            :max="100"
            :step="0.5"
            :precision="2"
            controls-position="right"
          />
          <span style="color: #999; font-size: 12px; margin-left: 8px">0-100, R1 防呆</span>
        </el-form-item>
        <el-form-item label="生效日" required>
          <el-date-picker v-model="form.effectiveFrom" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="结束日">
          <el-date-picker v-model="form.effectiveTo" type="date" value-format="YYYY-MM-DD" placeholder="留空 = 永久" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.active" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.commission-rule-config {
  padding: 16px;
  .header { margin-bottom: 16px;
    h2 { margin: 0; }
    .subtitle { color: #909399; margin: 4px 0 12px; font-size: 13px; }
    .action-bar { margin-top: 8px; }
  }
}
</style>
