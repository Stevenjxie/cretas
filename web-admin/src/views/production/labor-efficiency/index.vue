<script setup lang="ts">
/**
 * SP9 人效对比页 — LaborEfficiencyCompare
 *
 * 后端: GET /api/mobile/{factoryId}/labor-efficiency/compare
 * 参数: startDate (REQUIRED), endDate (REQUIRED), productTypeId? (optional)
 *
 * @PriceSensitive: 所有成本/金额字段对非财务角色为 null，严禁 JS 端重算，一律显示 "—"。
 * 注意: 本页为 M3 双口径对比表。M4 达成率汇总 + step-breakdown 端点见人效看板 (dashboard.vue)。
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';
import { Search } from '@element-plus/icons-vue';
import { get } from '@/api/request';
import {
  getLaborEfficiencyCompare,
  type LaborEfficiencyCompareDTO,
  type LaborVarianceItemDTO,
} from '@/api/laborEfficiency';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const router = useRouter();

// ============================================================================
// 产品类型选项
// ============================================================================

interface ProductTypeOption {
  id: string;
  name: string;
}
const productTypes = ref<ProductTypeOption[]>([]);

async function loadProductTypes() {
  try {
    const res = await get<ProductTypeOption[]>(`/${factoryId.value}/product-types/active`);
    if (res.success && res.data) productTypes.value = res.data;
  } catch {
    // 非阻塞
  }
}

// ============================================================================
// 查询条件
// ============================================================================

// 默认近30天
function defaultDateRange(): [string, string] {
  const end = new Date();
  const start = new Date();
  start.setDate(start.getDate() - 30);
  const fmt = (d: Date) => d.toISOString().slice(0, 10);
  return [fmt(start), fmt(end)];
}

const [defaultStart, defaultEnd] = defaultDateRange();
const dateRange = ref<[string, string]>([defaultStart, defaultEnd]);
const selectedProductTypeId = ref<string | null>(null);

// ============================================================================
// 数据
// ============================================================================

const compareRows = ref<LaborEfficiencyCompareDTO[]>([]);
const loading = ref(false);

async function loadData() {
  if (!factoryId.value) return;
  if (!dateRange.value || !dateRange.value[0] || !dateRange.value[1]) {
    ElMessage({ message: '请选择日期范围', type: 'warning', duration: 3000 });
    return;
  }
  loading.value = true;
  try {
    const res = await getLaborEfficiencyCompare(
      factoryId.value,
      dateRange.value[0],
      dateRange.value[1],
      selectedProductTypeId.value || null,
    );
    if (res.success && res.data) {
      compareRows.value = res.data;
    } else {
      ElMessage({ message: res.message || '加载失败', type: 'error', duration: 0, showClose: true });
    }
  } catch {
    ElMessage({ message: '网络错误，请重试', type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

// ============================================================================
// 格式化工具
// ============================================================================

function fmt(v: number | null | undefined, prefix = '¥', decimals = 2): string {
  if (v == null) return '—';
  return `${prefix}${v.toLocaleString('zh-CN', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  })}`;
}

function fmtPct(v: number | null | undefined): string {
  if (v == null) return '—';
  const sign = v > 0 ? '+' : '';
  return `${sign}${v.toFixed(2)}%`;
}

function fmtMin(minutes: number | null | undefined): string {
  if (minutes == null) return '—';
  if (minutes < 60) return `${minutes} 分`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m > 0 ? `${h}h${m}m` : `${h}h`;
}

function varianceStatusType(status: string): string {
  switch (status) {
    case 'NORMAL': return 'success';
    case 'WARNING': return 'warning';
    case 'CRITICAL': return 'danger';
    default: return 'info';
  }
}

function varianceStatusLabel(status: string): string {
  switch (status) {
    case 'NORMAL': return '正常';
    case 'WARNING': return '预警';
    case 'CRITICAL': return '严重超支';
    default: return status;
  }
}

function varianceRateClass(varianceRate: number | null, status: string): string {
  if (varianceRate == null) return '';
  if (status === 'CRITICAL') return 'text-critical';
  if (status === 'WARNING') return 'text-warning';
  return 'text-normal';
}

function achievementAlertLabel(alert: string | null): string {
  if (alert === 'BELOW_ALERT') return '低于标准';
  if (alert === 'ABOVE_ALERT') return '高于标准';
  return '—';
}

// ============================================================================
// 展开行 — 工序明细
// ============================================================================

const expandedRows = ref<Set<string>>(new Set());

function toggleExpand(row: LaborEfficiencyCompareDTO) {
  if (expandedRows.value.has(row.batchNumber)) {
    expandedRows.value.delete(row.batchNumber);
  } else {
    expandedRows.value.add(row.batchNumber);
  }
}

// ============================================================================
// 初始化
// ============================================================================

onMounted(async () => {
  await loadProductTypes();
  await loadData();
});
</script>

<template>
  <div class="labor-efficiency-page">
    <div class="page-header">
      <h2 class="title">人效对比分析</h2>
      <el-button
        type="primary"
        plain
        size="small"
        style="margin-left: auto"
        @click="router.push({ name: 'LaborEfficiencyDashboard' })"
      >
        查看达成率看板 →
      </el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      title="成本字段对非财务角色不可见（显示 —）。对比实际 vs 报价人工成本，识别超支批次。"
      style="margin-bottom: 16px"
    />

    <!-- 查询条件 -->
    <el-card shadow="never" class="filter-card">
      <el-form inline>
        <el-form-item label="日期范围">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item label="产品">
          <el-select
            v-model="selectedProductTypeId"
            clearable
            placeholder="全部产品"
            style="width: 200px"
          >
            <el-option
              v-for="pt in productTypes"
              :key="pt.id"
              :label="pt.name"
              :value="pt.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" :loading="loading" @click="loadData">
            查询
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 对比表 -->
    <el-card shadow="never" v-loading="loading" class="data-card">
      <el-table
        :data="compareRows"
        stripe
        empty-text="暂无数据 — 请调整日期范围后重新查询"
        row-key="batchNumber"
      >
        <el-table-column prop="batchNumber" label="批次号" min-width="160" />
        <el-table-column prop="productName" label="产品" min-width="160" />

        <!-- 报价成本 -->
        <el-table-column label="报价人工/kg" align="right" min-width="130">
          <template #default="{ row }">{{ fmt(row.quotedLaborCostPerKg) }}</template>
        </el-table-column>
        <el-table-column label="实际人工/kg" align="right" min-width="130">
          <template #default="{ row }">{{ fmt(row.actualLaborCostPerKg) }}</template>
        </el-table-column>

        <!-- 报价/实际 per 箱 -->
        <el-table-column label="报价人工/箱" align="right" min-width="130">
          <template #default="{ row }">{{ fmt(row.quotedLaborCostPerBox) }}</template>
        </el-table-column>
        <el-table-column label="实际人工/箱" align="right" min-width="130">
          <template #default="{ row }">{{ fmt(row.actualLaborCostPerBox) }}</template>
        </el-table-column>

        <!-- 偏差率 + 状态 -->
        <el-table-column label="偏差率" align="right" min-width="110">
          <template #default="{ row }">
            <span :class="varianceRateClass(row.varianceRate, row.varianceStatus)">
              {{ fmtPct(row.varianceRate) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" min-width="100">
          <template #default="{ row }">
            <el-tag :type="varianceStatusType(row.varianceStatus)" size="small">
              {{ varianceStatusLabel(row.varianceStatus) }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 展开工序明细 -->
        <el-table-column label="工序" align="center" min-width="80">
          <template #default="{ row }">
            <el-button
              v-if="row.stepDetails && row.stepDetails.length > 0"
              link
              size="small"
              @click="toggleExpand(row)"
            >
              {{ expandedRows.has(row.batchNumber) ? '收起' : `${row.stepDetails.length} 道` }}
            </el-button>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 展开区域: 工序级明细 -->
      <template v-for="row in compareRows" :key="row.batchNumber + '-steps'">
        <div v-if="expandedRows.has(row.batchNumber)" class="step-detail-block">
          <div class="step-detail-header">
            工序明细 — {{ row.productName }}（{{ row.batchNumber }}）
          </div>
          <el-table
            :data="row.stepDetails"
            size="small"
            border
            style="margin: 8px 0 16px"
          >
            <el-table-column prop="processName" label="工序" min-width="140" />
            <el-table-column label="工时" align="right" min-width="90">
              <template #default="{ step }: { step: LaborVarianceItemDTO }">
                {{ fmtMin((step as unknown as LaborVarianceItemDTO).totalWorkMinutes) }}
              </template>
            </el-table-column>
            <el-table-column label="人数" align="right" min-width="80">
              <template #default="{ row: step }">
                {{ (step as LaborVarianceItemDTO).totalWorkers ?? '—' }}
              </template>
            </el-table-column>
            <el-table-column label="工序人工成本" align="right" min-width="130">
              <template #default="{ row: step }">
                {{ fmt((step as LaborVarianceItemDTO).laborCost) }}
              </template>
            </el-table-column>
            <el-table-column label="每箱人工成本" align="right" min-width="130">
              <template #default="{ row: step }">
                {{ fmt((step as LaborVarianceItemDTO).laborCostPerBox) }}
              </template>
            </el-table-column>
            <el-table-column label="达成率" align="right" min-width="100">
              <template #default="{ row: step }">
                <span
                  v-if="(step as LaborVarianceItemDTO).achievementRate != null"
                  :class="
                    (step as LaborVarianceItemDTO).achievementAlert === 'BELOW_ALERT'
                      ? 'text-warning'
                      : (step as LaborVarianceItemDTO).achievementAlert === 'ABOVE_ALERT'
                        ? 'text-critical'
                        : 'text-normal'
                  "
                >
                  {{ ((step as LaborVarianceItemDTO).achievementRate as number).toFixed(1) }}%
                </span>
                <span v-else class="text-muted">未设标准</span>
              </template>
            </el-table-column>
            <el-table-column label="达成状态" align="center" min-width="100">
              <template #default="{ row: step }">
                {{ achievementAlertLabel((step as LaborVarianceItemDTO).achievementAlert) }}
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.labor-efficiency-page {
  padding: 20px;
}
.page-header {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
  color: #303133;
}
.filter-card {
  margin-bottom: 16px;
}
.data-card {
  margin-bottom: 16px;
}
.step-detail-block {
  padding: 0 16px;
  background: #f9f9f9;
  border-top: 1px solid #ebeef5;
}
.step-detail-header {
  padding: 8px 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
}
.text-critical {
  color: #f56c6c;
  font-weight: 600;
}
.text-warning {
  color: #e6a23c;
  font-weight: 600;
}
.text-normal {
  color: #67c23a;
}
.text-muted {
  color: #c0c4cc;
}
</style>
