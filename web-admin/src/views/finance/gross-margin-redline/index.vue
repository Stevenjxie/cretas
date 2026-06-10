<script setup lang="ts">
/**
 * SP5 W6 — 毛利红线配置页 — FactoryGrossMarginConfig CRUD
 *
 * 后端: /api/mobile/{factoryId}/pricing/gross-margin-configs
 * productTypeId = null → 工厂级全局默认红线
 *
 * 毛利红线 (目标毛利率) 用于报价/下单前判断报价是否低于最低可接受价 (#693)。
 * 此前只能 SQL 直写，本页提供财务/超管的可视化配置。
 *
 * RBAC: 读写均需 finance:read_write — 非财务/超管不显示编辑按钮 (canWrite('finance'))。
 *
 * 防呆设计 (fool-proof-design.md):
 * - Rule 2: 新建/编辑 dialog 带上下文标题 (产品名/全局)
 * - Rule 2: 删除前 confirm 带条目名称 + 后果说明
 * - 4-in-1: sticky error toast (duration:0 + showClose)，原样显示后端 message
 *
 * 口径: targetGrossMargin 存储为 0-1 小数 (0.30)，UI 以百分比 (30%) 展示/录入。
 */
import { ref, computed, onMounted, reactive } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Edit, Delete } from '@element-plus/icons-vue';
import { get } from '@/api/request';
import type { ApiResponse } from '@/types/api';
import {
  listGrossMarginConfigs,
  createGrossMarginConfig,
  updateGrossMarginConfig,
  deleteGrossMarginConfig,
  type FactoryGrossMarginConfig,
} from '@/api/grossMarginConfig';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);

/** 仅财务/超管 (finance:read_write) 可写。控制新建/编辑/删除按钮显隐。 */
const canWrite = computed(() => permissionStore.canWrite('finance'));

// ============================================================================
// 产品类型选项
// ============================================================================

interface ProductTypeOption {
  id: string;
  name: string;
  code?: string | null;
}
const productTypes = ref<ProductTypeOption[]>([]);

async function loadProductTypes() {
  try {
    const res = await get<ProductTypeOption[]>(`/${factoryId.value}/product-types/active`);
    if (res.success && res.data) productTypes.value = res.data;
  } catch {
    // 非阻塞: 产品下拉加载失败不影响列表
  }
}

function productTypeName(id: string | null): string {
  if (id == null) return '全局默认 (所有产品)';
  const found = productTypes.value.find((p) => String(p.id) === String(id));
  return found ? found.name : `ID:${id}`;
}

/** 小数 → 百分比展示 (0.3 → 30). */
function toPercent(decimal: number): number {
  return Math.round(decimal * 10000) / 100;
}

// ============================================================================
// 列表
// ============================================================================

const configs = ref<FactoryGrossMarginConfig[]>([]);
const listLoading = ref(false);

async function loadList() {
  if (!factoryId.value) return;
  listLoading.value = true;
  try {
    const res = await listGrossMarginConfigs(factoryId.value);
    if (res.success && res.data) {
      configs.value = res.data;
    } else {
      ElMessage({ message: res.message || '加载失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: any) {
    const msg = e?.response?.data?.message || '网络错误，请重试';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    listLoading.value = false;
  }
}

// ============================================================================
// 创建 / 编辑 Dialog
// ============================================================================

type DialogMode = 'create' | 'edit';

interface FormData {
  productTypeId: string | null;
  /** UI 录入为百分比 (30 = 30%)，提交前换算为小数 (0.30)。 */
  marginPercent: number;
  isActive: boolean;
  description: string;
}

const dialogVisible = ref(false);
const dialogMode = ref<DialogMode>('create');
const editingId = ref<string | null>(null);
const submitting = ref(false);

const formData = reactive<FormData>({
  productTypeId: null,
  marginPercent: 30,
  isActive: true,
  description: '',
});

const dialogTitle = computed(() => {
  const scopeLabel = formData.productTypeId == null
    ? '全局默认'
    : productTypeName(formData.productTypeId);
  return dialogMode.value === 'create'
    ? `新建毛利红线 — ${scopeLabel}`
    : `编辑毛利红线 — ${scopeLabel}`;
});

function openCreate() {
  dialogMode.value = 'create';
  editingId.value = null;
  formData.productTypeId = null;
  formData.marginPercent = 30;
  formData.isActive = true;
  formData.description = '';
  dialogVisible.value = true;
}

function openEdit(row: FactoryGrossMarginConfig) {
  dialogMode.value = 'edit';
  editingId.value = row.id;
  formData.productTypeId = row.productTypeId;
  formData.marginPercent = toPercent(row.targetGrossMargin);
  formData.isActive = row.isActive;
  formData.description = row.description ?? '';
  dialogVisible.value = true;
}

async function handleSubmit() {
  if (formData.marginPercent <= 0 || formData.marginPercent >= 100) {
    ElMessage({ message: '目标毛利率须在 0%~100% 之间 (不含端点)', type: 'warning', duration: 3000 });
    return;
  }
  // 百分比 → 小数, 4 位精度对齐后端 scale=4
  const targetGrossMargin = Math.round((formData.marginPercent / 100) * 10000) / 10000;
  submitting.value = true;
  try {
    let res: ApiResponse<FactoryGrossMarginConfig>;
    if (dialogMode.value === 'create') {
      res = await createGrossMarginConfig(factoryId.value, {
        productTypeId: formData.productTypeId,
        targetGrossMargin,
        isActive: formData.isActive,
        description: formData.description || null,
      });
    } else {
      res = await updateGrossMarginConfig(factoryId.value, editingId.value!, {
        targetGrossMargin,
        isActive: formData.isActive,
        description: formData.description || null,
      });
    }
    if (res.success) {
      ElMessage.success(dialogMode.value === 'create' ? '已创建毛利红线' : '已更新毛利红线');
      dialogVisible.value = false;
      await loadList();
    } else {
      ElMessage({ message: res.message || '操作失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: any) {
    // 4-in-1: 原样显示后端 message (e.g. 409 "工厂全局默认 的毛利红线已存在")
    const msg = e?.response?.data?.message || '网络错误，请重试';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    submitting.value = false;
  }
}

// ============================================================================
// 删除
// ============================================================================

async function handleDelete(row: FactoryGrossMarginConfig) {
  const scopeLabel = productTypeName(row.productTypeId);
  try {
    await ElMessageBox.confirm(
      `确认删除「${scopeLabel}」的毛利红线 (${toPercent(row.targetGrossMargin)}%)？` +
        `删除后该范围将跳过红线检查，报价不再触发低于红线预警。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    const res = await deleteGrossMarginConfig(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success('已删除');
      await loadList();
    } else {
      ElMessage({ message: res.message || '删除失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: any) {
    const msg = e?.response?.data?.message || '网络错误，请重试';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  }
}

// ============================================================================
// 初始化
// ============================================================================

onMounted(async () => {
  await Promise.all([loadProductTypes(), loadList()]);
});
</script>

<template>
  <div class="gross-margin-page">
    <div class="page-header">
      <h2 class="title">毛利红线配置</h2>
      <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新建红线</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      title="毛利红线控制报价/下单前的低毛利预警 — productTypeId 留空表示工厂级全局默认红线。产品级配置优先级高于全局。仅财务经理 / 工厂超管可配置。"
      style="margin-bottom: 16px"
    />

    <el-card shadow="never" v-loading="listLoading">
      <el-table :data="configs" stripe empty-text="暂无毛利红线配置，点击右上角「新建红线」添加">
        <el-table-column label="适用范围" min-width="220">
          <template #default="{ row }">
            <span v-if="row.productTypeId == null">
              <el-tag type="warning" size="small">全局默认</el-tag>
            </span>
            <span v-else>{{ productTypeName(row.productTypeId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="目标毛利率" align="right" min-width="130">
          <template #default="{ row }">
            <strong>{{ toPercent(row.targetGrossMargin) }}%</strong>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" min-width="80">
          <template #default="{ row }">
            <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
              {{ row.isActive ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="备注" min-width="200">
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="160">
          <template #default="{ row }">{{ row.updatedAt ?? row.createdAt }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" align="center" min-width="140" fixed="right">
          <template #default="{ row }">
            <el-button :icon="Edit" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button :icon="Delete" link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建/编辑 Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item label="适用产品 (留空 = 全局默认)">
          <el-select
            v-model="formData.productTypeId"
            clearable
            filterable
            placeholder="全局默认 (所有产品)"
            style="width: 100%"
            :disabled="dialogMode === 'edit'"
          >
            <el-option
              v-for="pt in productTypes"
              :key="pt.id"
              :label="pt.name"
              :value="String(pt.id)"
            />
          </el-select>
          <div class="form-hint">
            留空表示工厂级全局默认红线，优先级低于产品级配置。
            <span v-if="dialogMode === 'edit'">（编辑时不可更改适用范围）</span>
          </div>
        </el-form-item>

        <el-form-item label="目标毛利率 (%)">
          <el-input-number
            v-model="formData.marginPercent"
            :min="0.01"
            :max="99.99"
            :precision="2"
            :step="1"
            style="width: 200px"
          />
          <div class="form-hint">报价对应毛利低于此值时触发"低于红线"预警 (非阻断)。须在 0%~100% 之间。</div>
        </el-form-item>

        <el-form-item label="状态">
          <el-switch v-model="formData.isActive" active-text="启用" inactive-text="禁用" />
        </el-form-item>

        <el-form-item label="备注">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="2"
            placeholder="可选说明 (e.g. 镇级商务餐厅主力品类红线)"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false" :disabled="submitting">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ dialogMode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.gross-margin-page {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
  color: #303133;
}
.form-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
</style>
