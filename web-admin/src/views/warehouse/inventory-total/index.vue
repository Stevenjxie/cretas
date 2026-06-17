<script setup lang="ts">
/**
 * 总库存查询 (工厂级原料总库存, 按物料聚合, 跨所有仓库)
 *
 * F006 六膳门真实客户需求。区别于「分仓库存查询」(/inventory/by-warehouse, 批次级 + 按单仓):
 * 本页是 工厂级 + 按物料聚合 的只读视图 —— 每个原料类型一行, 把该物料在所有仓库的
 * 所有在库批次的当前剩余量 / 价值 汇总。
 *
 * 后端: GET /{factoryId}/material-batches/stock-summary
 *   - 仅统计在库批次 (剩余量 > 0 且 status 非 耗尽/用完/过期/报废/不良品)
 *   - totalQuantity = SUM(receiptQuantity - usedQuantity - reservedQuantity)
 *   - totalValue / avgUnitPrice 受 RBAC 价格可见性约束 (canViewPrice)
 *
 * 只读页面 (无新建/编辑/删除)。
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Search, Refresh } from '@element-plus/icons-vue';
import { formatAmount, fmtQty } from '@/utils/tableFormatters';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
// 价格可见性 RBAC: 无 price-view 角色 (仓管/质检/操作员) 不显示价值/均价列。
const canViewPrice = computed(() => permissionStore.canViewPrice);

interface MaterialStockSummaryRow {
  materialTypeId: string;
  materialName?: string;
  materialCode?: string;
  materialCategory?: string;
  unit?: string;
  totalQuantity?: number;
  totalValue?: number;
  batchCount?: number;
  warehouseCount?: number;
  avgUnitPrice?: number | null;
}

const loading = ref(false);
const loadError = ref('');
const tableData = ref<MaterialStockSummaryRow[]>([]);
const searchKeyword = ref('');
const filterCategory = ref('');

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  loadError.value = '';
  try {
    const resp = await get<MaterialStockSummaryRow[]>(
      `/${factoryId.value}/material-batches/stock-summary`
    );
    if (resp.success && Array.isArray(resp.data)) {
      tableData.value = resp.data;
    } else if (resp.success === false) {
      // 防呆 4 位一体: 原样显示后端 message, 不吞错。
      loadError.value = resp.message || '加载总库存失败';
      ElMessage.error(loadError.value);
      tableData.value = [];
    }
  } catch (e: unknown) {
    const msg =
      typeof e === 'object' && e !== null && 'message' in e
        ? String((e as { message?: unknown }).message ?? '')
        : '';
    loadError.value = msg || '加载总库存失败, 请稍后重试';
    ElMessage.error(loadError.value);
    tableData.value = [];
    console.error('加载总库存失败:', e);
  } finally {
    loading.value = false;
  }
}

// ==================== 前端过滤 ====================

// 类别下拉选项 (从已加载数据动态去重)
const categoryOptions = computed<string[]>(() => {
  const set = new Set<string>();
  for (const r of tableData.value) {
    if (r.materialCategory) set.add(r.materialCategory);
  }
  return Array.from(set).sort();
});

const filteredData = computed<MaterialStockSummaryRow[]>(() => {
  const kw = searchKeyword.value.trim().toLowerCase();
  const cat = filterCategory.value;
  return tableData.value.filter((r) => {
    if (cat && r.materialCategory !== cat) return false;
    if (!kw) return true;
    const name = (r.materialName || '').toLowerCase();
    const code = (r.materialCode || '').toLowerCase();
    return name.includes(kw) || code.includes(kw);
  });
});

// ==================== 顶部汇总 ====================

const totalMaterialKinds = computed(() => filteredData.value.length);
const totalValueSum = computed(() => {
  if (!canViewPrice.value) return null;
  return filteredData.value.reduce((acc, r) => acc + (Number(r.totalValue) || 0), 0);
});

function handleRefresh() {
  searchKeyword.value = '';
  filterCategory.value = '';
  loadData();
}

onMounted(() => {
  loadData();
});
</script>

<template>
  <div class="page-wrapper">
    <el-card class="filter-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="page-title">总库存查询</span>
          <span class="hint">工厂级原料总库存 — 按物料聚合, 跨所有仓库 (仅在库批次)</span>
        </div>
      </template>
      <div class="filter-row">
        <div class="filter-item">
          <label>搜索:</label>
          <el-input
            v-model="searchKeyword"
            placeholder="原料名称 / 编码"
            :prefix-icon="Search"
            clearable
            style="width: 240px"
          />
        </div>

        <div class="filter-item">
          <label>类别:</label>
          <el-select
            v-model="filterCategory"
            placeholder="全部类别"
            clearable
            style="width: 180px"
          >
            <el-option
              v-for="cat in categoryOptions"
              :key="cat"
              :label="cat"
              :value="cat"
            />
          </el-select>
        </div>

        <el-button :icon="Refresh" @click="handleRefresh">刷新</el-button>
      </div>
    </el-card>

    <el-card class="summary-card" shadow="never">
      <div class="summary-row">
        <div class="summary-item">
          <div class="summary-label">原料种类</div>
          <div class="summary-value strong-blue">{{ totalMaterialKinds }}</div>
        </div>
        <div class="summary-item" v-if="canViewPrice">
          <div class="summary-label">库存总价值</div>
          <div class="summary-value strong-green">{{ formatAmount(totalValueSum) }}</div>
        </div>
      </div>
    </el-card>

    <el-card class="result-card" shadow="never">
      <el-table
        :data="filteredData"
        v-loading="loading"
        empty-text="暂无库存数据"
        stripe
        border
        style="width: 100%"
        :default-sort="{ prop: 'materialName', order: 'ascending' }"
      >
        <el-table-column prop="materialCode" label="原料编码" width="130" show-overflow-tooltip />
        <el-table-column prop="materialName" label="原料名称" min-width="160" show-overflow-tooltip sortable />
        <el-table-column prop="materialCategory" label="类别" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.materialCategory || '-' }}
          </template>
        </el-table-column>
        <el-table-column
          prop="totalQuantity"
          label="当前总库存"
          width="140"
          align="right"
          sortable
        >
          <template #default="{ row }">
            <strong>{{ fmtQty(row.totalQuantity) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="单位" width="90" align="center">
          <template #default="{ row }">
            {{ row.unit || '-' }}
          </template>
        </el-table-column>
        <el-table-column
          v-if="canViewPrice"
          label="移动均价"
          width="130"
          align="right"
        >
          <template #default="{ row }">
            {{ row.avgUnitPrice == null ? '-' : formatAmount(row.avgUnitPrice) }}
          </template>
        </el-table-column>
        <el-table-column
          v-if="canViewPrice"
          prop="totalValue"
          label="库存总价值"
          width="150"
          align="right"
          sortable
        >
          <template #default="{ row }">
            {{ formatAmount(row.totalValue) }}
          </template>
        </el-table-column>
        <el-table-column label="批次数" width="100" align="right">
          <template #default="{ row }">
            {{ row.batchCount ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column label="仓库数" width="100" align="right">
          <template #default="{ row }">
            {{ row.warehouseCount ?? 0 }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-header {
  display: flex;
  align-items: baseline;
  gap: 12px;

  .page-title {
    font-size: 16px;
    font-weight: 600;
    color: #303133;
  }

  .hint {
    font-size: 13px;
    color: #909399;
  }
}

.filter-row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  align-items: center;
}

.filter-item {
  display: flex;
  gap: 8px;
  align-items: center;

  label {
    font-size: 13px;
    color: #606266;
    white-space: nowrap;
  }
}

.summary-row {
  display: flex;
  gap: 48px;
  flex-wrap: wrap;
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.summary-label {
  font-size: 13px;
  color: #909399;
}

.summary-value {
  font-size: 22px;
  font-weight: 600;
  color: #303133;

  &.strong-blue {
    color: #409eff;
  }

  &.strong-green {
    color: #67c23a;
  }
}
</style>
