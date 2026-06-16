<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Search } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('procurement'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const dialogVisible = ref(false);
const searchKeyword = ref('');

const priceTypeMap: Record<string, string> = {
  PURCHASE_PRICE: '采购价',
  SELLING_PRICE: '销售价',
  TRANSFER_PRICE: '调拨价',
};

const form = ref({
  name: '',
  priceType: 'PURCHASE_PRICE',
  effectiveFrom: '',
  effectiveTo: '',
  items: [{ itemName: '', unit: 'kg', standardPrice: 0, minPrice: 0, maxPrice: 0 }],
});

onMounted(() => loadData());

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: TableRow = { page: pagination.value.page, size: pagination.value.size };
    const kw = searchKeyword.value.trim();
    if (kw) params.keyword = kw;
    const res = await get(`/${factoryId.value}/price-lists`, { params });
    if (res.success && res.data) {
      tableData.value = res.data.content || [];
      pagination.value.total = res.data.totalElements || 0;
    } else if (res.success === false) {
      ElMessage.error(res.message || '加载价格表失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { loading.value = false; }
}

function addItem() {
  form.value.items.push({ itemName: '', unit: 'kg', standardPrice: 0, minPrice: 0, maxPrice: 0 });
}

function removeItem(idx: number) {
  if (form.value.items.length > 1) form.value.items.splice(idx, 1);
}

async function handleCreate() {
  if (!form.value.name) return ElMessage.warning('请输入价格表名称');
  if (!form.value.priceType) return ElMessage.warning('请选择价格类型');
  if (!form.value.effectiveFrom) return ElMessage.warning('请选择生效日期');
  if (!Array.isArray(form.value.items) || form.value.items.length === 0) {
    return ElMessage.warning('请至少添加一行价格明细');
  }
  const badPriceIdx = form.value.items.findIndex(i => i.standardPrice == null);
  if (badPriceIdx >= 0) {
    return ElMessage.warning(`第 ${badPriceIdx + 1} 行请填写标准价`);
  }
  try {
    const res = await post(`/${factoryId.value}/price-lists`, form.value);
    if (res.success) { ElMessage.success('创建成功'); dialogVisible.value = false; loadData(); }
    else { ElMessage.error(res.message || '创建失败'); }
  } catch { /* axios interceptor already displayed error toast */ }
}

async function handleDelete(id: string) {
  try {
    await ElMessageBox.confirm('确认删除此价格表？', '提示');
    const res = await del(`/${factoryId.value}/price-lists/${id}`);
    if (res.success) { ElMessage.success('删除成功'); loadData(); }
    else { ElMessage.error(res.message || '删除失败'); }
  } catch (error) { /* axios interceptor handles API errors; cancel from MessageBox is silent */ }
}

function handlePageChange(page: number) { pagination.value.page = page; loadData(); }
function handleSizeChange(size: number) { pagination.value.size = size; pagination.value.page = 1; loadData(); }
function handleSearch() { pagination.value.page = 1; loadData(); }
function handleSearchClear() { searchKeyword.value = ''; handleSearch(); }
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">价格表管理</span>
            <span class="data-count">共 {{ pagination.total }} 条</span>
          </div>
          <div class="header-right">
            <el-input
              v-model="searchKeyword"
              placeholder="搜索价格表名称"
              clearable
              :prefix-icon="Search"
              style="width: 240px; margin-right: 12px;"
              @keyup.enter="handleSearch"
              @clear="handleSearchClear"
            />
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button v-if="canWrite && canViewPrice" type="primary" :icon="Plus" @click="dialogVisible = true">新建价格表</el-button>
          </div>
        </div>
      </template>

      <template v-if="canViewPrice">
        <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border row-key="id" style="width: 100%">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div style="padding: 12px 48px">
                <el-table :data="row.items || []" border size="small">
                  <el-table-column prop="itemName" label="项目名称" min-width="150" />
                  <el-table-column prop="unit" label="单位" width="80" align="center" />
                  <el-table-column prop="standardPrice" label="标准价" width="120" align="right">
                    <template #default="{ row: item }">{{ formatAmount(item.standardPrice) }}</template>
                  </el-table-column>
                  <el-table-column prop="minPrice" label="最低价" width="120" align="right">
                    <template #default="{ row: item }">{{ formatAmount(item.minPrice) }}</template>
                  </el-table-column>
                  <el-table-column prop="maxPrice" label="最高价" width="120" align="right">
                    <template #default="{ row: item }">{{ formatAmount(item.maxPrice) }}</template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="价格表名称" min-width="200" show-overflow-tooltip />
          <el-table-column prop="priceType" label="类型" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small">{{ priceTypeMap[row.priceType] || row.priceType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="effectiveFrom" label="生效日期" width="120" />
          <el-table-column prop="effectiveTo" label="失效日期" width="120" />
          <el-table-column prop="isActive" label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.isActive ? 'success' : 'info'" size="small">{{ row.isActive ? '生效中' : '未生效' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right" align="center">
            <template #default="{ row }">
              <el-button v-if="canWrite" type="danger" link size="small" @click="handleDelete(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-wrapper">
          <el-pagination v-model:current-page="pagination.page" v-model:page-size="pagination.size"
            :page-sizes="[10, 20, 50]" :total="pagination.total"
            layout="total, sizes, prev, pager, next, jumper"
            @current-change="handlePageChange" @size-change="handleSizeChange" />
        </div>
      </template>
      <el-empty v-else description="您没有查看价格数据的权限" />
    </el-card>

    <el-dialog v-if="canViewPrice" v-model="dialogVisible" title="新建价格表" width="720px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称" required><el-input v-model="form.name" placeholder="例如:2025 Q2 采购标准价" /></el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="form.priceType">
            <el-radio value="PURCHASE_PRICE">采购价</el-radio>
            <el-radio value="SELLING_PRICE">销售价</el-radio>
            <el-radio value="TRANSFER_PRICE">调拨价</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="生效日期" required><el-date-picker v-model="form.effectiveFrom" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="失效日期"><el-date-picker v-model="form.effectiveTo" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-divider>价格明细</el-divider>
        <div class="item-row item-header">
          <span style="width: 150px">名称</span>
          <span style="width: 80px">单位</span>
          <span style="width: 120px"><span class="req-star">*</span>标准价</span>
          <span style="width: 110px">最低</span>
          <span style="width: 110px">最高</span>
          <span style="width: 50px"></span>
        </div>
        <div v-for="(item, idx) in form.items" :key="idx" class="item-row">
          <el-input v-model="item.itemName" placeholder="名称" style="width: 150px" />
          <el-input v-model="item.unit" placeholder="单位" style="width: 80px" />
          <el-input-number v-model="item.standardPrice" :min="0" :precision="2" placeholder="标准价" style="width: 120px" />
          <el-input-number v-model="item.minPrice" :min="0" :precision="2" placeholder="最低" style="width: 110px" />
          <el-input-number v-model="item.maxPrice" :min="0" :precision="2" placeholder="最高" style="width: 110px" />
          <el-button type="danger" link @click="removeItem(idx)" :disabled="form.items.length <= 1">删除</el-button>
        </div>
        <el-button style="width: 100%; margin-top: 8px" @click="addItem">+ 添加行</el-button>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; display: flex; flex-direction: column; padding: 20px; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: baseline; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
    .data-count { font-size: 13px; color: #909399; }
  }
}
.pagination-wrapper { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #ebeef5; margin-top: 16px; }
.item-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.item-header { font-size: 13px; color: #606266; font-weight: 500; margin-bottom: 4px; }
.req-star { color: #f56c6c; margin-right: 2px; }
</style>
