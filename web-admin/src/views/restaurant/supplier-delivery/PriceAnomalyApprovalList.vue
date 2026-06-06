<template>
  <div class="page-wrapper">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="page-title">价格异常待审批</span>
          <el-button :icon="Refresh" @click="loadData">刷新</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="tableData" border>
        <el-table-column prop="noteNumber" label="送货单号" min-width="140">
          <template #default="{ row }">
            {{ row.noteNumber || row.id }}
          </template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="140" />
        <el-table-column prop="deliveryDate" label="送货日期" width="120" />
        <el-table-column label="异常行数" width="100" align="center">
          <template #default="{ row }">
            {{ countAnomalies(row) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row)">审批</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { useFactoryId } from '@/composables/useFactoryId';
import {
  getPendingPriceAnomalyApprovals,
  type SupplierDeliveryNoteDto,
} from '@/api/restaurant/supplierDeliveryNote';
import { handleCatchError } from '@/utils/errorToast';

const router = useRouter();
const factoryId = useFactoryId();
const loading = ref(false);
const tableData = ref<SupplierDeliveryNoteDto[]>([]);

function countAnomalies(row: SupplierDeliveryNoteDto): number {
  return (row.lines || []).filter((line) => line.priceAnomalyFlag).length;
}

async function loadData() {
  loading.value = true;
  try {
    const resp = await getPendingPriceAnomalyApprovals(factoryId.value, { page: 0, size: 50 });
    if (resp.success && resp.data) {
      tableData.value = resp.data.content || [];
    }
  } catch (e) {
    handleCatchError(e, '加载待审批列表失败');
  } finally {
    loading.value = false;
  }
}

function goDetail(row: SupplierDeliveryNoteDto) {
  router.push({ name: 'SupplierDeliveryNoteDetail', params: { id: row.id } });
}

onMounted(loadData);
</script>

<style scoped lang="scss">
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
</style>
