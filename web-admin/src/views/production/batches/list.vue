<script setup lang="ts">
import { ref, onMounted, computed, h } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Plus, Search, Refresh, ChatDotRound } from '@element-plus/icons-vue';
import { formatDateTimeCell } from '@/utils/tableFormatters';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import type { TableRow } from '@/types/api';
import { RowActionMenu } from '@/components/list';
import { computeRowActions } from '@/composables/useRowActions';
import { safePrint } from '@/api/printApi';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import { PROCESS_TASK_CONFIG } from '@/components/ai-entry/types';
import { useTableColumnWidth } from '@/composables/useTableColumnWidth';

const router = useRouter();

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

type BatchTableRow = TableRow & {
  isSyntheticPlanGroup?: boolean;
  children?: BatchTableRow[];
  sourcePlanId?: string | null;
  sourcePlanNumber?: string | null;
  sourceProcessOrder?: number | null;
  sourceProcessCode?: string | null;
};

function batchRowClassName({ row }: { row: BatchTableRow }): string {
  return row.isSyntheticPlanGroup ? 'plan-group-row' : '';
}

function rowActionsFor(row: TableRow) {
  // #751: 删除 dropdown 中的 'view-detail' (页面已有独立"查看" button, 避免重复 button 跳同页)
  // fool-proof-design Rule 5: 'edit'/'lock' 没有真实后端能力 (无批次编辑表单/接口,
  // 无锁定 API) — 之前点了才弹 toast (click-then-toast, dead-end)。改用
  // forceDisabled: 灰显 + hover 提示原因, 用户不用点一次才知道不可用。
  const all = computeRowActions(
    'processTask',
    { status: String(row.status || 'IN_PROGRESS'), id: String(row.id || '') },
    {
      canViewPrice: canViewPrice.value,
      forceDisabled: {
        edit: '批次编辑功能暂未上线 (无独立编辑表单)，如需修改请联系管理员',
        lock: '锁定功能后端 API 对接中，暂不可用',
      },
    }
  );
  return all.filter((a) => a.id !== 'view-detail');
}
function handleRowActionClick(actionId: string, row: TableRow) {
  switch (actionId) {
    case 'view-detail': router.push(`/production/batches/${row.id}`); break;
    // #751: 编辑跳详情页 + ?mode=edit hint — 'edit' 已在 rowActionsFor() 用
    // forceDisabled 灰显 (无编辑表单), 这个 case 保留只作防御 (元素被禁用后
    // el-dropdown 不会派发 command)。
    case 'edit': router.push(`/production/batches/${row.id}?mode=edit`); break;
    case 'print-pdf': void safePrint('production-task', factoryId.value, String(row.id), { fileName: `生产批次_${row.batchNumber || row.id}` }); break;
    case 'lock':
      // 'lock' 已在 rowActionsFor() 用 forceDisabled 灰显; 保留仅作防御。
      ElMessage.info('锁定后该批次将不再允许修改数量/报工记录，进入封存状态。后端 API 正在对接中，暂时不可用。');
      break;
    default: ElMessage.warning(`该操作暂不支持: ${actionId}`);
  }
}
// AI 智能创建生产批次 (Day 9, Issue #780.3)
const aiEntryVisible = ref(false);
function openAiForRow(_row: TableRow) {
  // Row-context AI is future scope; current Day 9 opens drawer for CREATE flow
  aiEntryVisible.value = true;
}
function openAiCreate() {
  aiEntryVisible.value = true;
}
async function handleAiFill(params: Record<string, unknown>) {
  // Load product types if not already cached so name-matching can work
  if (productTypes.value.length === 0 && factoryId.value) {
    try {
      const res = await get(`/${factoryId.value}/product-types/active`);
      if (res.success) {
        productTypes.value = res.data || [];
      }
    } catch (e: unknown) {
      console.error('加载产品类型失败:', e);
    }
  }

  const productName = String(params.productTypeName || '');
  const matched = productTypes.value.find(
    (p) => {
      const name = String(p.name || p.productName || '');
      return name.includes(productName) || productName.includes(name);
    }
  );

  createForm.value = {
    batchNumber: generateBatchNumber(),
    productTypeId: matched ? String(matched.id) : '',
    plannedQuantity: Number(params.plannedQuantity || 0) || null,
    unit: String(params.unit || 'kg'),
    notes: String(params.notes || ''),
    isTrial: false,
    trialSampleId: '',
  };

  if (!matched && productName) {
    ElMessage.warning(`未找到匹配的产品类型 "${productName}"，请手动选择`);
  }

  createDialogVisible.value = true;
}

const loading = ref(false);
const tableData = ref<BatchTableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchForm = ref({
  batchNumber: '',
  status: ''
});

function sumQuantity(rows: BatchTableRow[], key: string) {
  let total = 0;
  let hasValue = false;
  for (const row of rows) {
    const value = Number(row[key]);
    if (Number.isFinite(value)) {
      total += value;
      hasValue = true;
    }
  }
  return hasValue ? Number(total.toFixed(3)) : null;
}

const displayTableData = computed<BatchTableRow[]>(() => {
  const result: BatchTableRow[] = [];
  const groups = new Map<string, BatchTableRow>();

  for (const row of tableData.value) {
    const sourcePlanKey = row.batchType === 'CLERK_WIP'
      ? String(row.sourcePlanId || row.sourcePlanNumber || '')
      : '';

    if (!sourcePlanKey) {
      result.push(row);
      continue;
    }

    let group = groups.get(sourcePlanKey);
    if (!group) {
      group = {
        id: `source-plan-${sourcePlanKey}`,
        batchNumber: row.sourcePlanNumber || row.sourcePlanId || '未归属生产计划',
        productTypeName: row.productTypeName || row.productName || '',
        status: 'SOURCE_PLAN_GROUP',
        isSyntheticPlanGroup: true,
        sourcePlanId: row.sourcePlanId,
        sourcePlanNumber: row.sourcePlanNumber,
        children: []
      };
      groups.set(sourcePlanKey, group);
      result.push(group);
    }

    group.children?.push(row);
  }

  for (const group of groups.values()) {
    const children = group.children || [];
    children.sort((a, b) => {
      const orderA = Number(a.sourceProcessOrder ?? Number.MAX_SAFE_INTEGER);
      const orderB = Number(b.sourceProcessOrder ?? Number.MAX_SAFE_INTEGER);
      if (orderA !== orderB) return orderA - orderB;
      return String(a.batchNumber || '').localeCompare(String(b.batchNumber || ''));
    });
    group.plannedQuantity = sumQuantity(children, 'plannedQuantity');
    group.actualQuantity = sumQuantity(children, 'actualQuantity');
  }

  return result;
});

/**
 * 排序表头 —— 与出成率总览 (YieldCardTable) 同一套做法, per PR #1991。
 *
 * el-table 自带的 `sortable` 只渲染一对没有名字的箭头, 屏幕阅读器读到的是
 * 「一串没名字的控件」。这里换成一个真按钮, 自带 aria-label 说明按哪一列排;
 * 视觉上完全继承单元格字体与颜色, 不额外套壳 (客户原话:「每个表头套了独立边框盒子」)。
 */
function sortableHeader({ column }: { column: { label?: string } }) {
  const label = column.label || '本列';
  return h(
    'button',
    {
      type: 'button',
      class: 'batch-sort-trigger',
      'aria-label': `按${label}排序，连续操作可切换升序和降序`,
      title: '点击切换升序和降序',
    },
    label,
  );
}

/**
 * 列宽记忆 (客户 Sheet Row 13: 调好的列宽刷新就没了)。
 * defaults 即这张表原本写死的宽度 —— 没拖过时渲染结果与以前一致;
 * `计划/批次号` 与 `产品类型` 用 min-width 自适应, 刻意不入 defaults。
 */
const BATCH_LIST_DEFAULT_COLUMN_WIDTHS = {
  plannedQuantity: 120,
  actualQuantity: 120,
  sourceProcess: 160,
  status: 120,
  supervisorName: 120,
  createdAt: 190,
} as const;

const { columnWidth, handleHeaderDragend, resetColumnWidths, hasStoredColumnWidths } = useTableColumnWidth({
  pageKey: 'production.batches.list',
  scope: factoryId,
  defaults: BATCH_LIST_DEFAULT_COLUMN_WIDTHS,
});

type TableSortRow = Record<string, unknown>;

function compareNullableNumber(left: TableSortRow, right: TableSortRow, prop: string) {
  const leftValue = Number(left[prop]);
  const rightValue = Number(right[prop]);
  const leftValid = Number.isFinite(leftValue);
  const rightValid = Number.isFinite(rightValue);
  if (!leftValid && !rightValid) return 0;
  if (!leftValid) return 1;
  if (!rightValid) return -1;
  return leftValue - rightValue;
}

function compareNullableDate(left: TableSortRow, right: TableSortRow, prop: string) {
  const leftValue = Date.parse(String(left[prop] || ''));
  const rightValue = Date.parse(String(right[prop] || ''));
  const leftValid = Number.isFinite(leftValue);
  const rightValid = Number.isFinite(rightValue);
  if (!leftValid && !rightValid) return 0;
  if (!leftValid) return 1;
  if (!rightValid) return -1;
  return leftValue - rightValue;
}

function productNameOf(row: BatchTableRow) {
  return String(row.productTypeName || row.productName || row.productTypeId || '-');
}

function sourceProcessOf(row: BatchTableRow) {
  if (row.isSyntheticPlanGroup) return '生产计划';
  if (row.batchType === 'CLERK_WIP' && row.sourceProcessOrder) {
    return `第${row.sourceProcessOrder}道${row.sourceProcessCode ? ` / ${row.sourceProcessCode}` : ''}`;
  }
  return '-';
}

const productFilters = computed(() => Array.from(new Set(tableData.value.map(productNameOf)))
  .filter((value) => value !== '-')
  .sort((a, b) => a.localeCompare(b, 'zh-CN'))
  .map((value) => ({ text: value, value })));

const sourceProcessFilters = computed(() => Array.from(new Set(displayTableData.value.flatMap((row) => [
  sourceProcessOf(row),
  ...(row.children || []).map(sourceProcessOf)
])))
  .sort((a, b) => a.localeCompare(b, 'zh-CN'))
  .map((value) => ({ text: value, value })));

const supervisorFilters = computed(() => Array.from(new Set(tableData.value
  .map((row) => String(row.supervisorName || '-'))))
  .sort((a, b) => a.localeCompare(b, 'zh-CN'))
  .map((value) => ({ text: value, value })));

const statusFilters = [
  { text: '待生产', value: 'PLANNED' },
  { text: '生产中', value: 'IN_PROGRESS' },
  { text: '已暂停', value: 'PAUSED' },
  { text: '已完成', value: 'COMPLETED' },
  { text: '已取消', value: 'CANCELLED' },
  { text: '计划归组', value: 'SOURCE_PLAN_GROUP' }
];

function openSourcePlan(row: BatchTableRow) {
  if (row.sourcePlanId) {
    router.push({ path: '/production/plans', query: { openProcessEntryPlan: String(row.sourcePlanId) } });
    return;
  }
  router.push('/production/plans');
}

// 创建批次
const createDialogVisible = ref(false);
const creating = ref(false);
const productTypes = ref<TableRow[]>([]);
const createForm = ref({
  batchNumber: '',
  productTypeId: '',
  plannedQuantity: null as number | null,
  unit: 'kg',
  notes: '',
  // SP10: 试制批次标记
  isTrial: false,
  trialSampleId: '',
});

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/processing/batches-with-clerk-wip`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        ...searchForm.value
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error: any) {
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

function handleReset() {
  searchForm.value = { batchNumber: '', status: '' };
  handleSearch();
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

function generateBatchNumber() {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  const rand = Math.random().toString(36).substring(2, 7).toUpperCase();
  return `PB-${y}${m}${d}-${rand}`;
}

async function handleCreate() {
  createForm.value = { batchNumber: generateBatchNumber(), productTypeId: '', plannedQuantity: null, unit: 'kg', notes: '', isTrial: false, trialSampleId: '' };
  createDialogVisible.value = true;
  // Load product types for dropdown
  if (productTypes.value.length === 0 && factoryId.value) {
    try {
      const res = await get(`/${factoryId.value}/product-types/active`);
      if (res.success) {
        productTypes.value = res.data || [];
      }
    } catch (e: unknown) {
      console.error('加载产品类型失败:', e);
      const err = e as { actionHint?: unknown };
      if (!err?.actionHint) ElMessage.error('加载产品类型失败');
    }
  }
}

async function submitCreate() {
  if (!factoryId.value) return;
  if (!createForm.value.batchNumber) {
    ElMessage.warning('请输入批次号');
    return;
  }
  if (!createForm.value.productTypeId) {
    ElMessage.warning('请选择产品类型');
    return;
  }
  if (!createForm.value.plannedQuantity || createForm.value.plannedQuantity <= 0) {
    ElMessage.warning('请输入有效的计划数量');
    return;
  }

  const selectedProduct = productTypes.value.find((p) => p.id === createForm.value.productTypeId);
  creating.value = true;
  try {
    const response = await post(`/${factoryId.value}/processing/batches`, {
      batchNumber: createForm.value.batchNumber,
      productTypeId: createForm.value.productTypeId,
      productName: selectedProduct?.name || selectedProduct?.productName || '',
      plannedQuantity: createForm.value.plannedQuantity,
      quantity: createForm.value.plannedQuantity,
      unit: createForm.value.unit,
      notes: createForm.value.notes,
      // SP10: 试制批次字段
      isTrial: createForm.value.isTrial || false,
      trialSampleId: createForm.value.isTrial && createForm.value.trialSampleId ? createForm.value.trialSampleId : null
    });
    if (response.success) {
      ElMessage.success('批次创建成功');
      createDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '创建失败');
    }
  } catch (error: unknown) {
    const e = error as { actionHint?: unknown; response?: { data?: { message?: string } } };
    if (!e?.actionHint) ElMessage.error(e?.response?.data?.message || '创建失败');
  } finally {
    creating.value = false;
  }
}

// ProductionBatchStatus.java real values: PLANNED/PLANNING/IN_PROGRESS/PRODUCING/
// PAUSED/COMPLETED/CANCELLED. PENDING kept for back-compat display only (not a
// real value); PLANNING/PRODUCING are legacy aliases of PLANNED/IN_PROGRESS.
function getStatusType(status: string) {
  const map: Record<string, string> = {
    PLANNED: 'info',
    PLANNING: 'info',
    PENDING: 'info',
    IN_PROGRESS: 'warning',
    PRODUCING: 'warning',
    PAUSED: 'warning',
    COMPLETED: 'success',
    CANCELLED: 'danger'
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    PLANNED: '待生产',
    PLANNING: '待生产',
    PENDING: '待生产',
    IN_PROGRESS: '生产中',
    PRODUCING: '生产中',
    PAUSED: '已暂停',
    COMPLETED: '已完成',
    CANCELLED: '已取消'
  };
  return map[status?.toUpperCase()] || status;
}
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="生产批次"
      here="已开工的实际「批次」（IN_PROGRESS / COMPLETED，记录实际产量、消耗、报工）"
      other-name="生产管理 → 生产计划"
      other="未来要做什么的「计划」（PENDING / 待开工状态，可调整数量、日期）"
      other-path="/production/plans"
    />
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">生产批次列表</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <!-- 列宽已被记住时才出现 —— 拖乱了要有一条明确的退路 (fool-proof Rule 5) -->
            <el-button v-if="hasStoredColumnWidths" link type="primary" @click="resetColumnWidths">
              恢复默认列宽
            </el-button>
            <el-button v-if="canWrite" type="success" :icon="ChatDotRound" @click="openAiCreate">
              AI 创建批次
            </el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">
              创建批次
            </el-button>
          </div>
        </div>
      </template>

      <!-- 搜索区域 -->
      <div class="search-bar">
        <el-input
          v-model="searchForm.batchNumber"
          placeholder="批次号"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
        <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 150px">
          <!-- Bug fix (module-verify 2026-07-03, same root cause as scheduling/plans/create.vue):
               ProductionBatchStatus 枚举没有 PENDING, 传给后端会 400 (IllegalArgumentException).
               PLANNED 才是真实"待生产"状态值。 -->
          <el-option label="待生产" value="PLANNED" />
          <el-option label="生产中" value="IN_PROGRESS" />
          <el-option label="已暂停" value="PAUSED" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleReset">重置</el-button>
      </div>

      <!-- 数据表格 -->
      <el-table
        :data="displayTableData"
        v-loading="loading"
        row-key="id"
        :tree-props="{ children: 'children' }"
        default-expand-all
        empty-text="暂无数据"
        stripe
        border
        table-layout="fixed"
        class="batch-data-table"
        :row-class-name="batchRowClassName"
        style="width: 100%"
        @header-dragend="handleHeaderDragend"
      >
        <el-table-column
          prop="batchNumber"
          label="计划/批次号"
          min-width="280"
          :width="columnWidth('batchNumber')"
          sortable
          :render-header="sortableHeader"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <div v-if="row.isSyntheticPlanGroup" class="plan-group-cell">
              <span class="plan-group-title">生产计划 {{ row.sourcePlanNumber || row.sourcePlanId || '-' }}</span>
              <el-tag size="small" type="success">已小结归组</el-tag>
            </div>
            <span v-else>{{ row.batchNumber || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="productTypeName"
          label="产品类型"
          min-width="260"
          :width="columnWidth('productTypeName')"
          show-overflow-tooltip
          :filters="productFilters"
          :filter-method="(value: string, row: BatchTableRow) => productNameOf(row) === value"
        >
          <template #default="{ row }">
            <span v-if="row.isSyntheticPlanGroup">{{ row.children?.length || 0 }} 条已小结批次</span>
            <span v-else>{{ row.productTypeName || row.productName || row.productTypeId || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="plannedQuantity"
          label="计划数量"
          :width="columnWidth('plannedQuantity')"
          align="right"
          sortable
          :render-header="sortableHeader"
          :sort-method="(a: TableSortRow, b: TableSortRow) => compareNullableNumber(a, b, 'plannedQuantity')"
        />
        <el-table-column
          prop="actualQuantity"
          label="实际数量"
          :width="columnWidth('actualQuantity')"
          align="right"
          sortable
          :render-header="sortableHeader"
          :sort-method="(a: TableSortRow, b: TableSortRow) => compareNullableNumber(a, b, 'actualQuantity')"
        />
        <el-table-column
          label="来源工序"
          column-key="sourceProcess"
          :width="columnWidth('sourceProcess')"
          align="center"
          :filters="sourceProcessFilters"
          :filter-method="(value: string, row: BatchTableRow) => sourceProcessOf(row) === value"
        >
          <template #default="{ row }">
            <span v-if="row.isSyntheticPlanGroup">生产计划</span>
            <span v-else-if="row.batchType === 'CLERK_WIP' && row.sourceProcessOrder">
              {{ `第${row.sourceProcessOrder}道` }}{{ row.sourceProcessCode ? ` / ${row.sourceProcessCode}` : '' }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="status"
          label="状态"
          :width="columnWidth('status')"
          align="center"
          :filters="statusFilters"
          :filter-method="(value: string, row: BatchTableRow) => String(row.status || '').toUpperCase() === value"
        >
          <template #default="{ row }">
            <el-tag v-if="row.isSyntheticPlanGroup" type="info" size="small">
              计划归组
            </el-tag>
            <el-tag v-else :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="supervisorName"
          label="负责人"
          :width="columnWidth('supervisorName')"
          :filters="supervisorFilters"
          :filter-method="(value: string, row: BatchTableRow) => String(row.supervisorName || '-') === value"
        >
          <template #default="{ row }">{{ row.isSyntheticPlanGroup ? '-' : (row.supervisorName || '-') }}</template>
        </el-table-column>
        <el-table-column
          prop="createdAt"
          label="创建时间"
          :width="columnWidth('createdAt')"
          sortable
          :render-header="sortableHeader"
          :sort-method="(a: TableSortRow, b: TableSortRow) => compareNullableDate(a, b, 'createdAt')"
          :formatter="formatDateTimeCell"
        />
        <el-table-column label="操作" width="220" fixed="right" align="center">
          <template #default="{ row }">
            <template v-if="row.isSyntheticPlanGroup">
              <el-button type="primary" link size="small" @click="openSourcePlan(row)">查看计划</el-button>
            </template>
            <template v-else>
            <!-- #751: 查看 vs 编辑 区分 — 详情页消费 ?mode=edit 决定 read-only / editable.
                 fool-proof-design Rule 5: 批次没有独立编辑表单, 之前点了才发现是死路
                 (click-then-toast) — 灰显 + title 说明原因, 而不是让用户点一次才知道。 -->
            <el-button type="primary" link size="small" @click="router.push(`/production/batches/${row.id}`)">查看</el-button>
            <el-button
              v-if="canWrite"
              type="warning"
              link
              size="small"
              disabled
              title="批次编辑功能暂未上线 (无独立编辑表单)"
            >编辑</el-button>
            <RowActionMenu
              :actions="rowActionsFor(row)"
              button-label="更多"
              @action-click="(id: string) => handleRowActionClick(id, row)"
              @ai-trigger="() => openAiForRow(row)"
            />
            </template>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
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

    <!-- 创建批次对话框 -->
    <el-dialog v-model="createDialogVisible" title="创建生产批次" width="500px" :close-on-click-modal="false" destroy-on-close>
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="批次号" required>
          <el-input v-model="createForm.batchNumber" placeholder="自动生成，可手动修改" />
        </el-form-item>
        <el-form-item label="产品类型" required>
          <el-select v-model="createForm.productTypeId" placeholder="请选择产品类型" filterable style="width: 100%">
            <el-option
              v-for="pt in productTypes"
              :key="pt.id"
              :label="pt.name || pt.productName"
              :value="pt.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="计划数量" required>
          <el-input-number v-model="createForm.plannedQuantity" :min="1" :precision="2" style="width: 200px" />
          <el-select v-model="createForm.unit" style="width: 80px; margin-left: 8px">
            <el-option label="kg" value="kg" />
            <el-option label="箱" value="箱" />
            <el-option label="件" value="件" />
            <el-option label="吨" value="吨" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.notes" type="textarea" :rows="3" placeholder="可选备注信息" />
        </el-form-item>
        <!-- SP10: 试制批次 -->
        <el-form-item label="试制批次">
          <el-checkbox v-model="createForm.isTrial">标记为试制批次（小试/中试）</el-checkbox>
        </el-form-item>
        <el-form-item v-if="createForm.isTrial" label="关联样品">
          <el-input
            v-model="createForm.trialSampleId"
            placeholder="输入研发样品 ID（可选）"
            clearable
            style="width:280px"
          />
          <el-text type="info" style="margin-left:8px;font-size:12px">对应 RD 样品编号</el-text>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- AI 智能创建生产批次抽屉 (Day 9, Issue #780.3) -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="PROCESS_TASK_CONFIG"
      @fill-form="handleAiFill"
    />
  </div>
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

/*
  通栏表头 —— 与出成率总览 (YieldCardTable, PR #1991) 同一套观感。
  客户原话「每个表头套了独立边框盒子」: 盒子感来自 加深的表头底色 + 逐格加深的右边框
  + 每格一个自带外壳的控件, 三层叠加。三层都不再覆盖 (交回 el-table 默认),
  排序入口保留为无外壳的 .batch-sort-trigger。
*/
.batch-data-table {
  --el-table-border-color: #dce3ec;
  --el-table-row-hover-bg-color: #f6faff;

  :deep(.el-table__header th.el-table__cell) {
    height: 46px;
    padding: 0;
  }

  :deep(.el-table__body td.el-table__cell) {
    height: 54px;
    padding: 0;
  }

  :deep(.el-table__cell .cell) {
    padding: 0 12px;
    line-height: 20px;
  }

  :deep(.el-table__column-filter-trigger) {
    margin-left: 6px;
    color: #697586;
  }

  /* 按钮只提供点击热区与无障碍名字, 视觉上完全等同于普通表头文字 */
  :deep(.batch-sort-trigger) {
    display: inline-flex;
    align-items: center;
    justify-content: inherit;
    min-height: 32px;
    padding: 0;
    border: 0;
    background: transparent;
    color: inherit;
    font: inherit;
    font-weight: inherit;
    cursor: pointer;
    touch-action: manipulation;
  }

  :deep(.batch-sort-trigger:hover) {
    color: var(--el-color-primary, #409eff);
  }

  :deep(.batch-sort-trigger:focus-visible) {
    outline: 2px solid var(--el-color-primary, #409eff);
    outline-offset: 2px;
  }
}

.plan-group-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.plan-group-title {
  font-weight: 600;
  color: var(--text-color-primary, #303133);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.el-table__body tr.plan-group-row > td.el-table__cell .cell) {
  font-weight: 600;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}
</style>
