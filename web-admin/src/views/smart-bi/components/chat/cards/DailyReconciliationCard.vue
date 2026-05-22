<template>
  <div class="daily-reconciliation-card">
    <h4>日清日结</h4>
    <div class="summary">
      <span class="stat">日期: {{ data.date ?? '--' }}</span>
      <span class="divider">|</span>
      <span class="stat">共 {{ data.total_ingredients ?? 0 }} 种食材</span>
      <span class="divider">|</span>
      <span class="stat" :class="withinCount === total ? 'ok' : 'warn'">
        在容差内: {{ withinCount }} / {{ total }}
      </span>
      <span class="divider">|</span>
      <span class="stat">容差: ±{{ data.tolerance_pct ?? 5 }}%</span>
    </div>
    <el-table v-if="rows.length" :data="rows" size="small" stripe>
      <el-table-column prop="ingredient" label="食材" width="120" />
      <el-table-column prop="expected_closing" label="预期期末" align="right" width="90" />
      <el-table-column prop="actual_closing" label="实际盘点" align="right" width="90" />
      <el-table-column prop="variance" label="差异" align="right" width="80">
        <template #default="{ row }">
          <span :class="row.variance < 0 ? 'loss' : row.variance > 0 ? 'surplus' : 'ok'">
            {{ row.variance >= 0 ? '+' : '' }}{{ row.variance }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="variance_pct" label="差异%" align="right" width="70">
        <template #default="{ row }">{{ row.variance_pct }}%</template>
      </el-table-column>
      <el-table-column label="状态" align="center" width="80">
        <template #default="{ row }">
          <el-tag
            :type="row.severity === 'HIGH' ? 'danger' : row.severity === 'MEDIUM' ? 'warning' : 'success'"
            size="small">
            {{ row.severity === 'OK' ? '正常' : row.severity }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="alerts.length" class="alerts">
      <p v-for="(a, i) in alerts" :key="i" class="alert-item">{{ a }}</p>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue';
const props = defineProps<{ data: Record<string, unknown> }>();
const rows = computed(() => (props.data?.reconciliation as Array<Record<string, unknown>>) ?? []);
const alerts = computed(() => (props.data?.alerts as string[]) ?? []);
const total = computed(() => Number(props.data?.total_ingredients ?? 0));
const withinCount = computed(() => Number(props.data?.within_tolerance_count ?? 0));
</script>
<style scoped>
.daily-reconciliation-card { padding: 12px; }
.summary { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 12px; font-size: 13px; color: #606266; }
.stat { font-weight: 500; }
.divider { color: #dcdfe6; }
.ok { color: #67c23a; }
.warn { color: #e6a23c; }
.loss { color: #f56c6c; font-weight: bold; }
.surplus { color: #e6a23c; }
.alerts { margin-top: 12px; padding: 8px 12px; background: #fff5f5; border-radius: 4px; }
.alert-item { margin: 4px 0; font-size: 12px; color: #c0392b; }
</style>
