<!--
  LogsList.vue — 价格策略应用日志 (审计 audit).

  Per spec §5: 每次 PricingEngine.calculate() 写 1 行, finance 月度审计 cost-violation SO.
  支持按 business_entity_id (SO line ID) + 日期范围过滤.
-->
<template>
  <div class="logs-list">
    <div class="filter-bar">
      <el-input
        v-model="filterEntityId"
        placeholder="按业务实体 ID 过滤 (SO line ID)"
        clearable
        style="width: 280px"
      />
      <el-button type="primary" @click="loadLogs">查询</el-button>
      <el-button @click="onClearFilter">清空过滤</el-button>
    </div>

    <el-table :data="logs" v-loading="loading" border size="small" style="margin-top: 12px">
      <el-table-column prop="appliedAt" label="应用时间" width="160">
        <template #default="{ row }">
          <span>{{ formatDate(row.appliedAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="businessEntityType" label="实体类型" width="100" />
      <el-table-column prop="businessEntityId" label="实体 ID" width="180" show-overflow-tooltip />
      <el-table-column label="原价" width="100">
        <template #default="{ row }">
          ¥{{ Number(row.originalPrice).toFixed(2) }}
        </template>
      </el-table-column>
      <el-table-column label="最终价" width="100">
        <template #default="{ row }">
          <span class="final-price">¥{{ Number(row.finalPrice).toFixed(2) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="折扣" width="100">
        <template #default="{ row }">
          <span class="discount">- ¥{{ Number(row.discount).toFixed(2) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="应用策略" min-width="240">
        <template #default="{ row }">
          <div v-if="!row.appliedStrategies || row.appliedStrategies.length === 0" class="empty-strategies">
            (未应用任何策略)
          </div>
          <div v-else class="applied-tags">
            <el-tag
              v-for="(s, idx) in row.appliedStrategies"
              :key="idx"
              size="small"
              :type="tagType(String(s.type || ''))"
              style="margin-right: 4px"
            >
              {{ s.strategyCode || s.strategyId }} ({{ s.type }})
            </el-tag>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="totalElements"
        :page-sizes="[10, 20, 50, 100]"
        background
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="loadLogs"
        @size-change="loadLogs"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { listLogs, type PricingApplicationLog } from '@/api/pricingStrategyApi';

const props = defineProps<{
  factoryId: string;
}>();

const logs = ref<PricingApplicationLog[]>([]);
const loading = ref(false);
const filterEntityId = ref('');
const currentPage = ref(1);
const pageSize = ref(20);
const totalElements = ref(0);

async function loadLogs() {
  if (!props.factoryId) return;
  loading.value = true;
  try {
    const page = await listLogs(props.factoryId, {
      businessEntityId: filterEntityId.value || undefined,
      page: currentPage.value - 1,
      size: pageSize.value,
    });
    logs.value = page.content;
    totalElements.value = page.totalElements;
  } catch (e) {
    console.error('Load logs failed:', e);
  } finally {
    loading.value = false;
  }
}

function onClearFilter() {
  filterEntityId.value = '';
  currentPage.value = 1;
  loadLogs();
}

function formatDate(s: string | null | undefined): string {
  if (!s) return '-';
  return s.replace('T', ' ').slice(0, 19);
}

function tagType(type: string): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  const map: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'primary'> = {
    TIERED: 'primary',
    PROMOTION: 'warning',
    MEMBER: 'success',
    BUNDLE: 'info',
    CYCLE: 'danger',
  };
  return map[type] || 'info';
}

watch(() => props.factoryId, () => {
  if (props.factoryId) {
    currentPage.value = 1;
    loadLogs();
  }
});

onMounted(() => {
  if (props.factoryId) loadLogs();
});
</script>

<style scoped>
.logs-list {
  display: flex;
  flex-direction: column;
}
.filter-bar {
  display: flex;
  gap: 8px;
  align-items: center;
}
.final-price {
  color: var(--el-color-success);
  font-weight: 600;
}
.discount {
  color: var(--el-color-warning);
}
.empty-strategies {
  color: var(--el-text-color-secondary);
  font-style: italic;
  font-size: 12px;
}
.applied-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
