<script setup lang="ts">
import StoreDetailDrawer from '../components/StoreDetailDrawer.vue';
import type { StoreOrder } from '../types';
import { useLogisticsDemoState } from '../useLogisticsDemoState';

const state = useLogisticsDemoState();

function openStore(store: StoreOrder): void {
  state.selectStore(store.id);
}
</script>

<template>
  <main class="support-page">
    <header class="page-header">
      <div>
        <h1>门店与订单</h1>
        <p>一行对应一家门店，点击门店可查看配送明细。</p>
      </div>
      <div class="header-actions">
        <el-button>下载导入模板</el-button>
        <el-button type="primary" @click="state.importOrders">导入订单</el-button>
      </div>
    </header>

    <el-card shadow="never">
      <el-table :data="state.stores.value" stripe row-key="id" class="orders-table" @row-click="openStore">
        <el-table-column prop="id" label="门店编码" min-width="110" />
        <el-table-column prop="name" label="门店名称" min-width="160" />
        <el-table-column prop="address" label="配送地址" min-width="240" show-overflow-tooltip />
        <el-table-column prop="pieces" label="件数" min-width="80" />
        <el-table-column prop="boxes" label="箱数" min-width="80" />
        <el-table-column label="重量" min-width="100"><template #default="{ row }">{{ row.weightKg }} kg</template></el-table-column>
        <el-table-column label="体积" min-width="100"><template #default="{ row }">{{ row.volumeCbm }} m³</template></el-table-column>
        <el-table-column label="定位状态" min-width="110"><template #default="{ row }"><el-tag type="success" effect="plain">已定位</el-tag></template></el-table-column>
      </el-table>
    </el-card>

    <StoreDetailDrawer :stores="state.stores.value" :selected-store-id="state.selectedStoreId.value" @select-store="state.selectStore" />
  </main>
</template>

<style scoped lang="scss">
.support-page { display: grid; gap: 20px; max-width: 1440px; min-height: 100%; padding: 24px; margin: 0 auto; background: #f8fafc; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.page-header h1 { margin: 0; color: #101828; font-size: 24px; }.page-header p { margin: 8px 0 0; color: #667085; }.header-actions { display: flex; flex-wrap: wrap; gap: 10px; }.orders-table :deep(.el-table__row) { cursor: pointer; }
@media (max-width: 720px) { .support-page { padding: 16px; }.page-header { flex-direction: column; }.header-actions { width: 100%; } }
</style>
