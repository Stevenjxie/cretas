<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';

/**
 * C3: 半成品重量库存视图 (只读, 仅重量字段)
 *
 * 数据来源: GET /api/mobile/{factoryId}/semi-finished/inventory
 * 展示 semi_finished_inventories 全状态快照 (AVAILABLE / DEPLETED / RETURNED),
 * 按产品类型名称分组, 仅显示重量字段, 不含成本。
 *
 * 客户要求: "只做重量库存" — 仓管员只需知道有多少可用半成品, 不需要看成本。
 */

interface WipRow {
  intermediateBatchNo: string | null;
  sourceWorkProcessTaskId: number | null;
  processOrder: number | null;
  productTypeId: string | null;
  producedQuantity: number;
  consumedQuantity: number;
  availableQuantity: number;
  unit: string | null;
  status: string;
  // C3 扩展字段
  productTypeName: string | null;
  batchId: number | null;
}

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const tableData = ref<WipRow[]>([]);

// 筛选: 产品类型 / 状态 (实时按当前数据里实际存在的值生成选项, 非固定枚举)
const filterProductType = ref('');
const filterStatus = ref('');

const productTypeOptions = computed(() => {
  const set = new Set(tableData.value.map(r => r.productTypeName || r.productTypeId).filter((v): v is string => !!v));
  return Array.from(set).sort();
});
const statusOptions = computed(() => {
  const set = new Set(tableData.value.map(r => r.status).filter((v): v is string => !!v));
  return Array.from(set).map(v => ({ value: v, label: statusLabel(v) }));
});

const filteredData = computed(() => tableData.value.filter(row => {
  if (filterProductType.value && (row.productTypeName || row.productTypeId) !== filterProductType.value) return false;
  if (filterStatus.value && row.status !== filterStatus.value) return false;
  return true;
}));

function handleFilterReset() {
  filterProductType.value = '';
  filterStatus.value = '';
}

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/semi-finished/inventory`);
    if (response.success && response.data) {
      tableData.value = Array.isArray(response.data) ? response.data : [];
    } else if (response.success === false) {
      ElMessage({ message: response.message || '加载半成品库存失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (error) {
    console.error('加载半成品库存失败:', error);
    ElMessage({ message: '加载半成品库存失败，请稍后重试', type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'AVAILABLE': return '可用';
    case 'DEPLETED':  return '已耗尽';
    case 'RETURNED':  return '已退回';
    default:          return status;
  }
}

function statusType(status: string): 'success' | 'info' | 'warning' | 'danger' | '' {
  switch (status) {
    case 'AVAILABLE': return 'success';
    case 'DEPLETED':  return 'info';
    case 'RETURNED':  return 'warning';
    default:          return '';
  }
}

function formatKg(val: number | null | undefined): string {
  if (val == null) return '-';
  return `${val} kg`;
}
</script>

<template>
  <div class="semi-finished-inventory-page">
    <div class="page-header">
      <h2>半成品重量库存</h2>
      <div class="page-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
      </div>
    </div>

    <!-- 筛选: 按当前数据里实际存在的 产品类型/状态 生成选项, 非固定枚举 -->
    <div class="filter-bar">
      <el-select v-model="filterProductType" placeholder="全部产品类型" clearable style="width: 180px">
        <el-option v-for="pt in productTypeOptions" :key="pt" :label="pt" :value="pt" />
      </el-select>
      <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 140px">
        <el-option v-for="opt in statusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-button :icon="Refresh" @click="handleFilterReset">重置</el-button>
    </div>

    <el-table
      :data="filteredData"
      v-loading="loading"
      border
      stripe
      row-key="intermediateBatchNo"
      default-expand-all
    >
      <!-- 产品类型 -->
      <el-table-column prop="productTypeName" label="产品类型" min-width="140">
        <template #default="{ row }">
          <span>{{ row.productTypeName || row.productTypeId || '-' }}</span>
        </template>
      </el-table-column>

      <!-- 批次 ID -->
      <el-table-column prop="batchId" label="批次" width="90" align="center">
        <template #default="{ row }">
          <span>{{ row.batchId ?? '-' }}</span>
        </template>
      </el-table-column>

      <!-- 中间批次号 -->
      <el-table-column prop="intermediateBatchNo" label="中间批次号" min-width="160">
        <template #default="{ row }">
          <span class="mono">{{ row.intermediateBatchNo || '-' }}</span>
        </template>
      </el-table-column>

      <!-- 工序顺序 -->
      <el-table-column prop="processOrder" label="工序" width="70" align="center">
        <template #default="{ row }">
          <span>{{ row.processOrder ?? '-' }}</span>
        </template>
      </el-table-column>

      <!-- 已产出 -->
      <el-table-column prop="producedQuantity" label="产出量" width="110" align="right">
        <template #default="{ row }">
          <span class="qty-cell">{{ formatKg(row.producedQuantity) }}</span>
        </template>
      </el-table-column>

      <!-- 已消耗 -->
      <el-table-column prop="consumedQuantity" label="已消耗" width="110" align="right">
        <template #default="{ row }">
          <span class="qty-cell consumed">{{ formatKg(row.consumedQuantity) }}</span>
        </template>
      </el-table-column>

      <!-- 可用量 (重点列) -->
      <el-table-column prop="availableQuantity" label="可用量" width="120" align="right">
        <template #default="{ row }">
          <span
            class="qty-cell available"
            :class="{ 'zero': row.availableQuantity === 0 }"
          >{{ formatKg(row.availableQuantity) }}</span>
        </template>
      </el-table-column>

      <!-- 状态 -->
      <el-table-column prop="status" label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!loading && tableData.length === 0" class="empty-tip">
      暂无半成品库存记录
    </div>
    <div v-else-if="!loading && filteredData.length === 0" class="empty-tip">
      没有符合筛选条件的记录
    </div>
  </div>
</template>

<style scoped>
.semi-finished-inventory-page {
  padding: 20px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
}

.filter-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.mono {
  font-family: 'Courier New', monospace;
  font-size: 12px;
}

.qty-cell {
  font-weight: 500;
}

.qty-cell.available {
  color: #409eff;
  font-weight: 600;
}

.qty-cell.available.zero {
  color: #909399;
  font-weight: 400;
}

.qty-cell.consumed {
  color: #909399;
}

.empty-tip {
  text-align: center;
  color: #909399;
  padding: 40px 0;
  font-size: 14px;
}
</style>
