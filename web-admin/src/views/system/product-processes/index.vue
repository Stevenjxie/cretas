<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Delete, Rank, Refresh } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import {
  getActiveWorkProcesses,
  getProductWorkProcesses,
  createProductWorkProcess,
  generateTasksFromProduct,
  deleteProductWorkProcess,
  batchSortProductWorkProcesses,
  type WorkProcessItem,
  type ProductWorkProcessItem
} from '@/api/processProduction';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

// 产品列表（从后端获取）
const products = ref<Array<{ id: string; name: string }>>([]);
const selectedProductId = ref('');
const productsLoading = ref(false);

// 已关联工序
const linkedProcesses = ref<ProductWorkProcessItem[]>([]);
const linkedLoading = ref(false);

// 可选工序（全部活跃工序）
const allProcesses = ref<WorkProcessItem[]>([]);

onMounted(async () => {
  await loadProducts();
  await loadAllProcesses();
});

watch(selectedProductId, () => {
  if (selectedProductId.value) {
    loadLinkedProcesses();
  } else {
    linkedProcesses.value = [];
  }
});

async function loadProducts() {
  if (!factoryId.value) return;
  productsLoading.value = true;
  try {
    const { get } = await import('@/api/request');
    const res = await get<{ content: Array<{ id: string; name: string }> }>(
      `/${factoryId.value}/product-types`, { params: { page: 1, size: 1000 } }
    );
    if (res.success && res.data?.content) {
      products.value = res.data.content;
      if (products.value.length > 0 && !selectedProductId.value) {
        selectedProductId.value = products.value[0].id;
      }
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载产品列表失败');
  } finally {
    productsLoading.value = false;
  }
}

async function loadAllProcesses() {
  if (!factoryId.value) return;
  try {
    const res = await getActiveWorkProcesses(factoryId.value);
    if (res.success && res.data) {
      allProcesses.value = Array.isArray(res.data) ? res.data : [];
    }
  } catch {
    // silent
  }
}

async function loadLinkedProcesses() {
  if (!factoryId.value || !selectedProductId.value) return;
  linkedLoading.value = true;
  try {
    const res = await getProductWorkProcesses(factoryId.value, selectedProductId.value);
    if (res.success && res.data) {
      linkedProcesses.value = Array.isArray(res.data) ? res.data : [];
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载关联工序失败');
  } finally {
    linkedLoading.value = false;
  }
}

// 可添加的工序（排除已关联的）
const availableProcesses = computed(() => {
  const linkedIds = new Set(linkedProcesses.value.map(p => p.workProcessId));
  return allProcesses.value.filter(p => !linkedIds.has(p.id));
});

async function handleAdd(wp: WorkProcessItem) {
  if (!factoryId.value || !selectedProductId.value) return;
  try {
    const nextOrder = linkedProcesses.value.length + 1;
    await createProductWorkProcess(factoryId.value, {
      productTypeId: selectedProductId.value,
      workProcessId: wp.id,
      processOrder: nextOrder,
    });
    ElMessage.success(`已添加工序「${wp.processName}」`);
    loadLinkedProcesses();
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  }
}

async function handleRemove(item: ProductWorkProcessItem) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(`确定移除工序关联？`, '确认');
    await deleteProductWorkProcess(factoryId.value, item.id);
    ElMessage.success('已移除');
    loadLinkedProcesses();
  } catch (e) {
    // UX polish (2026-05-20): ElMessageBox throws 'cancel' on user dismissal (skip);
    // interceptor handles 4xx/5xx with backend message — fallback only for network errors.
    if (e !== 'cancel') handleCatchError(e, '移除失败');
  }
}

async function handleMoveUp(index: number) {
  if (index <= 0) return;
  const items = [...linkedProcesses.value];
  [items[index - 1], items[index]] = [items[index], items[index - 1]];
  await saveSortOrder(items);
}

async function handleMoveDown(index: number) {
  if (index >= linkedProcesses.value.length - 1) return;
  const items = [...linkedProcesses.value];
  [items[index], items[index + 1]] = [items[index + 1], items[index]];
  await saveSortOrder(items);
}

async function saveSortOrder(items: ProductWorkProcessItem[]) {
  if (!factoryId.value) return;
  try {
    const sortItems = items.map((item, i) => ({ id: item.id, processOrder: i + 1 }));
    await batchSortProductWorkProcesses(factoryId.value, sortItems);
    linkedProcesses.value = items.map((item, i) => ({ ...item, processOrder: i + 1 }));
    ElMessage.success('排序已更新');
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '排序失败');
    loadLinkedProcesses();
  }
}

const selectedProductName = computed(() => {
  return products.value.find(p => p.id === selectedProductId.value)?.name || '';
});

async function handleGenerateTasks() {
  if (!factoryId.value || !selectedProductId.value) return;
  if (linkedProcesses.value.length === 0) {
    ElMessage.warning('请先为产品关联工序');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确定为「${selectedProductName.value}」生成 ${linkedProcesses.value.length} 道工序任务？`,
      '生成工序任务',
      { type: 'info' }
    );
    const res = await generateTasksFromProduct(factoryId.value, {
      productTypeId: selectedProductId.value,
      sourceCustomerName: selectedProductName.value,
    });
    if (res.success && res.data) {
      ElMessage.success(`已生成 ${res.data.length} 个工序任务`);
    } else {
      ElMessage.error(res.message || '生成失败');
    }
  } catch (e) {
    // UX polish (2026-05-20): ElMessageBox throws 'cancel' on user dismissal (skip);
    // interceptor handles 4xx/5xx with backend message — fallback only for network errors.
    if (e !== 'cancel') handleCatchError(e, '生成失败');
  }
}
</script>

<template>
  <div class="page-container">
    <el-card>
      <div class="toolbar">
        <div class="toolbar-left">
          <h2 style="margin: 0">产品-工序配置</h2>
          <el-tag type="info">{{ factoryId }}</el-tag>
        </div>
        <div class="toolbar-right">
          <el-button :icon="Refresh" @click="loadLinkedProcesses" />
        </div>
      </div>
    </el-card>

    <div class="content-layout">
      <!-- 左：产品选择 -->
      <el-card class="product-panel">
        <template #header>
          <span style="font-weight: 600">选择产品</span>
        </template>
        <el-select
          v-model="selectedProductId"
          placeholder="选择产品"
          filterable
          style="width: 100%; margin-bottom: 12px"
          :loading="productsLoading"
        >
          <el-option v-for="p in products" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>

        <div v-if="selectedProductId" class="product-info">
          <el-tag>{{ selectedProductName }}</el-tag>
          <el-text type="info" size="small" style="margin-top: 8px; display: block">
            已关联 {{ linkedProcesses.length }} 道工序
          </el-text>
        </div>
      </el-card>

      <!-- 右：工序配置 -->
      <el-card class="process-panel" v-loading="linkedLoading">
        <template #header>
          <div class="card-header">
            <span style="font-weight: 600">工序流程（按顺序执行）</span>
            <el-button
              v-if="canWrite && selectedProductId && linkedProcesses.length > 0"
              type="success"
              size="small"
              @click="handleGenerateTasks"
            >
              生成工序任务
            </el-button>
          </div>
        </template>

        <!-- 已关联工序列表 -->
        <div v-if="linkedProcesses.length === 0 && selectedProductId" class="empty-state">
          <el-empty description="暂无关联工序，请从下方添加" :image-size="80" />
        </div>

        <div v-for="(item, index) in linkedProcesses" :key="item.id" class="linked-item">
          <div class="step-number">{{ index + 1 }}</div>
          <div class="step-info">
            <div class="step-name">{{ item.processName || item.workProcessId }}</div>
            <div class="step-meta">
              <el-tag v-if="item.processCategory || ''" size="small" type="info">{{ item.processCategory || '' }}</el-tag>
              <span class="step-unit">单位: {{ item.unitOverride || item.defaultUnit || '-' }}</span>
            </div>
          </div>
          <div class="step-actions" v-if="canWrite">
            <el-button text size="small" :disabled="index === 0" @click="handleMoveUp(index)">
              <el-icon><Rank /></el-icon>上移
            </el-button>
            <el-button text size="small" :disabled="index === linkedProcesses.length - 1" @click="handleMoveDown(index)">
              <el-icon><Rank /></el-icon>下移
            </el-button>
            <el-button type="danger" text size="small" @click="handleRemove(item)">
              <el-icon><Delete /></el-icon>移除
            </el-button>
          </div>
        </div>

        <!-- 可添加的工序 -->
        <el-divider v-if="selectedProductId && canWrite">可添加的工序</el-divider>
        <div v-if="selectedProductId && canWrite" class="available-list">
          <div v-if="availableProcesses.length === 0" class="empty-hint">
            <el-text type="info" size="small">所有工序已关联，或尚未创建工序</el-text>
          </div>
          <div v-for="wp in availableProcesses" :key="wp.id" class="available-item">
            <div class="available-info">
              <span class="available-name">{{ wp.processName }}</span>
              <el-tag v-if="wp.processCategory" size="small" type="info" style="margin-left: 8px">{{ wp.processCategory }}</el-tag>
              <span class="available-unit">{{ wp.unit }}</span>
            </div>
            <el-button type="primary" text size="small" :icon="Plus" @click="handleAdd(wp)">添加</el-button>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.page-container { padding: 20px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; }
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.toolbar-right { display: flex; gap: 8px; }

.content-layout {
  display: flex;
  gap: 16px;
  margin-top: 16px;
}
.product-panel { width: 280px; flex-shrink: 0; }
.process-panel { flex: 1; }

.card-header { display: flex; justify-content: space-between; align-items: center; }
.product-info { padding: 8px 0; }

.linked-item {
  display: flex;
  align-items: center;
  padding: 12px;
  margin-bottom: 8px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fafafa;
  transition: background 0.2s;
}
.linked-item:hover { background: #f0f9ff; border-color: #409eff; }

.step-number {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #409eff;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 14px;
  flex-shrink: 0;
}
.step-info { flex: 1; margin-left: 12px; }
.step-name { font-weight: 600; font-size: 14px; color: #333; }
.step-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; }
.step-unit { font-size: 12px; color: #909399; }
.step-actions { display: flex; gap: 4px; flex-shrink: 0; }

.available-list { }
.available-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
}
.available-item:last-child { border-bottom: none; }
.available-info { display: flex; align-items: center; gap: 4px; }
.available-name { font-size: 14px; color: #333; }
.available-unit { font-size: 12px; color: #909399; margin-left: 8px; }

.empty-state { padding: 20px 0; }
.empty-hint { padding: 12px; text-align: center; }
</style>
