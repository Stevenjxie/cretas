<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { formatDateTime } from '@/utils/dateFormat';
import type { TableRow } from '@/types/api';

/**
 * 在制品 (WIP) 批次列表 — Sprint 4 Wave 2 M-WIP-1.
 *
 * 显示 status = PRODUCING_RESERVED 的原材料批次, 即已被
 * 进行中的生产批次 (ProductionBatch.status IN_PROGRESS) 占用的物料。
 *
 * 状态转换由后端 ProcessingServiceImpl 维护:
 *  - startProduction → RESERVED/AVAILABLE → PRODUCING_RESERVED
 *  - completeProduction / cancelProduction → PRODUCING_RESERVED → AVAILABLE/RESERVED/DEPLETED
 */
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const tableData = ref<TableRow[]>([]);

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/material-batches/wip`);
    if (response.success && response.data) {
      tableData.value = Array.isArray(response.data) ? response.data : [];
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载在制品列表失败');
    }
  } catch (error) {
    console.error('加载在制品失败:', error);
  } finally {
    loading.value = false;
  }
}

function formatQuantity(row: TableRow): string {
  const qty = row.currentQuantity ?? row.remainingQuantity ?? 0;
  return `${qty} ${row.quantityUnit || ''}`.trim();
}
</script>

<template>
  <div class="wip-batches-page">
    <div class="page-header">
      <h2>在制品 (WIP)</h2>
      <div class="page-actions">
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
      </div>
    </div>

    <el-alert
      title="在制品说明"
      type="info"
      :closable="false"
      show-icon
      class="info-banner"
    >
      <template #default>
        本页显示当前已被「进行中」生产批次占用的原材料批次 (状态 = PRODUCING_RESERVED)。
        生产批次完成或取消后, 物料批次状态会自动恢复为 AVAILABLE / RESERVED / DEPLETED。
      </template>
    </el-alert>

    <el-table
      v-loading="loading"
      :data="tableData"
      stripe
      border
      empty-text="暂无在制品批次"
    >
      <el-table-column prop="batchNumber" label="批次号" min-width="160" />
      <el-table-column prop="factoryId" label="工厂" width="100" />
      <el-table-column
        label="物料类型"
        min-width="160"
      >
        <template #default="{ row }">
          {{ row.materialType?.name || row.materialTypeId || '-' }}
        </template>
      </el-table-column>
      <el-table-column label="剩余数量" min-width="120">
        <template #default="{ row }">
          {{ formatQuantity(row) }}
        </template>
      </el-table-column>
      <el-table-column prop="reservedQuantity" label="预留数量" min-width="120" />
      <el-table-column
        label="供应商"
        min-width="140"
      >
        <template #default="{ row }">
          {{ row.supplier?.name || row.supplierId || '-' }}
        </template>
      </el-table-column>
      <el-table-column
        v-if="canViewPrice"
        prop="unitPrice"
        label="单价"
        min-width="100"
      />
      <el-table-column
        label="入库日期"
        min-width="140"
      >
        <template #default="{ row }">
          {{ row.inboundDate || row.receiptDate || '-' }}
        </template>
      </el-table-column>
      <el-table-column
        label="过期日期"
        min-width="140"
      >
        <template #default="{ row }">
          {{ row.expiryDate || row.expireDate || '-' }}
        </template>
      </el-table-column>
      <el-table-column
        label="最后使用时间"
        min-width="180"
      >
        <template #default="{ row }">
          {{ formatDateTime(row.lastUsedAt) }}
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.wip-batches-page {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.info-banner {
  margin-bottom: 16px;
}
</style>
