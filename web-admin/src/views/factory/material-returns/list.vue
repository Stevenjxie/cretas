<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { get } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { handleCatchError } from '@/utils/errorToast';

interface MaterialReturnRecord {
  id: string;
  requisitionId: string;
  // 防呆 Rule 2 (fool-proof-design): 后端 (ProductionMaterialReturnController) 已批量解析裸 GUID
  // 为人类可读名称, 前端只展示 requisitionNo/materialName/batchNumber, 不再显 UUID。
  requisitionNo?: string;
  requisitionItemId: string;
  materialTypeId: string;
  materialName?: string;
  materialBatchId: string;
  batchNumber?: string;
  returnQuantity: number | string;
  returnStatus: string;
  createdAt?: string | null;
}

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const tableData = ref<MaterialReturnRecord[]>([]);
// 防呆 Rule 2: 搜索框改按需求单号 (人话) 查询, 不再要求仓管员手填需求单 UUID。
const requisitionNo = ref('');
const pagination = ref({ page: 1, size: 10, total: 0 });

onMounted(loadData);

function formatDateTime(value?: string | null): string {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '-';
}

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: Record<string, unknown> = {
      page: pagination.value.page - 1,
      size: pagination.value.size,
    };
    if (requisitionNo.value.trim()) params.requisitionNo = requisitionNo.value.trim();
    const res = await get(`/${factoryId.value}/production-material-returns`, { params });
    if (res.success) {
      const data = res.data || {};
      tableData.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
    }
  } catch (e) {
    handleCatchError(e, '加载退料记录失败');
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>退料记录</h2>
        <span class="page-subtitle">生产关单回仓台账</span>
      </div>
      <div class="header-actions">
        <el-input v-model="requisitionNo" clearable placeholder="需求单号 (如 MR-20260701-001)" class="filter-input" @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
      </div>
    </div>

    <el-card shadow="never" class="content-card">
      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="createdAt" label="执行时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="需求单号" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.requisitionNo || row.requisitionId }}</template>
        </el-table-column>
        <el-table-column label="物料名称" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.materialName || row.materialTypeId }}</template>
        </el-table-column>
        <el-table-column label="批次号" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.batchNumber || row.materialBatchId }}</template>
        </el-table-column>
        <el-table-column prop="returnQuantity" label="退回数量" width="120" align="right" />
        <el-table-column prop="returnStatus" label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.returnStatus === 'EXECUTED' ? 'success' : 'warning'">
              {{ row.returnStatus === 'EXECUTED' ? '已执行' : row.returnStatus }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          :page-size="pagination.size"
          :total="pagination.total"
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-container {
  padding: 20px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    color: #1a2332;
    font-size: 20px;
    font-weight: 600;
  }
}

.page-subtitle {
  display: block;
  margin-top: 4px;
  color: #7a8599;
  font-size: 13px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-input {
  width: 240px;
}

.content-card {
  border-radius: 10px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
