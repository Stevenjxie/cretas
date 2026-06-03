<template>
  <CanvasAwareWrapper module-code="restaurant">
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">供应商进货录入</span>
            <span class="data-count">共 {{ pagination.total }} 条</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="primary" :icon="Camera" @click="openUpload">
              上传送货单
            </el-button>
          </div>
        </div>
      </template>

      <div class="search-bar" role="search" aria-label="送货单筛选">
        <el-select v-model="filterStatus" placeholder="状态" clearable style="width: 140px" @change="loadList">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="已拒绝" value="REJECTED" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="loadList">搜索</el-button>
        <el-button :icon="Refresh" @click="resetFilter">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" stripe style="width: 100%" @row-click="goDetail">
        <el-table-column prop="deliveryDate" label="送货日期" width="120" />
        <el-table-column prop="supplierName" label="供应商" min-width="140">
          <template #default="{ row }">{{ row.supplierName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="noteNumber" label="送货单号" min-width="120">
          <template #default="{ row }">{{ row.noteNumber || '—' }}</template>
        </el-table-column>
        <el-table-column prop="totalAmount" label="合计金额" width="120" align="right">
          <template #default="{ row }">{{ row.totalAmount != null ? '¥' + row.totalAmount : '—' }}</template>
        </el-table-column>
        <el-table-column label="来源" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.sourceType === 'OCR' ? 'success' : 'info'">
              {{ row.sourceType === 'OCR' ? 'OCR' : '手工' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="110">
          <template #default="{ row }">
            <span v-if="row.ocrConfidence != null">
              <el-tag size="small" :type="row.lowConfidenceWarning ? 'warning' : 'success'">
                {{ Math.round(row.ocrConfidence * 100) }}%
              </el-tag>
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="goDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pagination.page"
        :page-size="pagination.size"
        :total="pagination.total"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="loadList"
      />
    </el-card>

    <SupplierDeliveryNoteUploadDialog
      v-model="uploadVisible"
      :factory-id="factoryId"
      @parsed="onParsed"
    />
  </div>
  </CanvasAwareWrapper>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Camera, Search, Refresh } from '@element-plus/icons-vue';
import { useFactoryId } from '@/composables/useFactoryId';
import { usePermissionStore } from '@/store/modules/permission';
import { getNoteList, type SupplierDeliveryNoteDto } from '@/api/restaurant/supplierDeliveryNote';
import { handleCatchError } from '@/utils/errorToast';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import SupplierDeliveryNoteUploadDialog from './SupplierDeliveryNoteUploadDialog.vue';

const router = useRouter();
const factoryId = useFactoryId();
const permissionStore = usePermissionStore();
const canWrite = computed(() => permissionStore.canWrite('restaurant'));

const loading = ref(false);
const tableData = ref<SupplierDeliveryNoteDto[]>([]);
const filterStatus = ref<string>('');
const uploadVisible = ref(false);
const pagination = reactive({ page: 1, size: 20, total: 0 });

function statusText(s: string): string {
  return { DRAFT: '草稿', CONFIRMED: '已确认', REJECTED: '已拒绝' }[s] || s;
}
function statusTagType(s: string): string {
  return { DRAFT: 'info', CONFIRMED: 'success', REJECTED: 'danger' }[s] || 'info';
}

async function loadList() {
  loading.value = true;
  try {
    const resp = await getNoteList(factoryId.value, {
      status: filterStatus.value || undefined,
      page: pagination.page,
      size: pagination.size,
    });
    if (resp.success && resp.data) {
      tableData.value = resp.data.content || [];
      pagination.total = resp.data.totalElements || 0;
    }
  } catch (e) {
    handleCatchError(e, '加载送货单列表失败');
  } finally {
    loading.value = false;
  }
}

function resetFilter() {
  filterStatus.value = '';
  pagination.page = 1;
  loadList();
}

function openUpload() {
  uploadVisible.value = true;
}

function onParsed(note: SupplierDeliveryNoteDto) {
  uploadVisible.value = false;
  // 解析完成 → 直接进详情页校对行项
  router.push({ name: 'SupplierDeliveryNoteDetail', params: { id: note.id } });
}

function goDetail(row: SupplierDeliveryNoteDto) {
  router.push({ name: 'SupplierDeliveryNoteDetail', params: { id: row.id } });
}

onMounted(loadList);
</script>

<style scoped lang="scss">
@use '../restaurant-shared.scss';
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-left {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.data-count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
</style>
