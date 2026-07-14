<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Search, Refresh } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });

// 筛选: 日志类型/级别 是后端文档化的固定枚举 (entity 注释: INFO/WARNING/ERROR/AUDIT,
// DEBUG/INFO/WARN/ERROR/FATAL) — 日志表体量无上限, 不能像小目录页那样全量拉取再动态取值,
// 这里按固定枚举给选项。操作人为模糊搜索 (ILIKE), 用输入框而非下拉。
const LOG_TYPE_OPTIONS = ['INFO', 'WARNING', 'ERROR', 'AUDIT'];
const LOG_LEVEL_OPTIONS = ['DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'];
const filterLogType = ref('');
const filterLogLevel = ref('');
const filterUsername = ref('');
const filterKeyword = ref('');

onMounted(() => loadData());

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/system-logs`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        logType: filterLogType.value || undefined,
        logLevel: filterLogLevel.value || undefined,
        username: filterUsername.value || undefined,
        keyword: filterKeyword.value || undefined
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    }
  } catch {
    ElMessage.error('加载日志失败');
  } finally {
    loading.value = false;
  }
}

function handleFilterChange() {
  pagination.value.page = 1;
  loadData();
}

function handleFilterReset() {
  filterLogType.value = '';
  filterLogLevel.value = '';
  filterUsername.value = '';
  filterKeyword.value = '';
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) { pagination.value.page = page; loadData(); }
</script>

<template>
  <div class="page-container">
    <el-card>
      <template #header><span>操作日志</span></template>

      <div class="filter-bar">
        <el-select v-model="filterLogType" placeholder="全部日志类型" clearable style="width: 140px" @change="handleFilterChange">
          <el-option v-for="t in LOG_TYPE_OPTIONS" :key="t" :label="t" :value="t" />
        </el-select>
        <el-select v-model="filterLogLevel" placeholder="全部日志级别" clearable style="width: 140px" @change="handleFilterChange">
          <el-option v-for="l in LOG_LEVEL_OPTIONS" :key="l" :label="l" :value="l" />
        </el-select>
        <el-input
          v-model="filterUsername"
          placeholder="按操作人搜索"
          clearable
          style="width: 160px"
          @keyup.enter="handleFilterChange"
          @clear="handleFilterChange"
        />
        <el-input
          v-model="filterKeyword"
          placeholder="按日志内容关键词搜索"
          :prefix-icon="Search"
          clearable
          style="width: 220px"
          @keyup.enter="handleFilterChange"
          @clear="handleFilterChange"
        />
        <el-button type="primary" :icon="Search" @click="handleFilterChange">搜索</el-button>
        <el-button :icon="Refresh" @click="handleFilterReset">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无日志" stripe border>
        <el-table-column prop="action" label="操作类型" width="140" show-overflow-tooltip />
        <el-table-column prop="username" label="操作人" width="120" />
        <el-table-column prop="message" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="时间" width="180" />
      </el-table>
      <div style="display: flex; justify-content: flex-end; margin-top: 16px;">
        <el-pagination
          :current-page="pagination.page"
          :page-size="pagination.size"
          :total="pagination.total"
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.filter-bar { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
</style>
