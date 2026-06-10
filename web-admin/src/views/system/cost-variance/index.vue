<script setup lang="ts">
/**
 * SP3 方差阈值配置页 — ProductCostVarianceConfig CRUD
 *
 * 后端: /api/mobile/{factoryId}/bom/variance-configs
 * productTypeId = null → 工厂级全局默认阈值 (10%)
 *
 * 防呆设计:
 * - 新建/编辑 dialog 带上下文标题 (产品名/全局)
 * - 删除前 confirm 带条目名称
 * - sticky error toast (duration:0 + showClose)
 */
import { ref, computed, onMounted, reactive } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Edit, Delete } from '@element-plus/icons-vue';
import { get } from '@/api/request';
import type { ApiResponse } from '@/types/api';
import {
  listVarianceConfigs,
  createVarianceConfig,
  updateVarianceConfig,
  deleteVarianceConfig,
  type ProductCostVarianceConfig,
} from '@/api/costVarianceConfig';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

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
    // 非阻塞
  }
}

function productTypeName(id: number | null): string {
  if (id == null) return '全局默认 (所有产品)';
  const found = productTypes.value.find((p) => String(p.id) === String(id));
  return found ? found.name : `ID:${id}`;
}

// ============================================================================
// 列表
// ============================================================================

const configs = ref<ProductCostVarianceConfig[]>([]);
const total = ref(0);
const currentPage = ref(1);
const pageSize = ref(20);
const listLoading = ref(false);

async function loadList() {
  if (!factoryId.value) return;
  listLoading.value = true;
  try {
    const res = await listVarianceConfigs(factoryId.value, {
      page: currentPage.value,
      size: pageSize.value,
    });
    if (res.success && res.data) {
      configs.value = res.data.content;
      total.value = res.data.totalElements;
    } else {
      ElMessage({ message: res.message || '加载失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: '网络错误，请重试', type: 'error', duration: 0, showClose: true });
  } finally {
    listLoading.value = false;
  }
}

function handlePageChange(page: number) {
  currentPage.value = page;
  loadList();
}

// ============================================================================
// 创建 / 编辑 Dialog
// ============================================================================

type DialogMode = 'create' | 'edit';

interface FormData {
  productTypeId: number | null;
  varianceThreshold: number;
  isActive: boolean;
  remark: string;
}

const dialogVisible = ref(false);
const dialogMode = ref<DialogMode>('create');
const editingId = ref<string | null>(null);
const submitting = ref(false);

const formData = reactive<FormData>({
  productTypeId: null,
  varianceThreshold: 10,
  isActive: true,
  remark: '',
});

const dialogTitle = computed(() => {
  const scopeLabel = formData.productTypeId == null
    ? '全局默认'
    : productTypeName(formData.productTypeId);
  return dialogMode.value === 'create'
    ? `新建方差阈值配置 — ${scopeLabel}`
    : `编辑方差阈值配置 — ${scopeLabel}`;
});

function openCreate() {
  dialogMode.value = 'create';
  editingId.value = null;
  formData.productTypeId = null;
  formData.varianceThreshold = 10;
  formData.isActive = true;
  formData.remark = '';
  dialogVisible.value = true;
}

function openEdit(row: ProductCostVarianceConfig) {
  dialogMode.value = 'edit';
  editingId.value = row.id;
  formData.productTypeId = row.productTypeId;
  formData.varianceThreshold = row.varianceThreshold;
  formData.isActive = row.isActive;
  formData.remark = row.remark ?? '';
  dialogVisible.value = true;
}

async function handleSubmit() {
  if (formData.varianceThreshold <= 0 || formData.varianceThreshold > 100) {
    ElMessage({ message: '方差阈值须在 0%~100% 之间', type: 'warning', duration: 3000 });
    return;
  }
  submitting.value = true;
  try {
    let res: ApiResponse<ProductCostVarianceConfig>;
    if (dialogMode.value === 'create') {
      res = await createVarianceConfig(factoryId.value, {
        productTypeId: formData.productTypeId,
        varianceThreshold: formData.varianceThreshold,
        isActive: formData.isActive,
        remark: formData.remark || null,
      });
    } else {
      res = await updateVarianceConfig(factoryId.value, editingId.value!, {
        productTypeId: formData.productTypeId,
        varianceThreshold: formData.varianceThreshold,
        isActive: formData.isActive,
        remark: formData.remark || null,
      });
    }
    if (res.success) {
      ElMessage.success(dialogMode.value === 'create' ? '已创建方差配置' : '已更新方差配置');
      dialogVisible.value = false;
      await loadList();
    } else {
      ElMessage({ message: res.message || '操作失败', type: 'error', duration: 0, showClose: true });
    }
  } catch {
    ElMessage({ message: '网络错误，请重试', type: 'error', duration: 0, showClose: true });
  } finally {
    submitting.value = false;
  }
}

// ============================================================================
// 删除
// ============================================================================

async function handleDelete(row: ProductCostVarianceConfig) {
  const scopeLabel = productTypeName(row.productTypeId);
  try {
    await ElMessageBox.confirm(
      `确认删除「${scopeLabel}」的方差阈值配置 (${row.varianceThreshold}%)？删除后将使用系统默认阈值。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    const res = await deleteVarianceConfig(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success('已删除');
      await loadList();
    } else {
      ElMessage({ message: res.message || '删除失败', type: 'error', duration: 0, showClose: true });
    }
  } catch {
    ElMessage({ message: '网络错误，请重试', type: 'error', duration: 0, showClose: true });
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
  <div class="cost-variance-page">
    <div class="page-header">
      <h2 class="title">方差阈值配置</h2>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建配置</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      title="方差阈值控制财审成本超支告警 — productTypeId=null 表示工厂级全局默认阈值 (10%)。产品级配置优先级高于全局。"
      style="margin-bottom: 16px"
    />

    <el-card shadow="never" v-loading="listLoading">
      <el-table :data="configs" stripe empty-text="暂无方差配置">
        <el-table-column label="适用范围" min-width="200">
          <template #default="{ row }">
            <span v-if="row.productTypeId == null" class="global-badge">
              <el-tag type="info" size="small">全局默认</el-tag>
            </span>
            <span v-else>{{ productTypeName(row.productTypeId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="方差阈值" align="right" min-width="120">
          <template #default="{ row }">
            <strong>{{ row.varianceThreshold }}%</strong>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" min-width="80">
          <template #default="{ row }">
            <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
              {{ row.isActive ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="200">
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="160">
          <template #default="{ row }">{{ row.updatedAt ?? row.createdAt }}</template>
        </el-table-column>
        <el-table-column label="操作" align="center" min-width="140" fixed="right">
          <template #default="{ row }">
            <el-button :icon="Edit" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button :icon="Delete" link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > pageSize"
        style="margin-top: 16px; justify-content: flex-end; display: flex"
        :total="total"
        :page-size="pageSize"
        :current-page="currentPage"
        layout="total, prev, pager, next"
        @current-change="handlePageChange"
      />
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
            placeholder="全局默认 (所有产品)"
            style="width: 100%"
          >
            <el-option
              v-for="pt in productTypes"
              :key="pt.id"
              :label="pt.name"
              :value="Number(pt.id)"
            />
          </el-select>
          <div class="form-hint">留空表示工厂级全局默认阈值，优先级低于产品级配置</div>
        </el-form-item>

        <el-form-item label="方差阈值 (%)">
          <el-input-number
            v-model="formData.varianceThreshold"
            :min="0.01"
            :max="100"
            :precision="2"
            :step="1"
            style="width: 200px"
          />
          <div class="form-hint">实际成本超出 BOM 标准成本超过此百分比时触发超支告警</div>
        </el-form-item>

        <el-form-item label="状态">
          <el-switch
            v-model="formData.isActive"
            active-text="启用"
            inactive-text="禁用"
          />
        </el-form-item>

        <el-form-item label="备注">
          <el-input
            v-model="formData.remark"
            type="textarea"
            :rows="2"
            placeholder="可选说明"
            maxlength="200"
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
.cost-variance-page {
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
