<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Search, Refresh, PriceTag } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import type { TableRow } from '@/types/api';

const router = useRouter();

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('rd'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
const currentUser = computed(() => {
  const u = authStore.user;
  return (u as TableRow)?.fullName || (u as TableRow)?.username || '';
});

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
// page 是 0-based (后端 Spring Pageable; 同文件 reset 用 0, el-pagination current-page 用 page+1).
// 初值之前误写成 1 → 首屏发 page=1 给后端 = 取第 2 页 → 样品全在第 0 页时首屏空白 (所有工厂都中).
const pagination = ref({ page: 0, size: 20, total: 0 });
const activeTab = ref('samples');

// 搜索筛选
const searchForm = ref({ customerName: '', status: '', name: '' });

const sampleStatusMap: Record<string, { text: string; type: string }> = {
  DRAFT: { text: '待研发', type: 'info' },
  IN_PROGRESS: { text: '研发中', type: '' },
  TESTING: { text: '已寄样', type: 'warning' },
  SUBMITTED: { text: '待审核', type: 'warning' },
  APPROVED: { text: '样品通过', type: 'success' },
  REJECTED: { text: '样品未通过', type: 'danger' },
};

const requestStatusMap: Record<string, { text: string; type: string }> = {
  SUBMITTED: { text: '待分配', type: 'warning' },
  ASSIGNED: { text: '已分配', type: '' },
  IN_PROGRESS: { text: '进行中', type: '' },
  COMPLETED: { text: '已完成', type: 'success' },
  CANCELLED: { text: '已取消', type: 'info' },
};

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const endpoint = activeTab.value === 'requests' ? 'requests' : activeTab.value === 'quotations' ? 'quotations' : 'samples';
    const params: TableRow = { page: pagination.value.page, size: pagination.value.size };
    if (activeTab.value === 'samples') {
      if (searchForm.value.status) params.status = searchForm.value.status;
      if (searchForm.value.customerName) params.customerName = searchForm.value.customerName;
      if (searchForm.value.name) params.name = searchForm.value.name;
    }
    const res = await get(`/${factoryId.value}/rd/${endpoint}`, { params });
    if (res.success) {
      let items = res.data.content || [];
      // 前端筛选（后端不支持customerName/name参数时的fallback）
      if (activeTab.value === 'samples') {
        if (searchForm.value.customerName) {
          const kw = searchForm.value.customerName.toLowerCase();
          items = items.filter((r: TableRow) => String(r.customerName || '').toLowerCase().includes(kw));
        }
        if (searchForm.value.name) {
          const kw = searchForm.value.name.toLowerCase();
          items = items.filter((r: TableRow) => String(r.name || '').toLowerCase().includes(kw));
        }
      }
      tableData.value = items;
      pagination.value.total = res.data.totalElements || 0;
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { loading.value = false; }
}

function switchTab(tab: string) {
  activeTab.value = tab;
  searchForm.value = { customerName: '', status: '', name: '' };
  pagination.value.page = 0;
  loadData();
}

function handleSearch() {
  pagination.value.page = 0;
  loadData();
}

function resetSearch() {
  searchForm.value = { customerName: '', status: '', name: '' };
  pagination.value.page = 0;
  loadData();
}

// 样品操作
async function handleSampleAction(id: string, action: string) {
  const labels: Record<string, string> = { submit: '提交审核', approve: '审核通过', reject: '驳回' };
  try {
    if (action === 'reject') {
      const { value: notes } = await ElMessageBox.prompt('请输入驳回原因', '驳回');
      await post(`/${factoryId.value}/rd/samples/${id}/reject`, { notes });
    } else if (action === 'approve') {
      const { value: notes } = await ElMessageBox.prompt('审核意见（可选）', '审核通过', { inputValue: '', inputValidator: () => true });
      await post(`/${factoryId.value}/rd/samples/${id}/approve`, { notes: notes || '' });
    } else {
      await ElMessageBox.confirm(`确认${labels[action]}？`, '确认');
      await post(`/${factoryId.value}/rd/samples/${id}/${action}`);
    }
    ElMessage.success(`${labels[action]}成功`);
    loadData();
  } catch (e) { /* axios interceptor handles API errors; cancel from MessageBox is silent */ }
}

// 新建研发需求弹窗
const requestDialogVisible = ref(false);
const requestForm = ref({ customerName: '', customerContact: '', requirements: '', urgency: 'MEDIUM' });
const submitting = ref(false);

async function handleCreateRequest() {
  if (!requestForm.value.customerName || !requestForm.value.requirements) {
    ElMessage.warning('请填写客户名称和需求描述'); return;
  }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/rd/requests`, requestForm.value);
    if (res.success) {
      ElMessage.success('研发需求已创建');
      requestDialogVisible.value = false;
      switchTab('requests');
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { submitting.value = false; }
}

// 新建样品弹窗
const sampleDialogVisible = ref(false);
const sampleForm = ref({
  rdRequestId: '',
  customerName: '',
  name: '',
  specification: '',
  productLevel: '',
  salesperson: '',
  storageMethod: '',
});

// 业务员列表
const salespersonList = ref<TableRow[]>([]);

async function loadSalespersons() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/users`, { params: { size: 200 } });
    if (res.success && res.data) {
      salespersonList.value = Array.isArray(res.data) ? res.data : res.data.content || [];
    }
  } catch { /* optional */ }
}

// D13: Dirty form guard — warn user before leaving with unsaved changes
const isDirty = ref(false);
watch([requestDialogVisible, sampleDialogVisible], ([req, smp]) => { isDirty.value = req || smp; });
function handleBeforeUnload(e: BeforeUnloadEvent) {
  if (isDirty.value) { e.preventDefault(); e.returnValue = ''; }
}
onMounted(() => {
  loadData(); loadSalespersons();
  window.addEventListener('beforeunload', handleBeforeUnload);
});
onBeforeUnmount(() => { window.removeEventListener('beforeunload', handleBeforeUnload); });

async function handleCreateSample() {
  if (!sampleForm.value.name) { ElMessage.warning('请填写样品名称'); return; }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/rd/samples`, sampleForm.value);
    if (res.success) {
      ElMessage.success('样品已创建');
      sampleDialogVisible.value = false;
      switchTab('samples');
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { submitting.value = false; }
}

// ==================== R14: 价位选料 ====================
// GET /rd/materials/suggest-by-price?minPrice=&maxPrice=
const r14PanelVisible = ref(false);
const r14MinPrice = ref<number | null>(null);
const r14MaxPrice = ref<number | null>(null);
const r14Loading = ref(false);
const r14Results = ref<TableRow[]>([]);

async function fetchR14Suggestions() {
  if (!factoryId.value) return;
  r14Loading.value = true;
  r14Results.value = [];
  try {
    const params: Record<string, number> = {};
    if (r14MinPrice.value != null) params.minPrice = r14MinPrice.value;
    if (r14MaxPrice.value != null) params.maxPrice = r14MaxPrice.value;
    const res = await get(`/${factoryId.value}/rd/materials/suggest-by-price`, { params });
    if (res.success) {
      r14Results.value = Array.isArray(res.data) ? res.data : [];
    } else {
      ElMessage.error(res.message || '推荐选料失败');
    }
  } catch { /* axios interceptor shows toast */ }
  finally { r14Loading.value = false; }
}

// 追踪记录
const trackingDialogVisible = ref(false);
const trackingSampleId = ref('');
const trackingRecords = ref<{ date: string; content: string; attachment: string; recorder: string }[]>([]);
const newTracking = ref({ date: new Date().toISOString().slice(0, 10), content: '', attachment: '', recorder: '' });

async function openTrackingDialog(row: TableRow) {
  trackingSampleId.value = String(row.id);
  trackingRecords.value = [];
  newTracking.value = { date: new Date().toISOString().slice(0, 10), content: '', attachment: '', recorder: String(currentUser.value) };
  trackingDialogVisible.value = true;

  // P1-8: 优先从新 tracking_records 表读取
  try {
    const res = await get(`/${factoryId.value}/rd/samples/${row.id}/tracking-records`);
    if (res.success && Array.isArray(res.data) && res.data.length > 0) {
      trackingRecords.value = (res.data as TableRow[]).map((r) => ({
        date: r.recordedAt ? String(r.recordedAt).slice(0, 10) : '',
        content: String(r.content || ''),
        attachment: String(r.attachmentUrl || ''),
        recorder: String(r.recordedByName || r.recordedBy || ''),
      }));
      return;
    }
  } catch { /* ignore, fall through to legacy */ }

  // 兜底: legacy progressNotes JSON (向后兼容)
  try {
    const notes = row.progressNotes ? JSON.parse(String(row.progressNotes)) : [];
    trackingRecords.value = (notes as Record<string, string>[]).map((n) => ({
      date: n.time || n.date || '',
      content: n.note || n.content || '',
      attachment: n.photoUrl || n.attachment || '',
      recorder: n.recorder || '',
    }));
  } catch { trackingRecords.value = []; }
}

async function addTrackingRecord() {
  if (!newTracking.value.content) { ElMessage.warning('请输入追踪内容'); return; }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/rd/samples/${trackingSampleId.value}/progress`, {
      note: newTracking.value.content,
      photoUrl: newTracking.value.attachment || null,
      recorder: newTracking.value.recorder || String(currentUser.value),
      date: newTracking.value.date,
    });
    if (res.success) {
      ElMessage.success('追踪记录已添加');
      trackingRecords.value.push({ ...newTracking.value });
      newTracking.value = { date: new Date().toISOString().slice(0, 10), content: '', attachment: '', recorder: String(currentUser.value) };
      loadData();
    } else { ElMessage.error(res.message || '添加失败'); }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { submitting.value = false; }
}
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div style="display:flex;gap:12px;align-items:center">
            <span style="font-size:16px;font-weight:600">研发管理</span>
            <el-radio-group v-model="activeTab" @change="switchTab" size="small">
              <el-radio-button value="requests">研发需求</el-radio-button>
              <el-radio-button value="samples">样品管理</el-radio-button>
              <el-radio-button value="quotations">报价任务</el-radio-button>
            </el-radio-group>
          </div>
          <div style="display:flex;gap:8px" v-if="canWrite">
            <el-button v-if="activeTab === 'requests'" type="primary" @click="requestDialogVisible = true">新建研发需求</el-button>
            <el-button v-if="activeTab === 'samples'" type="primary" @click="sampleDialogVisible = true">新建样品</el-button>
          </div>
        </div>
      </template>

      <!-- 样品搜索栏 -->
      <div v-if="activeTab === 'samples'" style="display:flex;gap:8px;margin-bottom:16px;flex-wrap:wrap">
        <el-input v-model="searchForm.name" placeholder="样品名称" clearable style="width:180px" :prefix-icon="Search" @keyup.enter="handleSearch" />
        <el-input v-model="searchForm.customerName" placeholder="客户筛选" clearable style="width:160px" @keyup.enter="handleSearch" />
        <el-select v-model="searchForm.status" placeholder="产品状态" clearable style="width:140px" @change="handleSearch">
          <el-option v-for="(v, k) in sampleStatusMap" :key="k" :label="v.text" :value="k" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
        <!-- R14: 价位选料入口 -->
        <el-button :icon="PriceTag" @click="r14PanelVisible = !r14PanelVisible" :type="r14PanelVisible ? 'primary' : ''" plain>
          价位选料
        </el-button>
      </div>

      <!-- R14: 价位推荐选料面板 -->
      <el-collapse-transition>
        <div v-if="activeTab === 'samples' && r14PanelVisible" style="margin-bottom:16px">
          <el-card shadow="never" style="background:#fafafa">
            <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
              <span style="font-size:13px;color:#606266">按单价区间推荐原料：</span>
              <el-input-number
                v-model="r14MinPrice"
                :min="0"
                :precision="2"
                :step="1"
                placeholder="最低价(元)"
                style="width:150px"
                controls-position="right"
              />
              <span style="color:#909399">—</span>
              <el-input-number
                v-model="r14MaxPrice"
                :min="0"
                :precision="2"
                :step="1"
                placeholder="最高价(元)"
                style="width:150px"
                controls-position="right"
              />
              <el-button type="primary" :loading="r14Loading" :icon="PriceTag" @click="fetchR14Suggestions">推荐选料</el-button>
              <el-button @click="r14Results = []; r14MinPrice = null; r14MaxPrice = null">清空</el-button>
            </div>
            <div v-if="r14Results.length > 0" style="margin-top:12px">
              <el-table :data="r14Results" border size="small" max-height="240">
                <el-table-column prop="code" label="编码" width="120" />
                <el-table-column prop="name" label="原料名称" min-width="140" />
                <el-table-column prop="category" label="类别" width="80" align="center" />
                <el-table-column prop="unit" label="单位" width="70" align="center" />
                <el-table-column v-if="canViewPrice" prop="taxIncludedUnitPrice" label="含税单价(元)" width="120" align="right">
                  <template #default="{ row }">{{ row.taxIncludedUnitPrice != null ? row.taxIncludedUnitPrice : '—' }}</template>
                </el-table-column>
                <el-table-column prop="storageType" label="储存" width="80" align="center">
                  <template #default="{ row }">{{ row.storageType || '—' }}</template>
                </el-table-column>
              </el-table>
              <div style="font-size:12px;color:#909399;margin-top:6px">共 {{ r14Results.length }} 种原料符合价位区间</div>
            </div>
            <el-empty v-else-if="!r14Loading && r14Results.length === 0 && (r14MinPrice != null || r14MaxPrice != null)" description="该价位区间暂无候选原料" :image-size="48" style="padding:12px 0" />
          </el-card>
        </div>
      </el-collapse-transition>

      <!-- 研发需求列表 -->
      <el-table v-if="activeTab === 'requests'" :data="tableData" border stripe>
        <el-table-column prop="requestNumber" label="需求编号" width="180" />
        <el-table-column prop="customerName" label="客户" min-width="130" />
        <el-table-column prop="requirements" label="需求描述" min-width="200" show-overflow-tooltip />
        <el-table-column prop="urgency" label="紧急" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.urgency === 'HIGH' ? 'danger' : row.urgency === 'LOW' ? 'info' : 'warning'" size="small">
              {{ ({ HIGH: '紧急', MEDIUM: '普通', LOW: '低' } as Record<string, string>)[row.urgency] || row.urgency }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="requestStatusMap[row.status]?.type || 'info'" size="small">{{ requestStatusMap[row.status]?.text || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="submittedAt" label="提交时间" width="170" />
      </el-table>

      <!-- 样品列表 -->
      <el-table v-if="activeTab === 'samples'" :data="tableData" border stripe>
        <el-table-column prop="sampleCode" label="样品编码" width="180" />
        <el-table-column prop="name" label="样品名称" min-width="130" />
        <el-table-column prop="customerName" label="客户名称" min-width="110" show-overflow-tooltip />
        <el-table-column prop="specification" label="成品规格" width="120" />
        <el-table-column prop="productLevel" label="产品级别" width="90" align="center">
          <template #default="{ row }">{{ row.productLevel || '-' }}</template>
        </el-table-column>
        <el-table-column prop="salesperson" label="业务员" width="90" />
        <el-table-column prop="storageMethod" label="储存方式" width="90">
          <template #default="{ row }">{{ row.storageMethod || '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="产品状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="sampleStatusMap[row.status]?.type || 'info'" size="small">{{ sampleStatusMap[row.status]?.text || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openTrackingDialog(row)">追踪记录</el-button>
            <template v-if="canWrite">
              <el-button v-if="['DRAFT','IN_PROGRESS','TESTING'].includes(row.status)" type="warning" link size="small" @click="handleSampleAction(row.id, 'submit')">提交审核</el-button>
              <!-- SP10: 审核通过后由 SampleApprovedEvent 自动创建 BOM + 报价任务, 无需手动"提交报价申请" (旧按钮调用的 /request-quotation 端点不存在) -->
              <el-tag v-if="row.status === 'APPROVED'" type="success" size="small" effect="plain" style="margin:0 4px">已自动进入报价</el-tag>
              <el-button v-if="row.status === 'SUBMITTED'" type="success" link size="small" @click="handleSampleAction(row.id, 'approve')">通过</el-button>
              <el-button v-if="row.status === 'SUBMITTED'" type="danger" link size="small" @click="handleSampleAction(row.id, 'reject')">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <!-- 报价任务列表 -->
      <el-table v-if="activeTab === 'quotations'" :data="tableData" border stripe>
        <el-table-column prop="taskNumber" label="任务编号" width="180" />
        <el-table-column v-if="canViewPrice" prop="totalCost" label="总成本" width="120" align="right">
          <template #default="{ row }">{{ row.totalCost || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" prop="suggestedPrice" label="建议售价" width="120" align="right">
          <template #default="{ row }">{{ row.suggestedPrice || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" prop="finalPrice" label="最终报价" width="120" align="right">
          <template #default="{ row }">{{ row.finalPrice || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" prop="profitMargin" label="毛利率" width="90" align="center">
          <template #default="{ row }">{{ row.profitMargin != null ? `${row.profitMargin}%` : '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="(({ PENDING: 'warning', IN_PROGRESS: '', QUOTED: 'success', CONFIRMED: 'success' } as Record<string, string>)[row.status] || 'info')" size="small">
              {{ ({ PENDING: '待报价', IN_PROGRESS: '报价中', QUOTED: '已报价', CONFIRMED: '已确认' } as Record<string, string>)[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="router.push(`/rd/quotations/${row.id}`)">查看详情</el-button>
            <el-button type="info" link size="small" @click="router.push(`/rd/quotations/${row.id}/three-price`)">三价对比</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="pagination.total > pagination.size"
        style="margin-top:16px;justify-content:flex-end"
        :current-page="pagination.page + 1" :page-size="pagination.size" :total="pagination.total"
        layout="total, prev, pager, next"
        @current-change="(p: number) => { pagination.page = p - 1; loadData(); }"
      />
    </el-card>

    <!-- 新建研发需求 -->
    <el-dialog v-model="requestDialogVisible" title="新建研发需求" width="520px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="客户名称" required><el-input v-model="requestForm.customerName" /></el-form-item>
        <el-form-item label="联系方式"><el-input v-model="requestForm.customerContact" /></el-form-item>
        <el-form-item label="紧急程度">
          <el-select v-model="requestForm.urgency" style="width:100%">
            <el-option label="紧急" value="HIGH" /><el-option label="普通" value="MEDIUM" /><el-option label="低" value="LOW" />
          </el-select>
        </el-form-item>
        <el-form-item label="需求描述" required><el-input v-model="requestForm.requirements" type="textarea" :rows="4" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="requestDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreateRequest">提交</el-button>
      </template>
    </el-dialog>

    <!-- 新建样品 -->
    <el-dialog v-model="sampleDialogVisible" title="新建样品" width="600px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="客户名称"><el-input v-model="sampleForm.customerName" placeholder="客户名称" /></el-form-item>
        <el-form-item label="业务员">
          <el-select v-model="sampleForm.salesperson" placeholder="选择业务员" filterable allow-create clearable style="width: 100%">
            <el-option v-for="u in salespersonList" :key="u.id" :label="u.fullName || u.username" :value="u.fullName || u.username" />
          </el-select>
        </el-form-item>
        <el-form-item label="样品名称" required><el-input v-model="sampleForm.name" /></el-form-item>
        <el-form-item label="成品规格"><el-input v-model="sampleForm.specification" placeholder="如 200g/盒, 310g*42袋/箱" /></el-form-item>
        <el-form-item label="产品级别">
          <el-select v-model="sampleForm.productLevel" placeholder="选择产品级别" clearable style="width: 100%">
            <el-option label="A" value="A" />
            <el-option label="B" value="B" />
            <el-option label="C" value="C" />
            <el-option label="D" value="D" />
          </el-select>
        </el-form-item>
        <el-form-item label="储存方式">
          <el-select v-model="sampleForm.storageMethod" placeholder="选择储存方式" clearable style="width: 100%">
            <el-option label="冷冻" value="冷冻" />
            <el-option label="冷藏" value="冷藏" />
            <el-option label="常温" value="常温" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联需求"><el-input v-model="sampleForm.rdRequestId" placeholder="研发需求ID（可选）" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sampleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreateSample">创建</el-button>
      </template>
    </el-dialog>

    <!-- 追踪记录 -->
    <el-dialog v-model="trackingDialogVisible" title="追踪记录" width="700px" destroy-on-close>
      <el-table v-if="trackingRecords.length > 0" :data="trackingRecords" border size="small" style="margin-bottom: 16px">
        <el-table-column prop="date" label="日期" width="120" />
        <el-table-column prop="content" label="追踪内容" min-width="200" show-overflow-tooltip />
        <el-table-column prop="attachment" label="附件" width="100">
          <template #default="{ row }">
            <a v-if="row.attachment" :href="row.attachment" target="_blank" style="color: #409eff">查看</a>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="recorder" label="记录员" width="100" />
      </el-table>
      <el-empty v-else description="暂无追踪记录" :image-size="60" />

      <el-divider>添加追踪记录</el-divider>
      <el-form label-width="80px">
        <el-form-item label="日期">
          <el-date-picker v-model="newTracking.date" type="date" value-format="YYYY-MM-DD" style="width: 100%" disabled />
        </el-form-item>
        <el-form-item label="追踪内容" required>
          <el-input v-model="newTracking.content" type="textarea" :rows="3" placeholder="输入追踪内容..." />
        </el-form-item>
        <el-form-item label="附件">
          <el-input v-model="newTracking.attachment" placeholder="附件链接URL（可选）" />
        </el-form-item>
        <el-form-item label="记录员">
          <el-input v-model="newTracking.recorder" disabled />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="trackingDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="submitting" @click="addTrackingRecord" :disabled="!newTracking.content">添加记录</el-button>
      </template>
    </el-dialog>
  </div>
</template>
