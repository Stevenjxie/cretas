<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { handleCatchError } from '@/utils/errorToast';
import { fetchProductRank, type ProductRankRow } from '@/api/salesPreset';

const rows = ref<ProductRankRow[]>([]);
const loading = ref(false);
const dateRange = ref<[string, string] | null>(null);
const limit = ref(20);

function money(value: number | null | undefined): string {
  return `¥ ${Number(value ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function averageAmount(row: ProductRankRow, key: 'taxableAmount' | 'totalAmountWithTax'): number {
  const qty = Number(row.totalQty || 0);
  if (qty <= 0) return 0;
  return Number(row[key] || 0) / qty;
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const [start, end] = dateRange.value ?? [undefined, undefined];
    const res = await fetchProductRank(start, end, limit.value);
    rows.value = res?.data?.rank ?? [];
  } catch (e) {
    // UX polish (2026-05-20): interceptor toast already covers 4xx/5xx.
    handleCatchError(e, '加载失败,请检查网络');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="product-rank">
    <div class="header">
      <h2>产品销售排行</h2>
      <div class="filter">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
        />
        <el-input-number v-model="limit" :min="1" :max="100" controls-position="right" />
        <el-button type="primary" @click="load">查询</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="rank" label="排名" width="80" />
      <el-table-column prop="productName" label="产品" min-width="200">
        <template #default="{ row }">{{ row.productName ?? row.productTypeId }}</template>
      </el-table-column>
      <el-table-column label="总数量" width="140">
        <template #default="{ row }">{{ row.totalQty.toLocaleString() }} {{ row.unit ?? '' }}</template>
      </el-table-column>
      <el-table-column label="未税单价" width="140">
        <template #default="{ row }">{{ money(averageAmount(row, 'taxableAmount')) }}</template>
      </el-table-column>
      <el-table-column label="含税单价" width="140">
        <template #default="{ row }">{{ money(averageAmount(row, 'totalAmountWithTax')) }}</template>
      </el-table-column>
      <el-table-column label="含税销售额" width="160">
        <template #default="{ row }">{{ money(row.totalAmountWithTax) }}</template>
      </el-table-column>
      <el-table-column label="未税金额" width="160">
        <template #default="{ row }">{{ money(row.taxableAmount) }}</template>
      </el-table-column>
      <el-table-column label="税额" width="140">
        <template #default="{ row }">{{ money(row.taxAmount) }}</template>
      </el-table-column>
      <el-table-column prop="orderCount" label="订单数" width="100" />
    </el-table>
  </div>
</template>

<style lang="scss" scoped>
.product-rank {
  padding: 16px;
  .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; h2 { margin: 0; } .filter { display: flex; gap: 12px; } }
}
</style>
