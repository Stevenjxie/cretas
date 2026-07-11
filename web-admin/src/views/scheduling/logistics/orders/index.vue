<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { LocationStatus, LogisticsDeliveryOrder } from '@/api/logistics';
import { useLogisticsScheduling } from '../useLogisticsScheduling';

const state = useLogisticsScheduling();
const router = useRouter();

const selectedBatchId = ref<string | null>(null);
const currentPage = ref(1); // UI 侧 1-based，调用后端时转 0-based
const pageSize = ref(20);

const locationStatusMeta: Record<LocationStatus, { label: string; type: 'success' | 'info' | 'warning' }> = {
  RESOLVED: { label: '已定位', type: 'success' },
  UNRESOLVED: { label: '待定位', type: 'info' },
  OUT_OF_BOUNDS: { label: '超出范围', type: 'warning' },
};

const rows = computed(() => state.ordersPage.value?.content ?? []);
const total = computed(() => state.ordersPage.value?.totalElements ?? 0);

onMounted(async () => {
  await state.loadBatches();
  selectedBatchId.value = state.batches.value[0]?.id ?? null;
});

watch(selectedBatchId, async (batchId) => {
  currentPage.value = 1;
  if (batchId) await state.loadOrdersPage(batchId, 0, pageSize.value);
});

async function handlePageChange(nextPage: number): Promise<void> {
  if (!selectedBatchId.value) return;
  currentPage.value = nextPage;
  await state.loadOrdersPage(selectedBatchId.value, nextPage - 1, pageSize.value);
}

async function handleSizeChange(nextSize: number): Promise<void> {
  if (!selectedBatchId.value) return;
  pageSize.value = nextSize;
  currentPage.value = 1;
  await state.loadOrdersPage(selectedBatchId.value, 0, nextSize);
}

function goToWorkbench(): void {
  router.push('/scheduling/logistics/workbench');
}

async function downloadTemplate(): Promise<void> {
  await state.downloadTemplate();
}

// ==================== 门店详情 / 补录定位 ====================

const detailVisible = ref(false);
const activeOrder = ref<LogisticsDeliveryOrder | null>(null);
const locationForm = ref<{ longitude: number | null; latitude: number | null }>({ longitude: null, latitude: null });

function openDetail(order: LogisticsDeliveryOrder): void {
  activeOrder.value = order;
  locationForm.value = { longitude: order.longitude ?? null, latitude: order.latitude ?? null };
  detailVisible.value = true;
}

async function saveLocation(): Promise<void> {
  if (!activeOrder.value) return;
  const { longitude, latitude } = locationForm.value;
  if (longitude === null || latitude === null || Number.isNaN(longitude) || Number.isNaN(latitude)) {
    ElMessage.warning('请输入经度和纬度');
    return;
  }
  const ok = await state.updateLocation(activeOrder.value.id, longitude, latitude);
  if (ok) {
    ElMessage.success(`已更新 ${activeOrder.value.storeName} 的定位`);
    activeOrder.value = rows.value.find((row) => row.id === activeOrder.value?.id) ?? activeOrder.value;
  }
}
</script>

<template>
  <main class="support-page">
    <header class="page-header">
      <div>
        <h1>门店与订单</h1>
        <p>按导入批次分页查看真实订单，点击门店可查看明细或补录定位。</p>
      </div>
      <div class="header-actions">
        <el-select v-if="state.batches.value.length" v-model="selectedBatchId" placeholder="选择批次" style="width: 220px">
          <el-option
            v-for="b in state.batches.value"
            :key="b.id"
            :label="`${b.businessDate} · ${b.batchNumber}（${b.validRows}/${b.totalRows}）`"
            :value="b.id"
          />
        </el-select>
        <el-button @click="downloadTemplate">下载导入模板</el-button>
        <el-button type="primary" @click="goToWorkbench">导入订单</el-button>
      </div>
    </header>

    <el-alert v-if="state.ordersError.value" type="error" :closable="false" :title="state.ordersError.value" show-icon />

    <el-card v-if="!state.batches.value.length && !state.ordersLoading.value" shadow="never" class="empty-card">
      <p>尚未导入任何订单批次。</p>
      <el-button type="primary" @click="goToWorkbench">前往导入订单</el-button>
    </el-card>

    <el-card v-else shadow="never">
      <el-table v-loading="state.ordersLoading.value" :data="rows" stripe row-key="id" class="orders-table" @row-click="openDetail">
        <el-table-column prop="storeCode" label="门店编码" min-width="110" />
        <el-table-column prop="storeName" label="门店名称" min-width="160" />
        <el-table-column prop="address" label="配送地址" min-width="240" show-overflow-tooltip />
        <el-table-column prop="pieces" label="件数" min-width="80" />
        <el-table-column prop="boxes" label="箱数" min-width="80" />
        <el-table-column label="重量" min-width="100"><template #default="{ row }">{{ row.weightKg }} kg</template></el-table-column>
        <el-table-column label="体积" min-width="100"><template #default="{ row }">{{ row.volumeCbm }} m³</template></el-table-column>
        <el-table-column label="定位状态" min-width="110">
          <template #default="{ row }">
            <el-tag :type="locationStatusMeta[row.locationStatus as LocationStatus].type" effect="plain">
              {{ locationStatusMeta[row.locationStatus as LocationStatus].label }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <el-dialog v-model="detailVisible" :title="activeOrder?.storeName" width="480px">
      <template v-if="activeOrder">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="门店编码">{{ activeOrder.storeCode }}</el-descriptions-item>
          <el-descriptions-item label="区域">{{ activeOrder.areaCode || '未设置' }}</el-descriptions-item>
          <el-descriptions-item label="配送地址" :span="2">{{ activeOrder.address }}</el-descriptions-item>
          <el-descriptions-item label="件数">{{ activeOrder.pieces }}</el-descriptions-item>
          <el-descriptions-item label="箱数">{{ activeOrder.boxes }}</el-descriptions-item>
          <el-descriptions-item label="重量">{{ activeOrder.weightKg }} kg</el-descriptions-item>
          <el-descriptions-item label="体积">{{ activeOrder.volumeCbm }} m³</el-descriptions-item>
          <el-descriptions-item label="定位状态" :span="2">
            <el-tag :type="locationStatusMeta[activeOrder.locationStatus].type" effect="plain">
              {{ locationStatusMeta[activeOrder.locationStatus].label }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div class="location-form">
          <p class="location-hint">补录/修正经纬度后将回填定位状态，供地图展示使用。</p>
          <el-form label-width="70px" size="small">
            <el-form-item label="经度">
              <el-input-number v-model="locationForm.longitude" :precision="6" :step="0.0001" style="width: 100%" />
            </el-form-item>
            <el-form-item label="纬度">
              <el-input-number v-model="locationForm.latitude" :precision="6" :step="0.0001" style="width: 100%" />
            </el-form-item>
          </el-form>
        </div>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button type="primary" @click="saveLocation">保存定位</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped lang="scss">
.support-page { display: grid; gap: 20px; max-width: 1440px; min-height: 100%; padding: 24px; margin: 0 auto; background: #f8fafc; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.page-header h1 { margin: 0; color: #101828; font-size: 24px; }.page-header p { margin: 8px 0 0; color: #667085; }.header-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }.orders-table :deep(.el-table__row) { cursor: pointer; }
.empty-card { display: grid; gap: 12px; padding: 20px; text-align: center; }
.pagination-wrapper { display: flex; justify-content: flex-end; margin-top: 12px; }
.location-form { margin-top: 16px; padding-top: 16px; border-top: 1px solid #edf2f7; }
.location-hint { margin: 0 0 10px; color: #667085; font-size: 13px; }
@media (max-width: 720px) { .support-page { padding: 16px; }.page-header { flex-direction: column; }.header-actions { width: 100%; } }
</style>
