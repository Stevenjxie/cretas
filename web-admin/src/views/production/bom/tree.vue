<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Search, Refresh } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';
import { displayUnit } from '@/utils/unitPricing';

/**
 * 多级 BOM 树展开视图 — Sprint 4 Wave 2 M-MATTREE-1.
 *
 * 调用 GET /api/mobile/{factoryId}/bom/tree/{productTypeId}?quantity=N
 * 后端递归展开成品 BOM 到原料叶子, 附加库存短缺信息 + 循环检测。
 *
 * UI: Element Plus tree-table (el-table + row-key + tree-props children),
 * 叶子节点显示 库存可用量 / 短缺数量, 半成品节点显示 N children。
 */
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const productTypeId = ref('');
const quantity = ref<number>(1);
const loading = ref(false);
const treeData = ref<any[]>([]);
const summary = ref<{
  maxDepth: number;
  leafCount: number;
  shortfallLeafCount: number;
  cycleDetected: boolean;
  cycleTypeIds: string[];
} | null>(null);

const productTypes = ref<TableRow[]>([]);

onMounted(() => {
  loadProductTypes();
});

async function loadProductTypes() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/product-types`, { params: { size: 200 } });
    if (res.success && res.data) {
      productTypes.value = res.data.content || res.data || [];
    }
  } catch {
    // silent
  }
}

async function loadTree() {
  if (!factoryId.value || !productTypeId.value) {
    ElMessage.warning('请选择产品');
    return;
  }
  if (!quantity.value || quantity.value <= 0) {
    ElMessage.warning('请输入大于 0 的生产数量');
    return;
  }
  loading.value = true;
  try {
    const response = await get(
      `/${factoryId.value}/bom/tree/${productTypeId.value}`,
      { params: { quantity: quantity.value } }
    );
    if (response.success && response.data) {
      const r = response.data;
      summary.value = {
        maxDepth: r.maxDepth ?? 0,
        leafCount: r.leafCount ?? 0,
        shortfallLeafCount: r.shortfallLeafCount ?? 0,
        cycleDetected: !!r.cycleDetected,
        cycleTypeIds: r.cycleTypeIds || []
      };
      // 把根节点放进 array, el-table tree-props 会按 children 字段递归展示
      treeData.value = r.root ? [r.root] : [];
    } else if (response.success === false) {
      ElMessage.error(response.message || '展开 BOM 树失败');
    }
  } catch (error) {
    console.error('展开 BOM 树失败:', error);
  } finally {
    loading.value = false;
  }
}

function handleReset() {
  productTypeId.value = '';
  quantity.value = 1;
  treeData.value = [];
  summary.value = null;
}

function getRowClass({ row }: { row: any }): string {
  if (row.cycleDetected) return 'row-cycle';
  if (row.leaf && row.shortfallQuantity && parseFloat(row.shortfallQuantity) > 0) {
    return 'row-shortfall';
  }
  return '';
}

function formatNumber(v: any): string {
  if (v === null || v === undefined) return '-';
  const n = parseFloat(v);
  if (Number.isNaN(n)) return '-';
  return n.toFixed(4).replace(/\.?0+$/, '');
}
</script>

<template>
  <div class="bom-tree-page">
    <div class="page-header">
      <h2>多级 BOM 展开</h2>
    </div>

    <el-card class="control-card" shadow="never">
      <div class="control-row">
        <el-select
          v-model="productTypeId"
          placeholder="选择产品 (root)"
          filterable
          style="width: 280px"
        >
          <el-option
            v-for="p in productTypes"
            :key="p.id"
            :label="p.name || p.productName || p.id"
            :value="p.id"
          />
        </el-select>
        <el-input-number
          v-model="quantity"
          :min="0.0001"
          :precision="4"
          :step="1"
          placeholder="生产数量"
          style="width: 180px"
        />
        <el-button type="primary" :icon="Search" :loading="loading" @click="loadTree">展开</el-button>
        <el-button :icon="Refresh" @click="handleReset">重置</el-button>
      </div>
    </el-card>

    <el-row v-if="summary" :gutter="16" class="summary-row">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="card-label">最大深度</div>
          <div class="card-value">{{ summary.maxDepth }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="card-label">叶子节点 (原料种类)</div>
          <div class="card-value">{{ summary.leafCount }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" :class="summary.shortfallLeafCount > 0 ? 'card-warn' : ''">
          <div class="card-label">短缺叶子数</div>
          <div class="card-value">{{ summary.shortfallLeafCount }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" :class="summary.cycleDetected ? 'card-danger' : ''">
          <div class="card-label">循环检测</div>
          <div class="card-value">
            <el-tag v-if="summary.cycleDetected" type="danger">命中</el-tag>
            <span v-else>正常</span>
          </div>
          <div v-if="summary.cycleDetected" class="cycle-list">
            涉及: {{ summary.cycleTypeIds.join(', ') }}
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-table
      v-loading="loading"
      :data="treeData"
      row-key="typeId"
      :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
      :row-class-name="getRowClass"
      default-expand-all
      border
      stripe
      class="tree-table"
      empty-text="选择产品后点击「展开」查看 BOM 树"
    >
      <el-table-column prop="name" label="名称" min-width="220">
        <template #default="{ row }">
          <span :style="{ paddingLeft: (row.level * 4) + 'px' }">
            <el-tag v-if="row.level === 0" size="small" type="success" effect="plain">成品</el-tag>
            <el-tag v-else-if="!row.leaf" size="small" type="info" effect="plain">半成品</el-tag>
            <el-tag v-else size="small" type="primary" effect="plain">原料</el-tag>
            {{ row.name || row.typeId }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="typeId" label="Type ID" width="200" show-overflow-tooltip />
      <el-table-column prop="level" label="层级" width="70" align="center" />
      <el-table-column label="需求量" min-width="120">
        <template #default="{ row }">
          {{ formatNumber(row.requiredQuantity) }} {{ displayUnit(row.unit) }}
        </template>
      </el-table-column>
      <el-table-column label="损耗率 %" width="100" align="right">
        <template #default="{ row }">
          {{ row.wastageRate !== null && row.wastageRate !== undefined ? formatNumber(row.wastageRate) : '-' }}
        </template>
      </el-table-column>
      <el-table-column label="可用库存" min-width="120">
        <template #default="{ row }">
          <span v-if="row.leaf">{{ formatNumber(row.availableQuantity) }}</span>
          <span v-else style="color: #909399">—</span>
        </template>
      </el-table-column>
      <el-table-column label="短缺" min-width="120">
        <template #default="{ row }">
          <span v-if="row.leaf && row.shortfallQuantity && parseFloat(row.shortfallQuantity) > 0"
                style="color: #f56c6c; font-weight: 600">
            {{ formatNumber(row.shortfallQuantity) }}
          </span>
          <span v-else style="color: #909399">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.cycleDetected" type="danger" size="small">循环切断</el-tag>
          <el-tag v-else-if="row.leaf && row.shortfallQuantity && parseFloat(row.shortfallQuantity) > 0"
                  type="warning" size="small">短缺</el-tag>
          <el-tag v-else-if="row.leaf" type="success" size="small">充足</el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.bom-tree-page {
  padding: 20px;
}

.page-header h2 {
  margin: 0 0 16px;
  font-size: 20px;
  font-weight: 600;
}

.control-card {
  margin-bottom: 16px;
}

.control-row {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}

.summary-row {
  margin-bottom: 16px;
}

.summary-row .card-label {
  font-size: 14px;
  color: #909399;
  margin-bottom: 8px;
}

.summary-row .card-value {
  font-size: 26px;
  font-weight: 600;
}

.summary-row .card-warn .card-value { color: #e6a23c; }
.summary-row .card-danger .card-value { color: #f56c6c; }

.cycle-list {
  font-size: 12px;
  color: #f56c6c;
  margin-top: 6px;
  word-break: break-all;
}

.tree-table :deep(.row-shortfall) {
  background-color: #fef0f0 !important;
}

.tree-table :deep(.row-cycle) {
  background-color: #fde2e2 !important;
}
</style>
