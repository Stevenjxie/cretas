<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Plus, Search, Refresh } from '@element-plus/icons-vue';
import CanvasDynamicFields from '@/components/canvas/CanvasDynamicFields.vue';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import type { TableRow } from '@/types/api';
import { TableFooter } from '@/components/list';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('quality'));
const { goCreate } = useCreateAndReturn();

const loading = ref(false);
const submitting = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });

// U-FOOTER-1
const summaryRequest = computed<ListSummaryRequest>(() => ({ filterConditions: {} }));
const { summary: footerSummary, loading: footerLoading } = useListSummary('qualityInspection', summaryRequest);
const searchKeyword = ref('');
const filterResult = ref('');

// 新建对话框
const dialogVisible = ref(false);
const dialogForm = ref({
  batchId: '',
  sampleSize: null as number | null,
  passCount: null as number | null,
  failCount: null as number | null,
  result: '' as string,
  notes: '',
  customFields: {} as TableRow,
});

// Issue #745: 抽样/合格/不合格 联动自动平衡 — 任何 2 个有值, 第 3 个自动算.
// lastEdited 记录最近用户编辑哪个字段, 改其他字段时不反推它.
const lastEdited = ref<'sample' | 'pass' | 'fail' | null>(null);

function onSampleChange(v: number | null | undefined) {
  lastEdited.value = 'sample';
  const sample = Number(v);
  if (!isFinite(sample) || sample < 0) return;
  const pass = dialogForm.value.passCount;
  const fail = dialogForm.value.failCount;
  if (pass != null && fail == null) {
    dialogForm.value.failCount = Math.max(0, sample - Number(pass));
  } else if (fail != null && pass == null) {
    dialogForm.value.passCount = Math.max(0, sample - Number(fail));
  }
}

function onPassChange(v: number | null | undefined) {
  lastEdited.value = 'pass';
  const pass = Number(v);
  if (!isFinite(pass) || pass < 0) return;
  const sample = dialogForm.value.sampleSize;
  const fail = dialogForm.value.failCount;
  if (sample != null && fail == null) {
    dialogForm.value.failCount = Math.max(0, Number(sample) - pass);
  } else if (sample == null && fail != null) {
    dialogForm.value.sampleSize = pass + Number(fail);
  } else if (sample != null && fail != null) {
    if ((lastEdited.value as 'sample' | 'pass' | 'fail' | null) !== 'sample') {
      dialogForm.value.sampleSize = pass + Number(fail);
    }
  }
}

function onFailChange(v: number | null | undefined) {
  lastEdited.value = 'fail';
  const fail = Number(v);
  if (!isFinite(fail) || fail < 0) return;
  const sample = dialogForm.value.sampleSize;
  const pass = dialogForm.value.passCount;
  if (sample != null && pass == null) {
    dialogForm.value.passCount = Math.max(0, Number(sample) - fail);
  } else if (sample == null && pass != null) {
    dialogForm.value.sampleSize = Number(pass) + fail;
  } else if (sample != null && pass != null) {
    if ((lastEdited.value as 'sample' | 'pass' | 'fail' | null) !== 'sample') {
      dialogForm.value.sampleSize = Number(pass) + fail;
    }
  }
}

// 派生显示: 是否一致 + 提示文案
// Issue #825 fix (2026-05-17): under-balance (sum<sample) was previously ok:true
// with informational "剩余 N 件" message — user could still submit incomplete
// inspections, leaving data integrity gap (实际抽样数 != 报告的抽样数).
// Now blocks submit with sticky-style toast prompting user to fill remaining
// or reduce sampleSize. Matches over-balance (sum>sample) behavior introduced
// in #745. Customer expects 强制平衡 (May 7 data-integrity discussion spirit).
const balanceStatus = computed<{ ok: boolean; msg: string }>(() => {
  const s = dialogForm.value.sampleSize;
  const p = dialogForm.value.passCount;
  const f = dialogForm.value.failCount;
  if (s == null || p == null || f == null) {
    return { ok: true, msg: '填入任意两个,第三个自动计算' };
  }
  const sum = Number(p) + Number(f);
  if (sum > Number(s)) {
    return { ok: false, msg: `合格 ${p} + 不合格 ${f} = ${sum} > 抽样 ${s} (请调整)` };
  }
  if (sum < Number(s)) {
    return { ok: false, msg: `请填满合格+不合格数 (剩余 ${Number(s) - sum} 件未分类)` };
  }
  return { ok: true, msg: '数字一致' };
});

// 生产批次列表（用于下拉选择）
const productionBatches = ref<TableRow[]>([]);

async function loadProductionBatches() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/processing/batches`, { params: { size: 200 } });
    if (res.success && res.data) {
      productionBatches.value = res.data.content || res.data || [];
    }
  } catch { /* silent */ }
}

// 详情抽屉
const detailVisible = ref(false);
const detailData = ref<TableRow | null>(null);

function isPassResult(result: unknown): boolean {
  return result === 'PASS' || result === 'PASSED';
}

function getResultLabel(result: unknown): string {
  if (isPassResult(result)) return '合格';
  if (result === 'CONDITIONAL') return '有条件放行';
  if (result === 'PENDING') return '待检';
  if (result === 'FAIL' || result === 'FAILED') return '不合格';
  return String(result ?? '-');
}

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/processing/quality/inspections`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchKeyword.value || undefined,
        result: filterResult.value || undefined
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载质检记录失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchKeyword.value = '';
  filterResult.value = '';
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}

function handleCreate() {
  dialogForm.value = { batchId: '', sampleSize: null, passCount: null, failCount: null, result: '', notes: '', customFields: {} as TableRow };
  dialogVisible.value = true;
  loadProductionBatches();
}

async function submitCreateForm() {
  if (!dialogForm.value.batchId) {
    ElMessage.warning('请选择生产批次');
    return;
  }
  if (!dialogForm.value.sampleSize || dialogForm.value.sampleSize <= 0) {
    ElMessage.warning('请输入抽样数量');
    return;
  }
  if (!balanceStatus.value.ok) {
    // Issue #825 fix (2026-05-17): use .error (globally overridden to sticky
    // duration:0 + showClose in main.ts) so user can't dismiss accidentally
    // while still reading the count. .warning auto-dismisses in 3s and the
    // 剩余 N 数字 was easy to miss.
    ElMessage.error(balanceStatus.value.msg);
    return;
  }
  if (!dialogForm.value.result) {
    ElMessage.warning('请选择检验结果');
    return;
  }
  submitting.value = true;
  try {
    const body: TableRow = {
      sampleSize: dialogForm.value.sampleSize,
      passCount: dialogForm.value.passCount,
      failCount: dialogForm.value.failCount,
      result: dialogForm.value.result,
      notes: dialogForm.value.notes || undefined
    };
    // 从 localStorage 获取当前用户 ID 作为 inspectorId
    try {
      const userStr = localStorage.getItem('cretas_user');
      if (userStr) {
        const user = JSON.parse(userStr);
        if (user.id) body.inspectorId = user.id;
      }
    } catch { /* ignore */ }
    const response = await post(
      `/${factoryId.value}/processing/quality/inspections?batchId=${encodeURIComponent(dialogForm.value.batchId)}`,
      body
    );
    if (response.success) {
      ElMessage.success('质检记录已创建');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '创建失败');
    }
  } catch {
    ElMessage.error('创建失败，请检查网络连接');
  } finally {
    submitting.value = false;
  }
}

function showDetail(row: TableRow) {
  detailData.value = row;
  detailVisible.value = true;
}
</script>

<template>
  <CanvasAwareWrapper module-code="quality_inspection">
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">质检记录管理</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">新建质检</el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索质检编号/批次号"
          :prefix-icon="Search"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-select v-model="filterResult" placeholder="检测结果" clearable style="width: 150px">
          <el-option label="合格" value="PASS" />
          <el-option label="不合格" value="FAIL" />
          <el-option label="有条件放行" value="CONDITIONAL" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column label="质检编号" width="160">
          <template #default="{ row }">
            {{ row.id ? row.id.substring(0, 8).toUpperCase() : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="notes" label="批次/备注" min-width="180" show-overflow-tooltip />
        <el-table-column prop="sampleSize" label="抽样数" width="90" align="center" />
        <el-table-column prop="inspectorId" label="质检员ID" width="100" align="center" />
        <el-table-column prop="result" label="检测结果" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="isPassResult(row.result) ? 'success' : (row.result === 'CONDITIONAL' ? 'warning' : 'danger')" size="small">
              {{ getResultLabel(row.result) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="qualityGrade" label="等级" width="70" align="center" />
        <el-table-column label="合格率" width="90" align="center">
          <template #default="{ row }">
            {{ row.passRate != null ? row.passRate.toFixed(1) + '%' : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="inspectionDate" label="检测日期" width="130" />
        <el-table-column label="操作" width="120" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="showDetail(row)">查看详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <TableFooter
        :stats="footerSummary?.stats ?? []"
        :loading="footerLoading"
        :show-export="false"
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前质检${formatSummaryForAI(footerSummary)}`, duration: 8000, showClose: true })"
      />

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 新建质检对话框 -->
    <el-dialog v-model="dialogVisible" title="新建质检记录" width="480px" :close-on-click-modal="false">
      <el-form :model="dialogForm" label-width="90px">
        <el-form-item label="生产批次" required>
          <el-select v-model="dialogForm.batchId" placeholder="选择生产批次" filterable style="width: 100%">
            <el-option
              v-for="b in productionBatches"
              :key="String(b.id)"
              :label="`${b.batchNumber || b.id}${b.productName ? ' - ' + b.productName : ''}`"
              :value="String(b.id)"
            />
          </el-select>
          <UpstreamMissingHint v-if="productionBatches.length === 0" description="暂无生产批次，无法发起质检" target-module="production" require-write action-text="去生产管理" contact-text="请联系生产部门先开工生产批次" @action="goCreate('/production/plans')" />
        </el-form-item>
        <!-- Issue #745: 抽样/合格/不合格 联动自动平衡 - 填两个第三自动算 -->
        <el-form-item label="抽样数量" required>
          <el-input-number
            v-model="dialogForm.sampleSize"
            :min="0"
            :controls="false"
            style="width: 100%"
            @change="onSampleChange" />
        </el-form-item>
        <el-form-item label="合格数" required>
          <el-input-number
            v-model="dialogForm.passCount"
            :min="0"
            :controls="false"
            style="width: 100%"
            @change="onPassChange" />
        </el-form-item>
        <el-form-item label="不合格数" required>
          <el-input-number
            v-model="dialogForm.failCount"
            :min="0"
            :controls="false"
            style="width: 100%"
            @change="onFailChange" />
        </el-form-item>
        <el-form-item label=" ">
          <el-tag
            :type="balanceStatus.ok ? 'success' : 'danger'"
            effect="plain"
            size="small">
            {{ balanceStatus.msg }}
          </el-tag>
        </el-form-item>
        <el-form-item label="检验结果" required>
          <el-select v-model="dialogForm.result" placeholder="选择检验结果" style="width: 100%">
            <el-option label="合格" value="PASS" />
            <el-option label="不合格" value="FAIL" />
            <el-option label="待复检" value="PENDING" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dialogForm.notes" type="textarea" :rows="2" />
        </el-form-item>
        <CanvasDynamicFields v-model="dialogForm.customFields" module-code="quality_inspection" />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreateForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="质检记录详情" size="420px">
      <el-descriptions :column="1" border v-if="detailData">
        <el-descriptions-item label="质检编号">{{ detailData.id ? detailData.id.substring(0, 8).toUpperCase() : '-' }}</el-descriptions-item>
        <el-descriptions-item label="批次/备注">{{ detailData.notes || '-' }}</el-descriptions-item>
        <el-descriptions-item label="抽样数">{{ detailData.sampleSize ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="质检员ID">{{ detailData.inspectorId ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="检测结果">
          <el-tag :type="isPassResult(detailData.result) ? 'success' : (detailData.result === 'CONDITIONAL' ? 'warning' : 'danger')" size="small">
            {{ getResultLabel(detailData.result) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="等级">{{ detailData.qualityGrade || '-' }}</el-descriptions-item>
        <el-descriptions-item label="合格率">{{ detailData.passRate != null ? detailData.passRate.toFixed(1) + '%' : '-' }}</el-descriptions-item>
        <el-descriptions-item label="检测日期">{{ detailData.inspectionDate || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
  </CanvasAwareWrapper>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  }

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;

    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-color-primary, #303133);
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, #909399);
    }
  }
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.el-table {
  flex: 1;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}
</style>
