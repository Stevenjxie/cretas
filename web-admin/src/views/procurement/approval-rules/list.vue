<script setup lang="ts">
/**
 * 采购审批规则 自由配置 (F006 客户)
 *
 * 配置3 个字段决定 PO 提交后是否需要财务复核:
 *   - priceVarianceThreshold: 价格偏差%阈值 (任一行偏差 > 此值 → 财务审)
 *   - amountThreshold: 金额阈值 (订单总额 > 此值 → 财务审, null = 不参与判断)
 *   - enabled: 启用/关闭 (关闭后所有 PO 直接 APPROVED, 不经财务)
 *
 * 评估: 任一条件触发 → PENDING_FINANCE_REVIEW. 都不触发 → APPROVED.
 */
import { ref, computed, onMounted, reactive } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { get, post, put, del } from '@/api/request';
import { getFactoryId } from '@/api/smartbi/common';

interface ApprovalRule {
  id: string;
  factoryId: string;
  ruleName: string;
  priceVarianceThreshold: number;
  amountThreshold: number | null;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

const rules = ref<ApprovalRule[]>([]);
const loading = ref(false);
const dialogVisible = ref(false);
const editingId = ref<string | null>(null);
const formRef = ref<FormInstance>();

const form = reactive({
  ruleName: '',
  priceVarianceThreshold: 10.00,
  amountThreshold: null as number | null,
  enabled: true,
});

const formRules: FormRules = {
  ruleName: [
    { required: true, message: '规则名称不能为空', trigger: 'blur' },
    { max: 100, message: '名称不超过 100 字', trigger: 'blur' },
  ],
  priceVarianceThreshold: [
    { required: true, message: '价格偏差阈值不能为空', trigger: 'blur' },
    { type: 'number', min: 0, max: 100, message: '请填 0-100 之间数', trigger: 'blur' },
  ],
};

const factoryId = computed(() => {
  try {
    return getFactoryId();
  } catch {
    return '';
  }
});

async function loadRules() {
  if (!factoryId.value) {
    ElMessage.error('未登录或工厂 ID 缺失');
    return;
  }
  loading.value = true;
  try {
    const resp = await get<ApprovalRule[]>(`/${factoryId.value}/purchase-approval-rules`);
    rules.value = resp?.data ?? [];
  } catch (e) {
    ElMessage.error('加载规则失败: ' + (e instanceof Error ? e.message : String(e)));
  } finally {
    loading.value = false;
  }
}

function openCreateDialog() {
  editingId.value = null;
  form.ruleName = '';
  form.priceVarianceThreshold = 10.00;
  form.amountThreshold = null;
  form.enabled = true;
  dialogVisible.value = true;
}

function openEditDialog(row: ApprovalRule) {
  editingId.value = row.id;
  form.ruleName = row.ruleName;
  form.priceVarianceThreshold = Number(row.priceVarianceThreshold);
  form.amountThreshold = row.amountThreshold !== null ? Number(row.amountThreshold) : null;
  form.enabled = row.enabled;
  dialogVisible.value = true;
}

async function handleSave() {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  try {
    const payload = {
      ruleName: form.ruleName,
      priceVarianceThreshold: form.priceVarianceThreshold,
      amountThreshold: form.amountThreshold,
      enabled: form.enabled,
    };
    if (editingId.value) {
      await put(`/${factoryId.value}/purchase-approval-rules/${editingId.value}`, payload);
      ElMessage.success('规则更新成功');
    } else {
      await post(`/${factoryId.value}/purchase-approval-rules`, payload);
      ElMessage.success('规则创建成功');
    }
    dialogVisible.value = false;
    await loadRules();
  } catch (e) {
    ElMessage.error('保存失败: ' + (e instanceof Error ? e.message : String(e)));
  }
}

async function handleToggle(row: ApprovalRule) {
  try {
    await post(`/${factoryId.value}/purchase-approval-rules/${row.id}/toggle`, {});
    ElMessage.success(row.enabled ? '规则已禁用' : '规则已启用');
    await loadRules();
  } catch (e) {
    ElMessage.error('操作失败: ' + (e instanceof Error ? e.message : String(e)));
  }
}

async function handleDelete(row: ApprovalRule) {
  try {
    await ElMessageBox.confirm(
      `确定删除规则「${row.ruleName}」? 删除后此工厂将使用系统默认 (10% 偏差, 无金额阈值).`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    );
    await del(`/${factoryId.value}/purchase-approval-rules/${row.id}`);
    ElMessage.success('规则已删除');
    await loadRules();
  } catch (e) {
    if (e === 'cancel' || (e as Error)?.message === 'cancel') return;
    ElMessage.error('删除失败: ' + (e instanceof Error ? e.message : String(e)));
  }
}

onMounted(loadRules);
</script>

<template>
  <div class="page-wrapper">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <h3 style="margin: 0">采购审批规则</h3>
            <p style="margin: 4px 0 0; color: #909399; font-size: 13px">
              配置 PO 提交后, 满足什么条件需要财务复核. 不满足任何条件 → 直接 APPROVED.
            </p>
          </div>
          <el-button type="primary" @click="openCreateDialog">+ 新增规则</el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 16px">
        <p style="margin: 4px 0">
          <strong>评估逻辑</strong> (任一条件触发 → 进财务待审):
        </p>
        <ul style="margin: 4px 0 0 20px; padding: 0">
          <li><strong>价格偏差</strong>: 任一行单价 vs (BOM 均价 / 上次入库价) 偏差 &gt; 阈值</li>
          <li><strong>金额阈值</strong>: 订单总额 &gt; 阈值 (留空 = 不参与判断)</li>
        </ul>
        <p style="margin: 8px 0 0; color: #67c23a">
          多条同时启用时取 <strong>最新</strong> 一条 (按创建时间). 关闭所有规则 = 全部直接 APPROVED.
        </p>
      </el-alert>

      <el-table :data="rules" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="ruleName" label="规则名称" min-width="150" />
        <el-table-column label="价格偏差阈值" width="140" align="center">
          <template #default="{ row }">
            <el-tag type="warning">{{ Number(row.priceVarianceThreshold).toFixed(2) }}%</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="金额阈值" width="160" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.amountThreshold !== null" type="danger">
              ¥{{ Number(row.amountThreshold).toLocaleString() }}
            </el-tag>
            <span v-else style="color: #909399">— 不参与</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.enabled" type="success">启用</el-tag>
            <el-tag v-else type="info">已禁用</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="最后修改" width="180" align="center">
          <template #default="{ row }">
            <span v-if="row.updatedAt">{{ new Date(row.updatedAt).toLocaleString('zh-CN') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" align="center">
          <template #default="{ row }">
            <el-button size="small" link @click="openEditDialog(row)">编辑</el-button>
            <el-button
              size="small"
              link
              :type="row.enabled ? 'warning' : 'success'"
              @click="handleToggle(row)"
            >{{ row.enabled ? '禁用' : '启用' }}</el-button>
            <el-button size="small" link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && rules.length === 0" style="text-align: center; padding: 40px; color: #909399">
        <p>暂无规则 — 系统将使用默认 <strong>10%</strong> 价格偏差阈值, 无金额阈值</p>
        <p style="font-size: 13px">点 + 新增规则 自定义本工厂阈值</p>
      </div>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑规则' : '新增规则'"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="130px">
        <el-form-item label="规则名称" prop="ruleName">
          <el-input v-model="form.ruleName" placeholder="例: 默认采购审批规则" maxlength="100" show-word-limit />
        </el-form-item>

        <el-form-item label="价格偏差阈值 %" prop="priceVarianceThreshold">
          <el-input-number
            v-model="form.priceVarianceThreshold"
            :min="0"
            :max="100"
            :precision="2"
            :step="1"
            style="width: 100%"
          />
          <p style="margin: 4px 0 0; color: #909399; font-size: 12px">
            任一行单价偏差 (vs BOM 均价/上次入库价) 超此值 → 进财务待审
          </p>
        </el-form-item>

        <el-form-item label="金额阈值 ¥">
          <el-input-number
            v-model="form.amountThreshold"
            :min="0"
            :precision="2"
            :step="1000"
            placeholder="留空 = 不参与判断"
            style="width: 100%"
            controls-position="right"
          />
          <p style="margin: 4px 0 0; color: #909399; font-size: 12px">
            订单总金额超过此值 → 进财务待审. 留空 (清零) = 不按金额触发
          </p>
        </el-form-item>

        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <span style="margin-left: 12px; color: #909399; font-size: 12px">
            关闭后此规则不参与评估. 全部规则都关 = 所有 PO 直接 APPROVED
          </span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">{{ editingId ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
