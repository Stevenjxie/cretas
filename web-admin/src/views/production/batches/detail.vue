<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { getBatchWip, type WipRowItem } from '@/api/processProduction';
import { ElMessage } from 'element-plus';
import { ArrowLeft, Refresh } from '@element-plus/icons-vue';
import { formatDateTime } from '@/utils/dateFormat';
import AttachmentList from '@/components/attachment/AttachmentList.vue';
import AttachmentUploadButton from '@/components/attachment/AttachmentUploadButton.vue';
import type { TableRow } from '@/types/api';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const batchId = computed(() => route.params.id as string);
const canViewPrice = computed(() => permissionStore.canViewPrice);

// Issue #760: detail 页消费 ?mode=edit query
// 编辑 button (PR #755) 跳转时带 ?mode=edit, 本页据此切换 readonly vs editable.
// 当前 detail 页主要展示 KPI / 描述 / 成本 / 时间线 — 无 inline 表单控件,
// 编辑入口通过顶部"编辑批次"按钮跳回 list dialog (router-back) 实现.
// 此变更:
//  1. isEditMode 反映 query 状态
//  2. 顶部 header 显示 mode tag (查看 / 编辑)
//  3. 仅 edit 模式显示"返回列表编辑"按钮 (跳 list.vue 触发 edit dialog)
const isEditMode = computed(() => route.query.mode === 'edit');

const loading = ref(false);
const batch = ref<TableRow | null>(null);
const timeline = ref<TableRow[]>([]);
const attachmentRefreshKey = ref(0);
// T4-D4 (issue #533): F006 customer wants 原料消耗记录 visible on batch detail.
// Backend endpoint /processing/material-consumptions/batch/{productionBatchId} (MaterialConsumptionController:151)
// returns the consumption rows for this batch's production plan.
const consumptions = ref<TableRow[]>([]);
const yieldData = ref<any | null>(null);
// G6/G7 Wave 4: 半成品库存 (WIP) — 每道工序中间品存量 (产出/已领/余额/状态)
const wipRows = ref<WipRowItem[]>([]);

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value || !batchId.value) return;

  loading.value = true;
  try {
    const [batchRes, timelineRes, yieldRes, wipRes] = await Promise.allSettled([
      get(`/${factoryId.value}/processing/batches/${batchId.value}`),
      get(`/${factoryId.value}/processing/batches/${batchId.value}/timeline`),
      get(`/${factoryId.value}/production/batches/${batchId.value}/yield`),
      getBatchWip(factoryId.value, batchId.value)
    ]);

    if (batchRes.status === 'fulfilled' && batchRes.value.success) {
      batch.value = batchRes.value.data;
      // Once we know productionBatchId (= batchId), load consumption records.
      // Endpoint path uses productionBatchId param name; for batches, batchId === productionBatchId.
      await loadConsumptions();
    } else {
      ElMessage.error('加载批次详情失败');
    }

    if (timelineRes.status === 'fulfilled' && timelineRes.value.success) {
      timeline.value = timelineRes.value.data || [];
    }

    if (yieldRes.status === 'fulfilled' && yieldRes.value.success
        && yieldRes.value.data?.steps?.length > 0) {
      yieldData.value = yieldRes.value.data;
    } else {
      yieldData.value = null;
    }

    // G6/G7 Wave 4: WIP 库存行 (无则空数组 → WIP 区 v-if 隐藏, 诚实空态)
    if (wipRes.status === 'fulfilled' && wipRes.value.success && Array.isArray(wipRes.value.data)) {
      wipRows.value = wipRes.value.data;
    } else {
      wipRows.value = [];
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

async function loadConsumptions() {
  if (!factoryId.value || !batchId.value) return;
  try {
    const res = await get(`/${factoryId.value}/processing/material-consumptions/batch/${batchId.value}`);
    if (res.success && res.data) {
      consumptions.value = Array.isArray(res.data) ? res.data : (res.data.content || []);
    }
  } catch {
    // Interceptor shows toast. Consumption block gracefully hides via v-if length check.
  }
}

function goBack() {
  router.push('/production/batches');
}

// Issue #760: 回 list 并提示打开 edit dialog (用 query 标识)
function goBackToListWithEdit() {
  router.push({
    path: '/production/batches',
    query: { editId: batchId.value },
  });
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    PLANNED: 'info',
    PENDING: 'info',
    IN_PROGRESS: 'warning',
    PAUSED: 'warning',
    COMPLETED: 'success',
    CANCELLED: 'danger'
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    PLANNED: '待生产',
    PENDING: '待生产',
    IN_PROGRESS: '生产中',
    PAUSED: '已暂停',
    COMPLETED: '已完成',
    CANCELLED: '已取消'
  };
  return map[status?.toUpperCase()] || status;
}

function getQualityStatusText(status: string) {
  const map: Record<string, string> = {
    PENDING_INSPECTION: '待检验',
    INSPECTING: '检验中',
    PASSED: '已通过',
    FAILED: '不合格',
    PARTIAL_PASS: '部分合格',
    REWORK_REQUIRED: '需返工'
  };
  return map[status?.toUpperCase()] || status || '-';
}

function getQualityStatusType(status: string) {
  const map: Record<string, string> = {
    PENDING_INSPECTION: 'info',
    INSPECTING: 'warning',
    PASSED: 'success',
    FAILED: 'danger',
    PARTIAL_PASS: 'warning',
    REWORK_REQUIRED: 'danger'
  };
  return map[status?.toUpperCase()] || 'info';
}

function formatNum(val: unknown, suffix = '') {
  if (val === null || val === undefined) return '-';
  const n = Number(val);
  return isNaN(n) ? '-' : n.toLocaleString('zh-CN') + suffix;
}

function formatCost(val: unknown) {
  if (val === null || val === undefined) return '-';
  const n = Number(val);
  return isNaN(n) ? '-' : '¥' + n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatPercent(val: unknown) {
  if (val === null || val === undefined) return '-';
  const n = Number(val);
  return isNaN(n) ? '-' : n.toFixed(1) + '%';
}

function formatDuration(minutes: number | null) {
  if (!minutes) return '-';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}小时${m > 0 ? m + '分钟' : ''}` : `${m}分钟`;
}

// 单元3: 有 YIELD 数据时用末道产出回填"实际产量"
const hasYield = computed(() => !!yieldData.value?.steps?.length);
const displayActualQuantity = computed(() =>
  hasYield.value ? yieldData.value.lastStepOutput : batch.value?.actualQuantity);
const displayActualUnit = computed(() =>
  hasYield.value ? (yieldData.value.lastStepOutputUnit || '') : (batch.value?.unit || ''));
// audit YIELD-1: 跨单位 cumulative=null 显 —, 不能 *100 (null*100===0 会误显 0.0%)
// P0-2: 跨单位且无 cumulative → 标"跨单位不可比, 需配产品标准克重" (诚实, 不显 0/—)
const cumulativeDisplay = computed(() => {
  const yd = yieldData.value;
  const r = yd?.cumulativeYieldRate;
  if (r != null) return formatPercent(r * 100);
  const inU = yd?.firstStepInputUnit;
  const outU = yd?.lastStepOutputUnit;
  if (inU != null && outU != null && inU !== outU) {
    return '跨单位不可比, 需配产品标准克重';
  }
  return '—';
});

// G8 Wave 4 (C): 进行中标注 — 在制半成品未计入成品, 出成率偏低且会变 (cumulativeYieldRate 仍是 A 完工口径)
const yieldInProgress = computed(() => yieldData.value?.inProgress === true);
const wipInProgressText = computed(() => {
  const yd = yieldData.value;
  if (!yd?.inProgress) return '';
  const q = yd.wipInProgressQuantity;
  if (q == null || Number(q) <= 0) return '生产进行中, 出成率完工后才锁定';
  const u = yd.wipInProgressUnit || '';
  return `进行中: 含 ${formatNum(q)} ${u} 在制半成品未计入成品, 出成率完工后才锁定`;
});

// G6/G7 Wave 4: WIP 区 — 仅有 WIP 行时显示
const hasWip = computed(() => wipRows.value.length > 0);
function getWipStatusText(status: string) {
  const map: Record<string, string> = {
    AVAILABLE: '可领用',
    DEPLETED: '已领空',
    RETURNED: '已退回'
  };
  return map[status?.toUpperCase()] || status || '-';
}
function getWipStatusType(status: string) {
  const map: Record<string, string> = {
    AVAILABLE: 'success',
    DEPLETED: 'info',
    RETURNED: 'warning'
  };
  return map[status?.toUpperCase()] || 'info';
}

// P0-2 review fix: 末道产出单位 (份/盒) ≠ 批次原计划单位 (kg) 时, 后端已把 efficiency/unitCost
// 置 null (跨单位无意义)。前端据 plannedUnit≠unit 显诚实提示, 而非裸 "-" (易误读为"无数据")。
// 镜像 cumulativeDisplay 跨单位做法。同单位批次 plannedUnit 为 null → 走原 formatPercent/formatCost。
const isCrossUnit = computed(() => {
  const pu = batch.value?.plannedUnit;
  const u = batch.value?.unit;
  return pu != null && pu !== '' && u != null && pu !== u;
});
const efficiencyDisplay = computed(() =>
  isCrossUnit.value ? '跨单位不可比' : formatPercent(batch.value?.efficiency));
const unitCostDisplay = computed(() =>
  isCrossUnit.value ? '跨单位不可比' : formatCost(batch.value?.unitCost));

function getTimelineIcon(type: string) {
  const map: Record<string, string> = {
    CREATED: 'primary',
    STARTED: 'primary',
    PAUSED: 'warning',
    RESUMED: 'primary',
    COMPLETED: 'success',
    CANCELLED: 'danger'
  };
  return map[type?.toUpperCase()] || 'primary';
}
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <!-- Empty state -->
    <el-card v-if="!loading && !batch" shadow="never">
      <el-empty description="批次数据不存在">
        <el-button @click="goBack">返回列表</el-button>
      </el-empty>
    </el-card>

    <template v-if="batch">
      <!-- Header -->
      <div class="detail-header">
        <div class="header-left">
          <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
          <h2 class="batch-title">{{ batch.batchNumber }}</h2>
          <el-tag :type="getStatusType(batch.status)" size="large">
            {{ getStatusText(batch.status) }}
          </el-tag>
          <!-- Issue #760: 显式标记当前模式 -->
          <el-tag :type="isEditMode ? 'warning' : 'info'" size="large" effect="plain">
            {{ isEditMode ? '编辑模式' : '查看模式' }}
          </el-tag>
        </div>
        <div style="display:flex;gap:8px">
          <!-- Issue #760: edit mode 提供回 list 触发 edit dialog 的入口 -->
          <el-button
            v-if="isEditMode"
            type="primary"
            plain
            @click="goBackToListWithEdit"
          >在列表编辑</el-button>
          <el-button :icon="Refresh" @click="loadData">刷新</el-button>
        </div>
      </div>

      <!-- KPI Cards -->
      <div class="kpi-row">
        <div class="kpi-card">
          <div class="kpi-label">计划数量</div>
          <div class="kpi-value">{{ formatNum(batch.plannedQuantity) }}</div>
          <div class="kpi-unit">{{ batch.unit || '' }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">实际产量</div>
          <div class="kpi-value" :class="{ 'text-success': Number(displayActualQuantity) > 0 }">
            {{ formatNum(displayActualQuantity) }}
          </div>
          <div class="kpi-unit">{{ displayActualUnit }}</div>
        </div>
        <div v-if="hasYield" class="kpi-card">
          <div class="kpi-label">
            {{ yieldInProgress ? '累计出成率 (进行中)' : '累计出成率' }}
          </div>
          <div class="kpi-value">{{ cumulativeDisplay }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">良品率</div>
          <div class="kpi-value" :class="{
            'text-success': batch.yieldRate >= 95,
            'text-warning': batch.yieldRate >= 80 && batch.yieldRate < 95,
            'text-danger': batch.yieldRate > 0 && batch.yieldRate < 80
          }">
            {{ formatPercent(batch.yieldRate) }}
          </div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">完成效率</div>
          <div class="kpi-value">{{ efficiencyDisplay }}</div>
        </div>
        <div v-if="canViewPrice" class="kpi-card">
          <div class="kpi-label">单位成本</div>
          <div class="kpi-value">{{ unitCostDisplay }}</div>
        </div>
      </div>

      <!-- Detail Sections -->
      <div class="detail-grid">
        <!-- Basic Info -->
        <el-card shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">基本信息</span>
          </template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="批次号">{{ batch.batchNumber }}</el-descriptions-item>
            <el-descriptions-item label="产品类型">{{ batch.productName || batch.productType || '-' }}</el-descriptions-item>
            <el-descriptions-item label="生产状态">
              <el-tag :type="getStatusType(batch.status)" size="small">
                {{ getStatusText(batch.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="质量状态">
              <el-tag v-if="batch.qualityStatus" :type="getQualityStatusType(batch.qualityStatus)" size="small">
                {{ getQualityStatusText(batch.qualityStatus) }}
              </el-tag>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="负责人">{{ batch.supervisorName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="工人数">{{ batch.workerCount || '-' }} 人</el-descriptions-item>
            <el-descriptions-item label="生产线">{{ batch.equipmentName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="工作时长">{{ formatDuration(batch.workDurationMinutes) }}</el-descriptions-item>
            <el-descriptions-item label="开始时间">{{ formatDateTime(batch.startTime) }}</el-descriptions-item>
            <el-descriptions-item label="结束时间">{{ formatDateTime(batch.endTime) }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(batch.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatDateTime(batch.updatedAt) }}</el-descriptions-item>
            <el-descriptions-item v-if="batch.notes" label="备注" :span="2">{{ batch.notes }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- Quantity & Quality -->
        <el-card shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">产量与质量</span>
          </template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="计划数量">{{ formatNum(batch.plannedQuantity) }} {{ batch.unit }}</el-descriptions-item>
            <el-descriptions-item label="实际产量">{{ formatNum(batch.actualQuantity) }} {{ batch.unit }}</el-descriptions-item>
            <el-descriptions-item label="良品数量">{{ formatNum(batch.goodQuantity) }} {{ batch.unit }}</el-descriptions-item>
            <el-descriptions-item label="不良品数量">
              <span :class="{ 'text-danger': batch.defectQuantity > 0 }">
                {{ formatNum(batch.defectQuantity) }} {{ batch.unit }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="良品率">
              <span :class="{
                'text-success': batch.yieldRate >= 95,
                'text-warning': batch.yieldRate >= 80 && batch.yieldRate < 95,
                'text-danger': batch.yieldRate > 0 && batch.yieldRate < 80
              }">
                {{ formatPercent(batch.yieldRate) }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="完成效率">{{ efficiencyDisplay }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- Cost Breakdown -->
        <el-card v-if="canViewPrice" shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">成本明细</span>
          </template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="原料成本">{{ formatCost(batch.materialCost) }}</el-descriptions-item>
            <el-descriptions-item label="人工成本">{{ formatCost(batch.laborCost) }}</el-descriptions-item>
            <el-descriptions-item label="设备成本">{{ formatCost(batch.equipmentCost) }}</el-descriptions-item>
            <el-descriptions-item label="其他成本">{{ formatCost(batch.otherCost) }}</el-descriptions-item>
            <el-descriptions-item label="总成本">
              <span class="cost-total">{{ formatCost(batch.totalCost) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="单位成本">
              <template v-if="isCrossUnit">跨单位不可比</template>
              <template v-else>{{ formatCost(batch.unitCost) }}/{{ batch.unit }}</template>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- 单元3: 出成率·逐道报工 (audit YIELD-1/5/6, FE-VUE-6) -->
        <el-card v-if="hasYield" shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">出成率 · 逐道报工</span>
            <!-- G8 Wave 4 (C): 进行中标注 (展示层防呆) -->
            <el-tag v-if="yieldInProgress" type="warning" size="small" effect="plain" style="margin-left: 12px">
              进行中
            </el-tag>
          </template>
          <!-- G8 Wave 4 (C): 在制半成品提示条 — 数字偏低且会变, 完工后锁定 -->
          <el-alert
            v-if="yieldInProgress"
            :title="wipInProgressText"
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom: 12px"
          />
          <el-table :data="yieldData.steps" border stripe size="small" style="width: 100%">
            <el-table-column label="道" width="60" align="center">
              <template #default="{ row }">{{ row.processOrder }}</template>
            </el-table-column>
            <el-table-column label="工序" min-width="120" show-overflow-tooltip>
              <template #default="{ row }">{{ row.processName || ('第' + row.processOrder + '道') }}</template>
            </el-table-column>
            <el-table-column label="投入" width="130" align="right">
              <template #default="{ row }">{{ formatNum(row.totalInput) }} {{ row.inputUnit || '' }}</template>
            </el-table-column>
            <el-table-column label="产出" width="130" align="right">
              <template #default="{ row }">{{ formatNum(row.totalOutput) }} {{ row.outputUnit || '' }}</template>
            </el-table-column>
            <el-table-column label="出成率" width="110" align="center">
              <template #default="{ row }">
                <span v-if="!row.unitComparable">—</span>
                <span v-else :class="{ 'text-danger': row.yieldAlert }" :title="row.yieldAlert || ''">
                  {{ formatPercent(row.yieldRate * 100) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="结转" width="110" align="right">
              <template #default="{ row }">
                <span v-if="row.carryover == null">—</span>
                <span v-else :class="{ 'text-warning': Number(row.carryover) > 0 }">{{ formatNum(row.carryover) }}</span>
              </template>
            </el-table-column>
            <!-- P1-3 (G4): 逐道人数/工时 (张权 "用了多少人 / 一个人一个小时"); null 显 "—" -->
            <el-table-column label="人数" width="80" align="center">
              <template #default="{ row }">
                <span v-if="row.totalWorkers == null">—</span>
                <span v-else>{{ row.totalWorkers }} 人</span>
              </template>
            </el-table-column>
            <el-table-column label="工时" width="100" align="right">
              <template #default="{ row }">
                <span v-if="row.totalWorkMinutes == null">—</span>
                <span v-else>{{ row.totalWorkMinutes }} 分钟</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="yield-summary">
            合计: {{ formatNum(yieldData.firstStepInput) }} {{ yieldData.firstStepInputUnit || '' }}
            → {{ formatNum(yieldData.lastStepOutput) }} {{ yieldData.lastStepOutputUnit || '' }}
            &nbsp;累计出成率 {{ cumulativeDisplay }}
            <!-- P1-3 (G4): 整批工时/人次 — 跨道相加是"人次"(同一人多道重复计), 诚实标注 -->
            <span v-if="yieldData.totalWorkMinutes != null">&nbsp;·&nbsp;总工时 {{ yieldData.totalWorkMinutes }} 分钟</span>
            <span v-if="yieldData.totalWorkers != null">&nbsp;·&nbsp;总人次 {{ yieldData.totalWorkers }}</span>
          </div>
        </el-card>

        <!-- G6/G7 Wave 4: 半成品库存 (WIP) — 每道工序中间品产出/已领/余额/状态.
             端点 YieldReportController GET /wip. 无 WIP → 整块隐藏 (诚实空态). -->
        <el-card v-if="hasWip" shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">半成品库存 (WIP)</span>
            <span class="section-meta">共 {{ wipRows.length }} 道</span>
          </template>
          <el-table :data="wipRows" border stripe size="small" style="width: 100%">
            <el-table-column label="道" width="60" align="center">
              <template #default="{ row }">{{ row.processOrder ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="工序" min-width="120" show-overflow-tooltip>
              <template #default="{ row }">{{ row.processName || ('第' + (row.processOrder ?? '?') + '道') }}</template>
            </el-table-column>
            <el-table-column label="工序批次号" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.intermediateBatchNo || '-' }}</template>
            </el-table-column>
            <el-table-column label="产出" width="120" align="right">
              <template #default="{ row }">{{ formatNum(row.producedQuantity) }} {{ row.unit || '' }}</template>
            </el-table-column>
            <el-table-column label="已领" width="120" align="right">
              <template #default="{ row }">{{ formatNum(row.consumedQuantity) }} {{ row.unit || '' }}</template>
            </el-table-column>
            <el-table-column label="余额" width="120" align="right">
              <template #default="{ row }">
                <span :class="{ 'text-warning': Number(row.availableQuantity) > 0 }">
                  {{ formatNum(row.availableQuantity) }} {{ row.unit || '' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="getWipStatusType(row.status)" size="small">{{ getWipStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- T4-D4 (issue #533): F006 customer asked for raw_material consumption visibility on
             batch detail. Backend /processing/material-consumptions/batch/{id} (MaterialConsumption-
             Controller:151) returns enriched rows; this card renders them. Field names verified against
             enrichConsumptionWithMaps response Map (post-review fix for reviewer C1/I1/I2/I3/I4). -->
        <el-card v-if="consumptions.length > 0" shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">原料消耗记录</span>
            <span class="section-meta">共 {{ consumptions.length }} 条</span>
          </template>
          <el-table :data="consumptions" border stripe size="small" style="width: 100%">
            <el-table-column prop="materialTypeName" label="原料" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.materialTypeName || row.materialTypeId || '-' }}</template>
            </el-table-column>
            <el-table-column prop="batchNumber" label="批次号" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ row.batchNumber || '-' }}</template>
            </el-table-column>
            <el-table-column prop="quantity" label="消耗数量" width="120" align="right">
              <template #default="{ row }">{{ formatNum(row.quantity) }}</template>
            </el-table-column>
            <el-table-column prop="unit" label="单位" width="80" align="center">
              <template #default="{ row }">{{ row.unit || '-' }}</template>
            </el-table-column>
            <el-table-column v-if="canViewPrice" prop="unitPrice" label="单价" width="110" align="right">
              <template #default="{ row }">{{ formatCost(row.unitPrice) }}</template>
            </el-table-column>
            <el-table-column v-if="canViewPrice" prop="totalCost" label="小计" width="120" align="right">
              <template #default="{ row }">{{ formatCost(row.totalCost) }}</template>
            </el-table-column>
            <el-table-column prop="consumptionTime" label="消耗时间" width="160">
              <template #default="{ row }">{{ formatDateTime(row.consumedAt || row.consumptionTime || row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- Timeline -->
        <el-card v-if="timeline.length > 0" shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">生产时间线</span>
          </template>
          <el-timeline>
            <el-timeline-item
              v-for="(item, index) in timeline"
              :key="index"
              :type="getTimelineIcon(item.type || item.action)"
              :timestamp="formatDateTime(item.timestamp || item.createdAt)"
              placement="top"
            >
              <div class="timeline-content">
                <strong>{{ item.title || item.action || '-' }}</strong>
                <p v-if="item.description || item.notes">{{ item.description || item.notes }}</p>
                <p v-if="item.operatorName" class="timeline-operator">操作人: {{ item.operatorName }}</p>
              </div>
            </el-timeline-item>
          </el-timeline>
        </el-card>

        <!-- 附件 (C-ATT-1) — 现场照片 / 出料单据 / 质检报告 -->
        <el-card class="section-card" shadow="never" style="margin-top: 16px">
          <template #header>
            <span>附件</span>
          </template>
          <AttachmentList
            entity-type="PRODUCTION_BATCH"
            :entity-id="String(batchId)"
            :factory-id="factoryId"
            :refresh-key="attachmentRefreshKey"
          />
          <div style="margin-top: 12px">
            <AttachmentUploadButton
              entity-type="PRODUCTION_BATCH"
              :entity-id="String(batchId)"
              :factory-id="factoryId"
              business-tag="BATCH_EVIDENCE"
              @uploaded="attachmentRefreshKey++"
            />
          </div>
        </el-card>
      </div>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  overflow-y: auto;
  padding: 16px 20px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;

  .header-left {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .batch-title {
    font-size: 18px;
    font-weight: 600;
    margin: 0;
    color: var(--text-color-primary, #303133);
  }
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.kpi-card {
  background: #fff;
  border: 1px solid var(--border-color-lighter, #ebeef5);
  border-radius: 8px;
  padding: 16px 20px;
  text-align: center;

  .kpi-label {
    font-size: 13px;
    color: var(--text-color-secondary, #909399);
    margin-bottom: 8px;
  }

  .kpi-value {
    font-size: 24px;
    font-weight: 700;
    color: var(--text-color-primary, #303133);
    line-height: 1.2;
  }

  .kpi-unit {
    font-size: 12px;
    color: var(--text-color-secondary, #909399);
    margin-top: 4px;
  }
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.detail-card {
  :deep(.el-card__header) {
    padding: 12px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  }
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-color-primary, #303133);
}

.section-meta {
  font-size: 13px;
  color: #909399;
  margin-left: 12px;
}

.cost-total {
  font-weight: 700;
  color: var(--el-color-primary);
  font-size: 15px;
}

.text-success { color: #67C23A; }
.text-warning { color: #E6A23C; }
.text-danger { color: #F56C6C; }

.yield-summary {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  font-weight: 600;
  color: var(--text-color-primary, #303133);
}

.timeline-content {
  p {
    margin: 4px 0 0;
    font-size: 13px;
    color: var(--text-color-secondary, #909399);
  }

  .timeline-operator {
    font-size: 12px;
    color: var(--text-color-placeholder, #C0C4CC);
  }
}

@media (max-width: 1200px) {
  .kpi-row {
    grid-template-columns: repeat(3, 1fr);
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
