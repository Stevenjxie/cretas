<template>
  <div class="three-statements">
    <el-tabs v-model="activeTab" type="card" class="report-tabs">
      <el-tab-pane label="资产负债表" name="balance-sheet">
        <BalanceSheet v-if="activeTab === 'balance-sheet'" />
      </el-tab-pane>
      <el-tab-pane label="利润表" name="income-statement">
        <IncomeStatement v-if="activeTab === 'income-statement'" />
      </el-tab-pane>
      <el-tab-pane label="现金流量表" name="cash-flow">
        <CashFlow v-if="activeTab === 'cash-flow'" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import BalanceSheet from './BalanceSheet.vue';
import IncomeStatement from './IncomeStatement.vue';
import CashFlow from './CashFlow.vue';

const activeTab = ref('balance-sheet');
</script>

<style scoped>
.three-statements {
  padding: 16px;
}

.report-tabs :deep(.el-tabs__content) {
  padding: 0;
}
</style>
