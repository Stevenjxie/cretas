<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { vReveal } from '@/composables/useReveal';
import { deleteStoreMaster, listStoreMaster, updateStoreMaster, type StoreMaster } from '@/api/logistics';
import { useAuthStore } from '@/store/modules/auth';

// 门店库 —— 记忆每家门店的坐标/区域，导入或手动录入时按门店名复用，不必每天重新地理编码。
// 农村地址容易编错，可在此一次性修正坐标，之后所有排线永久用修正后的坐标（客户"录一次"核心诉求）。

const authStore = useAuthStore();
const loading = ref(false);
const rows = ref<StoreMaster[]>([]);
const total = ref(0);
const page = ref(0);
const size = ref(20);
const keyword = ref('');

async function load(): Promise<void> {
  const factoryId = authStore.factoryId;
  if (!factoryId) return;
  loading.value = true;
  try {
    const res = await listStoreMaster(factoryId, { page: page.value, size: size.value, keyword: keyword.value.trim() || undefined });
    rows.value = res.data?.content ?? [];
    total.value = res.data?.totalElements ?? 0;
  } catch {
    ElMessage.error('加载门店库失败');
  } finally {
    loading.value = false;
  }
}

function onSearch(): void {
  page.value = 0;
  void load();
}

function onPage(p: number): void {
  page.value = p - 1;
  void load();
}

const sourceLabel: Record<string, string> = { GEOCODED: '自动定位', MANUAL: '手工修正', IMPORT: '导入带入' };
function coordText(r: StoreMaster): string {
  if (r.longitude == null || r.latitude == null) return '未定位';
  return `${Number(r.longitude).toFixed(5)}, ${Number(r.latitude).toFixed(5)}`;
}

// ==================== 修正坐标 ====================
const editVisible = ref(false);
const editForm = ref<{ id: string; storeName: string; address: string; areaCode: string; longitude: string; latitude: string; version: number }>({
  id: '', storeName: '', address: '', areaCode: '', longitude: '', latitude: '', version: 0,
});

function openEdit(r: StoreMaster): void {
  editForm.value = {
    id: r.id,
    storeName: r.storeName,
    address: r.address ?? '',
    areaCode: r.areaCode ?? '',
    longitude: r.longitude == null ? '' : String(r.longitude),
    latitude: r.latitude == null ? '' : String(r.latitude),
    version: r.version,
  };
  editVisible.value = true;
}

async function saveEdit(): Promise<void> {
  const factoryId = authStore.factoryId;
  if (!factoryId) return;
  const f = editForm.value;
  const lonSet = f.longitude.trim() !== '';
  const latSet = f.latitude.trim() !== '';
  if (lonSet !== latSet) {
    ElMessage.warning('经度和纬度必须同时填写或同时留空');
    return;
  }
  try {
    await updateStoreMaster(factoryId, f.id, {
      longitude: lonSet ? Number(f.longitude) : null,
      latitude: latSet ? Number(f.latitude) : null,
      areaCode: f.areaCode.trim() || null,
      address: f.address.trim() || null,
      version: f.version,
    });
    ElMessage.success(`${f.storeName} 已更新`);
    editVisible.value = false;
    await load();
  } catch {
    ElMessage.error('保存失败，请重试');
  }
}

async function removeStore(r: StoreMaster): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认从门店库删除「${r.storeName}」？下次导入该门店会重新地理编码。`, '删除门店', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    });
  } catch {
    return;
  }
  const factoryId = authStore.factoryId;
  if (!factoryId) return;
  try {
    await deleteStoreMaster(factoryId, r.id);
    ElMessage.success('已删除');
    await load();
  } catch {
    ElMessage.error('删除失败');
  }
}

onMounted(load);
</script>

<template>
  <main class="stores-page">
    <header class="page-header">
      <div>
        <h1>门店库</h1>
        <p>记忆每家门店的坐标与区域。导入或手动录入时按门店名自动复用，不必每天重新定位；农村地址编错可在此一次性修正，永久生效。</p>
      </div>
    </header>

    <el-card v-reveal="0" shadow="never">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索门店名"
          clearable
          style="width: 240px"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
        <el-button type="primary" @click="onSearch">搜索</el-button>
        <span class="count">共 {{ total }} 家门店</span>
      </div>

      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column prop="storeName" label="门店名称" min-width="160" />
        <el-table-column prop="address" label="地址" min-width="220"><template #default="{ row }">{{ row.address || '—' }}</template></el-table-column>
        <el-table-column label="区域" min-width="90"><template #default="{ row }">{{ row.areaCode || '—' }}</template></el-table-column>
        <el-table-column label="坐标" min-width="180">
          <template #default="{ row }">
            <span :class="{ unlocated: row.longitude == null }">{{ coordText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="100">
          <template #default="{ row }">
            <el-tag :type="row.source === 'MANUAL' ? 'success' : row.source === 'IMPORT' ? 'warning' : 'info'" effect="plain" size="small">
              {{ sourceLabel[row.source] || row.source }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">修正</el-button>
            <el-button link type="danger" @click="removeStore(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="prev, pager, next"
          :total="total"
          :page-size="size"
          :current-page="page + 1"
          @current-change="onPage"
        />
      </div>
    </el-card>

    <el-dialog v-model="editVisible" :title="`修正门店 · ${editForm.storeName}`" width="460px">
      <el-form label-width="90px" size="small">
        <el-form-item label="地址"><el-input v-model="editForm.address" placeholder="配送地址" /></el-form-item>
        <el-form-item label="区域"><el-input v-model="editForm.areaCode" placeholder="如：园区" /></el-form-item>
        <el-form-item label="经度"><el-input v-model="editForm.longitude" placeholder="如：120.7300000" /></el-form-item>
        <el-form-item label="纬度"><el-input v-model="editForm.latitude" placeholder="如：31.3200000" /></el-form-item>
        <p class="dialog-hint">经度、纬度必须同时填写或同时留空。修正后来源标记为「手工修正」，之后所有排线永久使用此坐标。</p>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped lang="scss">
.stores-page { display: grid; gap: 20px; max-width: 1440px; min-height: 100%; padding: 24px; margin: 0 auto; background: #f8fafc; }
.page-header h1 { margin: 0; color: #101828; font-size: 24px; } .page-header p { margin: 8px 0 0; max-width: 760px; color: #667085; line-height: 1.6; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; } .toolbar .count { margin-left: auto; color: #667085; font-size: 13px; font-variant-numeric: tabular-nums; }
.pager { display: flex; justify-content: flex-end; margin-top: 14px; }
.unlocated { color: #b42318; }
.dialog-hint { margin: 4px 0 0; color: #98a2b3; font-size: 12.5px; line-height: 1.5; }
:deep(.el-table) { font-variant-numeric: tabular-nums; }
@media (max-width: 720px) { .stores-page { padding: 16px; } }
</style>
