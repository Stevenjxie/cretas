<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import FlywheelHeader from './components/FlywheelHeader.vue';
import { useFlywheelDomain } from './composables/useFlywheelDomain';
import { flywheelApi, type FlywheelMiss } from '@/api/smartbi/ai-flywheel';

const { domain } = useFlywheelDomain();
const loading = ref(false);
const misses = ref<FlywheelMiss[]>([]);
const statusFilter = ref<string>('all');
const error = ref('');

const STATUS_OPTIONS: Array<{ value: FlywheelMiss['status']; label: string; type: 'info' | 'warning' | 'success' | 'danger' }> = [
  { value: 'open', label: '待处理', type: 'danger' },
  { value: 'triaged', label: '已分诊', type: 'warning' },
  { value: 'resolved', label: '已解决', type: 'success' },
  { value: 'wontfix', label: '不处理', type: 'info' },
];

const filtered = computed(() =>
  statusFilter.value === 'all' ? misses.value : misses.value.filter((m) => m.status === statusFilter.value),
);

async function load() {
  loading.value = true;
  error.value = '';
  try {
    misses.value = await flywheelApi.misses(domain.value);
  } catch (e) {
    // 禁止降级处理: 失败就明确失败, misses 保持空数组 (不渲染假记录), 常驻错误横幅 + sticky toast。
    const msg = e instanceof Error ? e.message : String(e);
    error.value = msg;
    misses.value = [];
    ElMessage({ message: `加载 Miss 复盘失败: ${msg}`, type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

async function onStatusChange(row: FlywheelMiss, status: FlywheelMiss['status']) {
  const prev = row.status;
  row.status = status;
  try {
    await flywheelApi.updateMissStatus(row.id, domain.value, status);
    ElMessage.success(`「${row.query_text}」已标记为「${statusLabel(status)}」`);
  } catch (e) {
    row.status = prev;
    const msg = e instanceof Error ? e.message : String(e);
    ElMessage({ message: `更新状态失败: ${msg}`, type: 'error', duration: 0, showClose: true });
  }
}

function statusLabel(s: string): string {
  return STATUS_OPTIONS.find((o) => o.value === s)?.label || s;
}
function statusType(s: string): 'info' | 'warning' | 'success' | 'danger' {
  return STATUS_OPTIONS.find((o) => o.value === s)?.type || 'info';
}
function fmtTime(ts: string): string {
  return new Date(ts).toLocaleString('zh-CN');
}

onMounted(load);
watch(domain, load);
</script>

<template>
  <div class="page-container">
    <FlywheelHeader v-model:domain="domain" />

    <el-alert
      v-if="error"
      :title="`后端接口不可用: ${error}`"
      type="error"
      :closable="false"
      show-icon
      class="load-error-alert"
      data-test="flywheel-misses-error"
    />

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="filter-area">
            <el-select v-model="statusFilter" style="width: 140px">
              <el-option value="all" label="全部状态" />
              <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :value="o.value" :label="o.label" />
            </el-select>
            <el-button :icon="Refresh" @click="load" :loading="loading">刷新</el-button>
          </div>
          <el-tag size="small" type="info">数据源: RESTAURANT_OPS_MISS 聚合视图 — 真实问法落空自动留痕</el-tag>
        </div>
      </template>

      <el-table :data="filtered" v-loading="loading" stripe :empty-text="error ? '加载失败, 详见上方提示' : '暂无 miss 记录'">
        <el-table-column label="问法" prop="query_text" min-width="220" show-overflow-tooltip />
        <el-table-column label="模板" prop="template_code" width="180" />
        <el-table-column label="出现次数" prop="count" width="100" sortable align="center" />
        <el-table-column label="首次出现" width="170">
          <template #default="{ row }">{{ fmtTime(row.first_seen) }}</template>
        </el-table-column>
        <el-table-column label="最近出现" width="170" sortable :sort-method="(a: FlywheelMiss, b: FlywheelMiss) => new Date(a.last_seen).getTime() - new Date(b.last_seen).getTime()">
          <template #default="{ row }">{{ fmtTime(row.last_seen) }}</template>
        </el-table-column>
        <el-table-column label="处理状态" width="180" align="center">
          <template #default="{ row }">
            <el-select :model-value="row.status" size="small" @update:model-value="(v: FlywheelMiss['status']) => onStatusChange(row, v)">
              <template #label>
                <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
              </template>
              <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :value="o.value" :label="o.label">
                <el-tag :type="o.type" size="small">{{ o.label }}</el-tag>
              </el-option>
            </el-select>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page-container {
  padding: 20px;
}
.load-error-alert {
  margin-bottom: 16px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.filter-area {
  display: flex;
  align-items: center;
  gap: 10px;
}

@media (max-width: 768px) {
  .page-container {
    padding: 12px;
  }
}
</style>
